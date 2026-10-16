import { FB, firebaseApp } from "./firebase-config.js";

const $ = (s) => document.querySelector(s);
const note = $("#adminNote"), list = $("#list"), rows = $("#rows");
const say = (text, tone) => { note.textContent = text; note.dataset.tone = tone || ""; };
let emails = [];

try {
  const [app, A, F] = await Promise.all([firebaseApp(), import(FB + "firebase-auth.js"), import(FB + "firebase-firestore.js")]);
  const auth = A.getAuth(app);

  $("#signIn").onclick = () => A.signInWithPopup(auth, new A.GoogleAuthProvider())
    .catch((e) => { if (e.code !== "auth/popup-closed-by-user") say("Sign-in failed: " + e.code, "bad"); });
  $("#signOut").onclick = () => A.signOut(auth);
  $("#copy").onclick = async () => {
    await navigator.clipboard.writeText(emails.join(", "));
    say(`Copied ${emails.length} addresses. Paste them into the Google Group's Add members box.`, "ok");
  };

  A.onAuthStateChanged(auth, async (user) => {
    $("#signIn").hidden = !!user; $("#signOut").hidden = !user; $("#copy").hidden = list.hidden = true;
    rows.replaceChildren(); emails = [];
    if (!user) return say("Sign in with the owner's Google account.");
    say("Loading…");
    try {
      const snap = await F.getDocs(F.query(F.collection(F.getFirestore(app), "testers"), F.orderBy("at")));
      // Signups are visitor input, so every cell goes in as text, never as HTML.
      snap.docs.forEach((d, i) => {
        const t = d.data(), tr = rows.insertRow();
        for (const v of [i + 1, t.email, t.phone, t.at?.toDate().toLocaleString() ?? ""]) tr.insertCell().textContent = v;
        emails.push(t.email);
      });
      list.hidden = $("#copy").hidden = !emails.length;
      say(emails.length ? `${emails.length} signed up.` : "Nobody has signed up yet.");
    } catch (e) {
      // The rules let one account read the list, matched by UID so no email sits in the public repo.
      say(e.code === "permission-denied"
        ? `${user.email} cannot read the list. Its UID is ${user.uid}: that is what firebase/firestore.rules checks.`
        : "Could not load the list: " + (e.code || e.message), "bad");
    }
  });
} catch {
  say("Firebase did not load. Reload the page.", "bad");
}
