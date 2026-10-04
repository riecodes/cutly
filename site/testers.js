import { FB, firebaseApp } from "./firebase-config.js";

// One document per address, keyed by the address. The rules refuse a second write to the same key,
// so "permission-denied" here means "already on the list", which is a success to the visitor.
export async function join(email, phone) {
  const [app, { getFirestore, doc, setDoc, serverTimestamp }] =
    await Promise.all([firebaseApp(), import(FB + "firebase-firestore.js")]);
  try { await setDoc(doc(getFirestore(app), "testers", email), { email, phone, at: serverTimestamp() }); }
  catch (e) { if (e.code !== "permission-denied") throw e; }
}
