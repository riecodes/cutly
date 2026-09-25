# Cutly

An Android video tool for short-form creators: shoot a take in clips, read it back as text, and cut
the dead air out of it. Kotlin and Compose, no FFmpeg, no account, and nothing leaves the phone
except the audio you explicitly ask to have transcribed.

[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

A projects grid with two ways in and one editor:

- **Camera** — a TikTok-style segmented video camera. Record a take as a series of clips with no
  length cap, pause between them, discard the last clip, jump between zoom stops, double-tap to
  flip lenses, then save the clips to the gallery or open the merged take in the editor.
- **Import** — pick any video on the phone; it becomes a project.
- **Editor** — a clip timeline with the dead air detected and removed, hand trimming, a
  transcript (on device, offline Whisper, or your own cloud key) and captions burned into the
  export. Every project keeps its source, cut, and transcript until you delete it.

## Stack

| Concern | Choice |
| --- | --- |
| Language / UI | Kotlin, Jetpack Compose (Material 3) |
| Navigation | one `enum` and a `BackHandler` — two destinations is not a graph |
| Capture | CameraX (`camera-video`, `camera-compose`) |
| Export / concat | Media3 Transformer + Presentation effect |
| Delivery | MediaStore, `Movies/Cutly` |
| Transcription | Android `SpeechRecognizer`, sherpa-onnx Whisper, or Gemini, audio only |
| Cloud transcript / captions | Groq `whisper-large-v3`, OpenAI `whisper-1` or Gemini, audio only |
| Silence detection | `MediaExtractor` + `MediaCodec`, RMS per 20 ms window |
| Captions | Media3 `CanvasOverlay` on the composition |
| Build | AGP 9 (built-in Kotlin), Gradle 9.7 |

Media3 Transformer replaces FFmpeg here: it drives the device's hardware codecs, adds no native
binaries to the APK, and can transmux without re-encoding when clip formats already match.

## The shell

`MainActivity` opens on `ProjectsScreen`: a grid of projects, with Camera and Import on a bottom
bar and Settings behind a gear. Five destinations (projects, camera, editor, settings, licences)
and one string argument are a `sealed interface Screen` and a list in `rememberSaveable`; a
navigation library would add a route DSL for the same five `when` branches. Every surface is dark.

Projects live in `filesDir/projects/<id>/` as a copied `source.mp4`, a `project.json` with the
cut, settings and transcript, a `thumb.jpg` and a cached `levels.bin` of loudness readings.
`ProjectStore` is the whole database: a directory per project and a JSON file per directory,
written next to the old one and renamed over it. A merged camera take is moved, not copied, into
its project, since cache and files share a volume.

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
- **No length cap** — a take runs until it is stopped or the volume fills up.
  `FileOutputOptions.setDurationLimitMillis` is only set when the countdown sheet's optional
  per-clip cap is on. Free space is checked before every clip and every two seconds while one is
  running, and the clip is finalized with a margin left rather than dying inside the muxer.
- **Zoom stops** — the pill above the record button offers the round ratios the *bound lens* can
  actually reach, read off `ZoomState`, plus its own minimum when it goes wider than 1x. Between
  stops — mid-pinch, or after a slide on the record button — the active stop shows the live ratio.
- **Aspect ratio** — 9:16, 4:5, 1:1 or 16:9 from the rail. One CameraX `ViewPort` on the
  `SessionConfig` crops the preview and the recording alike, and the viewfinder is letterboxed to
  the same shape, so what is shown is what is saved. Locked once the take has a clip, since a
  merge cannot reconcile two frame shapes.
- **Process death** — the clip list is written to `cache/clips/session.idx` after every clip, and
  restored on launch. Android kills backgrounded camera apps aggressively.
- **Rotation** — the activity is locked to portrait, so an `OrientationEventListener` feeds
  `videoCapture.targetRotation` manually. Without it, clips shot sideways save sideways.
- **Mixed lenses** — merge chooses the tallest captured clip and adds a `Presentation` effect only
  to clips that need scaling, so front/back resolution differences do not break the sequence.

## Transcription

**Settings** chooses the engine: Android's own on-device recogniser, an optional multilingual
Whisper tiny INT8 model run through sherpa-onnx, or the cloud with your own key. The two local
options stay offline. The Whisper model is a roughly 99 MB opt-in download, resumes through
Android's DownloadManager after process death, is SHA-256 verified before use, and can be deleted
from the same screen. It is not bundled in the APK.

The editor's **Transcript** and **Captions** tools use whichever engine is chosen. With the cloud
engine, every upload first shows a consent dialog naming the provider. Groq is used when its key
exists, then OpenAI; both return native Whisper segment timestamps through the same request.
Otherwise Gemini's structured-timing path is used. All paths strip the video track into a temporary M4A and delete it when the attempt
finishes.

The speed effect is deliberately not applied to the transcription audio — a 3x take is
unintelligible to a speech model, and the transcript is of what was said.

### Setup

Cloud transcription is optional and bring-your-own-key. Paste a Groq, OpenAI or Gemini key in
**Settings** inside the app; it is stored in app-private storage, excluded from backup, and used
only for the audio uploads you confirm. Groq takes precedence (free tier, no billing), then OpenAI, then
Gemini. Groq and OpenAI responses carry native Whisper segment timings.

For development, debug builds also read the same keys from `local.properties` (untracked), so the
Settings screen does not have to be visited on every reinstall:

```properties
groq.api.key=<free key from https://console.groq.com/keys>
openai.api.key=<OpenAI API key>
gemini.api.key=<key from https://aistudio.google.com/apikey>
```

Release builds ignore `local.properties` and compile empty `BuildConfig` fields, so no build that
leaves this machine can carry a key.

### Releasing

Tag `vX.Y.Z` on `main` and the `Release` workflow builds, signs and uploads the APK to the GitHub
release page with a SHA-256 file. Signing needs four repository secrets: `KEYSTORE_BASE64`,
`KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Locally, a `keystore.properties` with
`storeFile`, `storePassword`, `keyAlias` and `keyPassword` does the same. `versionCode` is the
commit count, `versionName` comes from the tag. The install page and privacy policy live in
`docs/` and are served by GitHub Pages.

### Known ceilings

- Tagalog word error rate runs well above English on every current speech model, and proper nouns
  come back wrong often. That is why the transcript sheet is an editable text field, not a label.
- Gemini requests are inline base64 and capped at 20 MB total. The OpenAI path streams multipart
  audio from disk instead of buffering the extracted take in memory.
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
machinery `merge` uses for the clip list, pointed at one file. Cuts stay in Composition's default
transcode mode for frame accuracy; a transmux could only start on a key frame. Source resolution
and bitrate are retained, and identity `Presentation` scaling is skipped.

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
