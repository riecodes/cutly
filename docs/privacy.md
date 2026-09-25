---
title: Cutly privacy policy
---

# Cutly privacy policy

_Last updated: 13 September 2026_

Cutly is an Android app for recording, transcribing and cutting short videos. It has no
account system, no analytics, no advertising, and no crash reporting service. The developer
does not receive any data from the app.

## What the app does on your phone

- **Camera and microphone** are used only while you record a take. Clips are written to the
  app's private storage and, when you export, to the `Movies/Cutly` folder on your phone.
- **Videos you open** in the editor are copied into the app's private storage so a project
  can be resumed later. Deleting a project, or uninstalling the app, deletes that copy.
- **Transcripts and captions** are stored with the project, on the phone.
- **Nothing is backed up** to Google or transferred to a new phone: the app opts out of both.

## What can leave your phone, and when

Cutly works fully offline. Two optional features send data to a third party, and both happen
only when you tap them and confirm the upload:

- **Cloud transcription and captions.** If you paste your own Groq, OpenAI or Google Gemini API key
  in Settings, the app can send the **audio track** of a video you choose to that provider to
  get a transcript back. The video itself is never sent. The audio is deleted from the phone
  as soon as the transcript arrives. What the provider does with the audio is governed by
  their terms and privacy policy, not this one:
  [Groq](https://groq.com/privacy-policy/),
  [OpenAI](https://openai.com/policies/privacy-policy),
  [Google](https://policies.google.com/privacy).
- **Offline speech model download.** Choosing the Whisper engine downloads a speech model of
  about 100 MB from Hugging Face over HTTPS. That is an ordinary file download; no data about
  you is sent.

Your API keys are stored in the app's private storage on the phone and are never sent
anywhere except, as an authentication header, to the provider they belong to.

## Permissions

| Permission | Why |
| --- | --- |
| Camera | Recording a take. |
| Microphone | Recording sound with a take. Optional: without it, clips are silent. |
| Internet | Cloud transcription with your key, and the optional model download. |

## Children

Cutly is not directed at children under 13 and does not knowingly collect information from
anyone.

## Changes

Changes to this policy are published at this address with a new date at the top.

## Contact

Open an issue at [github.com/riecodes/cutly](https://github.com/riecodes/cutly/issues).
