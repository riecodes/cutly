import { keepSpans } from "./silence.js";
import { take } from "./take.js";

const $ = (s) => document.querySelector(s);
const still = matchMedia("(prefers-reduced-motion: reduce)");

/* Header hairline once the page moves. */
const header = $("header");
const onScroll = () => header.classList.toggle("scrolled", scrollY > 8);
addEventListener("scroll", onScroll, { passive: true });
onScroll();

/* Sections have their own paths (/how, /try, ...) that vercel.json rewrites to this page. */
const SECTIONS = ["how", "try", "fit", "keeps"];
const go = (id, smooth) => document.getElementById(id)?.scrollIntoView({ behavior: smooth && !still.matches ? "smooth" : "auto" });
const first = location.pathname.slice(1);
if (SECTIONS.includes(first)) requestAnimationFrame(() => go(first, false));
document.querySelectorAll("nav a").forEach((a) => a.addEventListener("click", (e) => {
  const id = a.getAttribute("href").slice(1);
  if (!SECTIONS.includes(id)) return;
  e.preventDefault();
  history.pushState(null, "", "/" + id);
  go(id, true);
}));

/* GitHub star count on every star button. Cached for the visit, since the unauthenticated API
   allows 60 calls an hour per visitor. On any failure the buttons just read "Star on GitHub". */
(async () => {
  const pills = document.querySelectorAll("[data-stars]");
  if (!pills.length) return;
  let n = null;
  try { n = JSON.parse(sessionStorage.getItem("cutly-stars")); } catch {}
  if (typeof n !== "number") {
    try {
      const r = await fetch("https://api.github.com/repos/riecodes/cutly", { headers: { accept: "application/vnd.github+json" } });
      if (r.ok) n = (await r.json()).stargazers_count;
      try { sessionStorage.setItem("cutly-stars", JSON.stringify(n)); } catch {}
    } catch {}
  }
  if (typeof n !== "number") return;
  const text = n >= 1000 ? (n / 1000).toFixed(n >= 10000 ? 0 : 1).replace(/\.0$/, "") + "k" : String(n);
  pills.forEach((p) => { p.querySelector(".num").textContent = text; p.hidden = false; });
})();

/* The film plays muted while it is on screen, and never by itself for reduced motion. */
const film = $("#film"), sound = $("#sound");
if (film) {
  new IntersectionObserver(([e]) => {
    if (e.isIntersecting && !still.matches) film.play().catch(() => {});
    else if (!e.isIntersecting) film.pause();
  }, { threshold: 0.4 }).observe(film);
  sound.addEventListener("click", () => {
    film.muted = !film.muted;
    if (!film.muted) { film.currentTime = film.currentTime < 1 ? 0 : film.currentTime; film.play().catch(() => {}); }
    sound.textContent = film.muted ? "Sound on" : "Sound off";
    sound.setAttribute("aria-pressed", String(!film.muted));
  });
}

/* Try the cut: the real take's loudness, cut by the app's own rules. */
const canvas = $("#wave");
if (canvas) {
  const levels = Float32Array.from(atob(take.levels), (c) => -c.charCodeAt(0) / 2);
  const { frameMs, durationMs } = take;
  const knobs = { thresholdDb: $("#kThreshold"), minSilenceMs: $("#kPause"), padMs: $("#kPad") };
  const shown = { thresholdDb: [$("#vThreshold"), " dB"], minSilenceMs: [$("#vPause"), " ms"], padMs: [$("#vPad"), " ms"] };
  const start = Object.fromEntries(Object.entries(knobs).map(([k, el]) => [k, el.value]));
  // This take sits between about -28 dB (room tone) and -11 dB (speech), so the drawing spans
  // -36..-6 dB to show that difference. Quieter frames draw flat; the cuts still use real values.
  const FLOOR = -36, CEIL = -6;

  const clock = (ms) => {
    const t = Math.round(ms / 100) / 10, m = Math.floor(t / 60), s = (t - m * 60).toFixed(1);
    return String(m).padStart(2, "0") + ":" + s.padStart(4, "0");
  };
  $("#inLen").textContent = clock(durationMs);

  const ctx = canvas.getContext("2d");
  let gaps = [];

  function draw() {
    const dpr = Math.min(devicePixelRatio || 1, 2);
    const w = canvas.clientWidth, h = canvas.clientHeight;
    if (canvas.width !== Math.round(w * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    const mid = h / 2, amp = h / 2 - 10;
    const height = (db) => Math.max(1, ((Math.min(Math.max(db, FLOOR), CEIL) - FLOOR) / (CEIL - FLOOR)) * amp);
    const msAt = (x) => (x / w) * durationMs;

    // Removed spans, full height, behind the bars.
    ctx.fillStyle = "rgba(234,68,90,.16)";
    for (const [a, b] of gaps) ctx.fillRect((a / durationMs) * w, 0, Math.max(1, ((b - a) / durationMs) * w), h);

    // One bar every 3 px, the loudest frame under it.
    const step = 3, perBar = (step / w) * levels.length;
    let g = 0;
    for (let x = 0; x < w; x += step) {
      const i0 = Math.floor((x / w) * levels.length), i1 = Math.max(i0 + 1, Math.floor(i0 + perBar));
      let peak = -Infinity;
      for (let i = i0; i < i1 && i < levels.length; i++) peak = Math.max(peak, levels[i]);
      const t = msAt(x + step / 2);
      while (g < gaps.length && gaps[g][1] < t) g++;
      const cut = g < gaps.length && gaps[g][0] <= t && t < gaps[g][1];
      const bh = height(peak);
      ctx.fillStyle = cut ? "#EA445A" : "#A0A0A8";
      ctx.fillRect(x, mid - bh, step - 1, bh * 2);
    }

    // Threshold, mirrored.
    const th = height(+knobs.thresholdDb.value);
    ctx.strokeStyle = "rgba(255,255,255,.9)";
    ctx.setLineDash([5, 5]);
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, mid - th + 0.5); ctx.lineTo(w, mid - th + 0.5);
    ctx.moveTo(0, mid + th - 0.5); ctx.lineTo(w, mid + th - 0.5);
    ctx.stroke();
    ctx.setLineDash([]);
  }

  function update() {
    const settings = {};
    for (const [k, el] of Object.entries(knobs)) {
      settings[k] = +el.value;
      const [out, unit] = shown[k];
      out.textContent = el.value + unit;
      el.style.setProperty("--p", ((el.value - el.min) / (el.max - el.min)) * 100 + "%");
    }
    const keeps = keepSpans(levels, frameMs, durationMs, settings);
    gaps = [];
    let cursor = 0;
    for (const [a, b] of keeps) { if (a > cursor) gaps.push([cursor, a]); cursor = b; }
    if (cursor < durationMs) gaps.push([cursor, durationMs]);
    const kept = keeps.reduce((s, [a, b]) => s + b - a, 0);
    $("#outLen").textContent = clock(kept);
    $("#outCuts").textContent = Math.max(0, keeps.length - 1);
    $("#outGone").textContent = ((durationMs - kept) / 1000).toFixed(1) + " s";
    draw();
  }

  Object.values(knobs).forEach((el) => el.addEventListener("input", update));
  $("#reset").addEventListener("click", () => { for (const [k, el] of Object.entries(knobs)) el.value = start[k]; update(); });
  new ResizeObserver(draw).observe(canvas);
  update();
}
