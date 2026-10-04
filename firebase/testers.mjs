// The Play closed test signups as JSON, read with this machine's `firebase login` (the project owner).
//   node firebase/testers.mjs
// ponytail: the token comes from firebase-tools' own auth module, so a CLI update can break the
// require below. Move to a service account key if it does, or if this has to run off this machine.
import { execSync } from "node:child_process";
import { createRequire } from "node:module";

const auth = createRequire(import.meta.url)(execSync("npm root -g").toString().trim() + "/firebase-tools/lib/auth.js");
const account = auth.getGlobalDefaultAccount();
if (!account) { console.error("Not logged in. Run: firebase login"); process.exit(1); }
const { access_token } = await auth.getAccessToken(account.tokens.refresh_token, account.tokens.scopes || []);

const url = "https://firestore.googleapis.com/v1/projects/cutly-riecodes/databases/(default)/documents/testers?pageSize=300";
const out = [];
let page = "";
do {
  const r = await fetch(url + page, { headers: { authorization: "Bearer " + access_token } });
  if (!r.ok) { console.error("Firestore said " + r.status + ": " + await r.text()); process.exit(1); }
  const j = await r.json();
  for (const d of j.documents || []) {
    // Each Firestore field arrives wrapped in its type ({ stringValue: ... }); only the value is wanted.
    out.push(Object.fromEntries(Object.entries(d.fields).map(([k, v]) => [k, Object.values(v)[0]])));
  }
  page = j.nextPageToken ? "&pageToken=" + encodeURIComponent(j.nextPageToken) : "";
} while (page);
console.log(JSON.stringify(out.sort((a, b) => (a.at > b.at ? 1 : -1)), null, 2));
