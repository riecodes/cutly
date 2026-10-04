const $ = (s) => document.querySelector(s);
const form = $("#testerForm"), note = $("#testerNote");
const say = (text, tone) => { note.textContent = text; note.dataset.tone = tone || ""; };

form.addEventListener("submit", async (e) => {
  e.preventDefault();
  const email = $("#gmail").value.trim().toLowerCase();
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/.test(email)) { say("That does not look like an email address.", "bad"); $("#gmail").focus(); return; }
  // Play invites testers by Google account, so anything but a Gmail address is no use here. The
  // Firestore rules refuse the rest too, so this message is a courtesy, not the gate.
  if (!/^[a-z0-9.+_-]+@(gmail|googlemail)\.com$/.test(email)) {
    say("Google Play invites testers by Google account, so this needs a Gmail address.", "bad"); $("#gmail").focus(); return;
  }
  const phone = $("#phone").value.trim();
  if (phone.length < 3) { say("Which phone, and which Android version?", "bad"); $("#phone").focus(); return; }
  if (!$("#stay").checked) { say("The test only counts if the app stays on your phone for the 14 days.", "bad"); $("#stay").focus(); return; }

  const btn = form.querySelector("button");
  btn.disabled = true;
  say("Saving…");
  try {
    const { join } = await import("./testers.js");
    await join(email, phone.slice(0, 120));
    form.reset();
    say("You are in. The Play invite goes to this Gmail.", "ok");
  } catch {
    say("That did not go through. Try again in a minute.", "bad");
  } finally {
    btn.disabled = false;
  }
});
