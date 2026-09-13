---
title: Cutly
---

# Cutly

Shoot a take in clips, read it back as text, and cut the dead air out of it. Android, offline
by default, no account.

## Install

1. Download the latest `cutly-x.y.z.apk` from the
   [releases page](https://github.com/riecodes/cutly/releases/latest).
2. Open it on your phone. Android will ask you to allow installs from your browser or file
   manager the first time.
3. Updates install over the top: download the newer APK and open it.

Every release is signed with the same key, so your phone will refuse a build that did not
come from this project. The `.sha256` file next to each APK lets you check the download.

## Cloud features are opt-in

Transcription and captions work on the phone. If you want the cloud versions, paste your own
OpenAI or Gemini API key in **Settings**. The app asks before each upload and only ever sends
audio. See the [privacy policy](privacy) for the full picture.

## Source

[github.com/riecodes/cutly](https://github.com/riecodes/cutly), Apache-2.0.
