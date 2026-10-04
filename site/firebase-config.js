// Public by design: a Firebase web config identifies the project, it does not grant access.
// Access is decided in firebase/firestore.rules.
export const FB = "https://www.gstatic.com/firebasejs/10.14.1/";
export const config = {
  apiKey: "AIzaSyCfcYMTu13Pt-60JZI_CyaDKbyY1pG8YwA",
  authDomain: "cutly-riecodes.firebaseapp.com",
  projectId: "cutly-riecodes",
  appId: "1:425127186230:web:3c381ff647d40c29732d97"
};

// The reCAPTCHA v3 site key from Firebase console > App Check > Apps > Web. Public like the config
// above. Blank turns App Check off, so the site keeps working until the key is registered.
export const recaptchaSiteKey = "";

let started;
/** The one initialised app, with App Check attached once a site key is set. */
export function firebaseApp() {
  started = started || (async () => {
    const { initializeApp } = await import(FB + "firebase-app.js");
    const app = initializeApp(config);
    if (recaptchaSiteKey) {
      const { initializeAppCheck, ReCaptchaV3Provider } = await import(FB + "firebase-app-check.js");
      initializeAppCheck(app, { provider: new ReCaptchaV3Provider(recaptchaSiteKey), isTokenAutoRefreshEnabled: true });
    }
    return app;
  })();
  return started;
}
