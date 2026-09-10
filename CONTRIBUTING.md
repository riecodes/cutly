# Contributing to Cutly

Thanks for looking. This is a small, opinionated app, and the fastest way to get a change merged is
to know what it is opinionated about before you write any code.

## What Cutly is

A camera, a transcriber and a silence cutter for short-form video, on one phone, with as little
between the user and the file as possible.

Two rules shape most of the codebase:

1. **No FFmpeg.** Media3 Transformer drives the device's hardware codecs. That keeps the APK free
   of native binaries, keeps the export fast, and keeps the project clear of the GPL that an
   x264-enabled FFmpeg build would pull in. `ffmpeg-kit` is also retired, with its binaries removed
   from Maven Central in April 2025, so this is the durable choice and not only the smaller one. A
   pull request that adds FFmpeg needs to first show a format Media3 genuinely cannot handle.
2. **Offline unless it cannot be.** Only transcription goes online, and only when the user asks for
   it. Silence detection is amplitude arithmetic on the device for exactly this reason. If your
   feature can be done locally, do it locally.

## Getting set up

You need Android Studio with **SDK platform 37.1** installed. The JDK bundled with Android Studio
is fine; there is no separate one to install.

```bash
git clone https://github.com/<your-fork>/cutly.git
cd cutly
```

Open the project in Android Studio once and let it write `local.properties` with your `sdk.dir`.
That file is gitignored and must stay that way.

Phone and downloaded-Whisper transcription need no key. The optional Gemini video-to-text choice,
camera transcription, and captions use a configured cloud key; camera transcription and captions
can also use OpenAI.

```properties
# local.properties  (gitignored, never commit this)
gemini.api.key=<key from https://aistudio.google.com/apikey>
openai.api.key=<OpenAI API key>
```

A missing key is not a build failure; the app says so when you use a feature that needs it. The key
is compiled into the APK, so **a debug build with your key in it must not be shared.**

```bash
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleDebug        # build
./gradlew installDebug         # build and install
./gradlew devRun               # install and relaunch, for a terminal-only loop
adb logcat -s Cutly:* CameraX:* Transformer:* AndroidRuntime:E
```

Use a physical device for anything touching the camera. Emulator camera feeds are synthetic and
make recording behaviour untrustworthy. The cut and transcribe services take a video from the photo
picker and never open the camera, so an emulator is fine for those.

## Making a change

1. Fork the repository and branch from `main`. Name the branch after the change rather than the
   issue number: `silence-detector-stereo`, not `fix-12`.
2. Make the change.
3. Run `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`. Both have to pass.
4. Actually run it on a device and confirm the thing you changed does what you say it does. This is
   a media app; a green build proves very little.
5. Open a pull request against `main`.

### What a good pull request looks like here

- **One change.** A refactor bundled with a feature is two reviews wearing one coat.
- **Says what it does and what it does not do.** If you left an edge case, name it. That makes a
  pull request easier to accept, not harder.
- **Says how you tested it.** Device and Android version, or the fixture you used. "Cut a 3 minute
  clip on a Pixel 6a, Android 15, captions stayed in sync" is worth more than a paragraph of prose.
- **A frame grab for anything visual.** For an export change, pull a frame out of the output file
  rather than screenshotting the editor.

## Code style

Kotlin official style, which Android Studio applies by default. Beyond that, the things a reviewer
will actually raise:

- **Comments say why, not what.** The code already says what. Nearly every comment in this repo
  explains a decision, a constraint or a trap. If a comment would only restate the line under it,
  delete it.
- **Keep logic testable off-device.** `SilenceDetector` and `Segment` have no Android imports on
  purpose, so their tests run on the JVM in seconds. When you add logic, ask whether the arithmetic
  can be separated from the Android API feeding it. Usually it can.
- **Non-trivial logic ships with a test.** Not a suite: one test that fails if the logic breaks.
  `SilenceDetectorTest` and `SegmentTest` show the expected level of detail, where each test names
  the artefact a user would notice if that case regressed.
- **Tuning values are sliders, not constants.** There is no silence threshold that is right twice.
  If your feature has a number that depends on the room, the phone or the footage, expose it.
- **Prefer the platform.** `MediaExtractor` and `MediaCodec` over a decoding library, `StaticLayout`
  over a text-rendering dependency. A new Gradle dependency needs a reason in the description.

## Reporting a bug

Open an issue with:

- Device and Android version
- What you did, what you expected, what happened
- The relevant `adb logcat` lines, if the app failed rather than misbehaved

For an export failure, `adb logcat -s Cutly:*` carries the real cause. Media3's own
`ExportException` message is only an error-code name, which is why the exporter logs the underlying
exception separately.

## Licence

By contributing you agree that your contribution is licensed under the Apache License 2.0, the same
licence as the project. See [LICENSE](LICENSE).
