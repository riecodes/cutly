# Cutly

An Android video tool for short-form creators: shoot a take in clips, read it back as text, and cut
the dead air out of it. Kotlin and Compose, no FFmpeg, no account, and nothing leaves the phone
except the audio you explicitly ask to have transcribed.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

Three services on Android, behind one hub screen:

- **Camera** — a TikTok-style segmented video camera. Record a take as a series of clips, pause
  between them, discard the last clip, double-tap to flip lenses, then export either every clip as
  its own video or the whole take stitched into one.
- **Video to text** — pick any video on the phone and get an editable, timestamped transcript back
  in English and Tagalog.
- **Cut** — pick any video and get the dead air taken out of it, optionally with the transcript
  burned in as captions.

## Stack

| Concern | Choice |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose (Material 3) |
| Navigation | one `enum` and a `BackHandler` — two destinations is not a graph |
| Capture | CameraX (`camera-video`, `camera-compose`) |
| Export / concat | Media3 Transformer + Presentation effect |
| Delivery | MediaStore, `Movies/Cutly` |
| Transcription | Gemini API (`gemini-3.7-flash`), audio only |
| Silence detection | `MediaExtractor` + `MediaCodec`, RMS per 20 ms window |
| Captions | Media3 `CanvasOverlay` on the composition |
| Build | AGP 9 (built-in Kotlin), Gradle 9.7 |

Media3 Transformer replaces FFmpeg here: it drives the device's hardware codecs, adds no native
binaries to the APK, and can transmux without re-encoding when clip formats already match.

## The hub

`MainActivity` opens on `HomeScreen`, a card per service, and hands off to `CameraScreen` or the
system photo picker. Only the camera is a destination — the other two services are sheets over the
hub — so there is still nothing here a navigation library would help with, and the destination
stays an enum in `setContent` with one `BackHandler` for back.

The hub is deliberately the opposite surface from the rest of the app. The camera is a black tool
that has to disappear behind the viewfinder; the hub is a warm off-white page — `#f5f5f5` canvas,
white cards on 7%-black hairlines, one 52sp display headline at weight 800 with −4.5% tracking,
and a single accent (the record red the app already had) used only on the card marks. All
hierarchy comes from the size jump between the headline and everything else.

Motion is hand-written: one shared expo-out curve (`EaseOut`, `cubic-bezier(.23,1,.32,1)`), a
staggered fade-and-rise on enter, and a 0.97 scale on card press. `ValueAnimator.areAnimatorsEnabled()`
is Android's `prefers-reduced-motion`, and it skips the reveals straight to their resting state.

The display face is TikTok Sans at 800 rather than a rounded grotesk, because the family is already
shipped and licensed here — swap `TikTokSans` in `HomeScreen` for a rounded face if the roundness
matters more than the extra font file.

## The core design decision

Every clip is a separate, standalone MP4 on disk.

CameraX exposes `Recording.pause()` / `resume()`, but those produce a single continuous file, which
makes "discard last clip" impossible without re-encoding. So pause is implemented as *finalize the
current recording*, and resume as *start a new one*. The clip list is the source of truth; export is
the only step that touches a muxer.

That single choice is what makes discard, per-clip export, and mid-take lens switching all cheap.

## Feature notes

- **Pause / resume** — `stop()` finalizes clip N, the next `start()` opens clip N+1.
- **Discard last** — pop the list, delete the file. No media processing involved.
- **Double-tap flip** — CameraX cannot swap the bound `CameraSelector` under an active recording, so
  the current clip is finalized, the use cases are rebound, and recording auto-resumes on the new
  lens. The gap is sub-frame after concat.
- **Single tap** — tap to focus.
- **60s cap** — `FileOutputOptions.setDurationLimitMillis` is set to the take's *remaining* budget,
  so the running clip can never overshoot the total.
- **Process death** — the clip list is written to `cache/clips/session.idx` after every clip, and
  restored on launch. Android kills backgrounded camera apps aggressively.
- **Rotation** — the activity is locked to portrait, so an `OrientationEventListener` feeds
  `videoCapture.targetRotation` manually. Without it, clips shot sideways save sideways.
- **Mixed lenses** — both lenses are pinned to one `QualitySelector`, and every export item gets a
  `Presentation` effect, so front/back resolution differences do not break the merge.

## Transcription

Two entry points, one path. From the hub, **Transcribe a video** opens the system photo picker and
transcribes whatever is chosen. From the camera, the export sheet has a third action, **Transcribe
to text**, which does the same for the take.

Both strip the video track, concatenate the audio into one M4A with the same Transformer already
used for export, post it to the Gemini API as inline base64, and show the transcript in an editable
sheet with a copy button. Nothing is written to disk; the temp M4A is deleted whether the request
succeeds or fails. A picked video never becomes a `Clip`, so it never touches the take or the clip
store.

Gemini rather than a dedicated speech-to-text API because the target languages are English (United
States) and Tagalog/Filipino, and real Filipino speech switches between them inside a single
sentence. APIs that lock one language per utterance mangle that; a general model transcribes the
mix as spoken. The prompt asks for `[MM:SS]` line prefixes and forbids translation.

The speed effect is deliberately not applied to the transcription audio — a 3x take is
unintelligible to a speech model, and the transcript is of what was said.

### Setup

Put a personal key in `local.properties`, which is untracked:

```properties
gemini.api.key=<key from https://aistudio.google.com/apikey>
```

It reaches the app as `BuildConfig.GEMINI_API_KEY`. A missing key is not a build failure — the app
says `Set gemini.api.key in local.properties` when transcription is used. This is a personal-use
arrangement: the key ships inside the APK, so the debug build must not be handed to anyone else.

### Known ceilings

- Tagalog word error rate runs well above English on every current speech model, and proper nouns
  come back wrong often. That is why the transcript sheet is an editable text field, not a label.
- The request is inline base64, capped by Gemini at 20 MB total. A ten-minute take at the camera's
  AAC bitrate lands near 10 MB, so it fits, but the transcriber refuses anything larger up front
  rather than posting it to earn a 400.
- Gemini answers a refusal and a truncation with HTTP 200 and no text, so `parseTranscript` turns
  every empty-text shape into an error — otherwise a blocked take would look like silence.

## Cutting

Pick a video, and Cutly reports what it would remove before it removes anything:

    13s -> 6s
    2 cuts, 6s removed

Nothing is encoded until you press **Save cut**.

### Silence is an amplitude question, not a language one

The transcript is not used to place the cuts, even though it exists and carries `[MM:SS]` prefixes.
Those prefixes are second-granularity with no end times, and model-reported audio timestamps drift.
A boundary that drifts late shaves the first consonant off the sentence after it, which is the one
artefact a viewer notices immediately.

So `PcmDecoder` decodes the same M4A the transcriber already produces and reports RMS per 20 ms
window, and `SilenceDetector` thresholds it. That is exact, offline, free, adds no dependency, and
works on music and room tone where a speech model returns nothing at all.

### The four knobs

They are sliders in the sheet rather than constants, because no value is right twice — a phone mic
in a quiet room floors near -55 dBFS and the same phone on a street floors near -30, and a fixed
-40 either trims nothing or eats speech depending on which video it meets.

| Knob | Default | What it protects |
| --- | --- | --- |
| Threshold | -40 dBFS | The noise floor of the room the video was shot in |
| Shortest cut | 350 ms | Breaths between sentences — removing those is what sounds robotic |
| Breathing room | 80 ms | The attack of the first word after each cut |
| (internal) minimum keep | 150 ms | Fragments too short to be a word, which would land as clicks |

`minSilenceMs` is tested against the *padded* span, so an interior gap has to run 350 + 2 x 80 ms
before it qualifies and the number means what it says: no cut ever removes less than it. A run
touching the very start or end of the file is not padded on that side, since there is no speech
there to protect — pad it and an 80 ms sliver of silence survives at the head or tail.

`SilenceDetector` has no Android imports on purpose, so all of this is covered by a JVM unit test
rather than needing a device.

### Rebuilding the video

`ClipExporter.exportCut` gives every kept span its own `EditedMediaItem` with a
`ClippingConfiguration` over the same source URI, and lets the sequence concatenate them — the same
machinery `merge` uses for the clip list, pointed at one file. Cuts are frame-accurate because the
`Presentation` effect forces a re-encode anyway; a transmux could only start on a key frame.

### Captions

Opt-in, because it is the only part of this service that leaves the device. **Add** transcribes the
audio, the sheet reports how many lines came back, and **Save cut** burns them in.

The transcript is asked for as structured `{start, end, text}` segments through a `responseSchema`
rather than as `[MM:SS]` prose. The prose form gave whole-second starts and no ends at all, which
is enough to read a transcript and not enough to place a caption.

Two things are easy to get wrong here, and both are covered by unit tests:

- **The timings have to be remapped.** Every removed gap pulls everything after it earlier, so a
  caption's position in the export is its position in the source minus all the silence removed
  before it. Skip the remap and the drift grows with every cut, so the last line of a heavily cut
  video lands seconds after it was said. `Segment.remap` does this at save, against the spans
  actually being exported rather than whatever the sliders said earlier.
- **The overlay belongs on the composition, not on the items.** A cut export is many
  `EditedMediaItem`s over one source, and a per-item presentation time restarts at every join, so
  every caption after the first cut would be placed against the wrong clock.
  `Composition.Builder.setEffects` sees the output timeline, which is the timeline the remap
  produces.

`CanvasOverlay` rather than the more obvious `TextOverlay`: `TextOverlay` measures the text at its
natural width with nowhere to say how wide the frame is, so anything longer than a few words runs
off both edges. Drawing it by hand means a `StaticLayout` bounded to the frame, which wraps.

### Known ceilings

- The span count is capped at 120, and past that only the longest gaps are cut. Every kept span is
  an `EditedMediaItem`, so an unbounded count is an unbounded Transformer sequence.
- Detection is amplitude only. Loud room tone, traffic or a fan reads as speech, and the fix is the
  threshold slider rather than anything smarter.
- Decoding is one pass over the audio, so a long video waits on the decode before the sheet opens.
  The readings are then held in memory (~120 KB for ten minutes), which is what keeps the sliders
  instant afterwards.
- Caption styling is fixed: white bold on a black band, wrapped, near the bottom. No font, colour,
  position or karaoke options.
- A caption whose words were entirely cut away is dropped, as is one with under 120 ms left after
  the cut. The detector works on amplitude and the model works on language, so they disagree at
  the edges.

## Contributing

Pull requests are welcome. [CONTRIBUTING.md](CONTRIBUTING.md) covers the setup, the two rules that
shape most of the codebase (no FFmpeg, offline unless it cannot be), and what a good pull request
looks like here.

## Build and run

Requires the Android SDK with platform 37.1 installed. `local.properties` points at the SDK.

```bash
./gradlew assembleDebug          # build
./gradlew installDebug           # build + install on a connected device
adb shell am start -n com.eirmon.cutly.debug/com.eirmon.cutly.MainActivity
adb logcat -s CameraX:* Transformer:* AndroidRuntime:E
```

The debug build uses the applicationId `com.eirmon.cutly.debug` so it can sit alongside a release
install.

Use a physical device. Emulator camera feeds are synthetic and make recording behaviour
untrustworthy.

## Not built yet

- Playback / review screen before export (Media3 ExoPlayer over the clip list)
- Clip thumbnails in the progress bar
- Speed control, timer, filters
- Tap-and-hold to record instead of tap-to-toggle
- Captions on the camera take, rather than only on a picked video
- Caption styling, and a preview of them before the export

## Licence

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

Cutly bundles the TikTok Sans typeface, which is licensed separately under the SIL Open Font
License 1.1. That licence is reproduced in full in
[THIRD_PARTY_LICENSES.txt](THIRD_PARTY_LICENSES.txt).

Cutly is not affiliated with, endorsed by, or connected to TikTok, ByteDance, CapCut, Meta or
Google. Product names are used only to describe interoperability and prior art.
