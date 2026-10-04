// A line-for-line port of the app's SilenceDetector.keepSpans
// (app/src/main/java/com/eirmon/cutly/audio/SilenceDetector.kt), so the demo on the home page
// cuts exactly where the app would. Spans are [startMs, endMs] pairs.
export const DEFAULTS = { thresholdDb: -40, minSilenceMs: 350, padMs: 80, minKeepMs: 150, maxSpans: 120 };

export function keepSpans(levelsDb, frameMs, durationMs, settings) {
  const s = { ...DEFAULTS, ...settings };
  if (!levelsDb.length || durationMs <= 0) return [];

  // Runs of quiet frames long enough to be worth removing, already shrunk by the padding. A run
  // touching the start or the end is not padded on that side: there is no speech there to protect.
  let gaps = [], runStart = -1;
  for (let i = 0; i <= levelsDb.length; i++) {
    const quiet = i < levelsDb.length && levelsDb[i] < s.thresholdDb;
    if (quiet) { if (runStart < 0) runStart = i; continue; }
    if (runStart < 0) continue;
    const rawStart = runStart * frameMs;
    const rawEnd = i === levelsDb.length ? durationMs : i * frameMs;
    const start = rawStart === 0 ? 0 : rawStart + s.padMs;
    const end = rawEnd >= durationMs ? durationMs : rawEnd - s.padMs;
    if (end - start >= s.minSilenceMs) gaps.push([start, end]);
    runStart = -1;
  }

  // Keep only the longest gaps, so the cut count stays inside the app's Transformer budget.
  const maxGaps = Math.max(0, s.maxSpans - 1);
  if (gaps.length > maxGaps) {
    gaps = gaps.slice().sort((a, b) => (b[1] - b[0]) - (a[1] - a[0])).slice(0, maxGaps).sort((a, b) => a[0] - b[0]);
  }

  // Everything the gaps do not cover.
  const keeps = [];
  let cursor = 0;
  for (const [a, b] of gaps) { if (a > cursor) keeps.push([cursor, a]); cursor = b; }
  if (cursor < durationMs) keeps.push([cursor, durationMs]);

  // Fold away kept pieces too short to be a word; they would land as clicks.
  const merged = [];
  for (const k of keeps) {
    const prev = merged[merged.length - 1];
    if (prev && (k[1] - k[0] < s.minKeepMs || prev[1] - prev[0] < s.minKeepMs)) prev[1] = k[1];
    else merged.push(k.slice());
  }
  return merged;
}
