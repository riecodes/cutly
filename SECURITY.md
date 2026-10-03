# Security policy

## Reporting a vulnerability

Please do not open a public issue for a security problem. Report it privately through
[GitHub's private vulnerability reporting](https://github.com/riecodes/cutly/security/advisories/new)
instead. Include the app version (Settings shows it), the device and Android version, and the
steps that reproduce the problem.

You should get a reply within a week. Fixes ship in the next tagged release, and the advisory is
published once that release is out.

## Scope

Cutly has no server and no account system, so the interesting surface is on the phone and in the
build:

- the user's own cloud API keys (stored in app-private storage, excluded from backup)
- the audio uploaded to Groq, OpenAI or Gemini after the consent dialog
- the offline Whisper model download, which is pinned to a revision and SHA-256 verified
- the release signing pipeline in `.github/workflows/release.yml`

Problems in the cloud providers themselves belong with those providers.

## Supported versions

Only the latest release gets fixes.
