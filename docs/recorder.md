# Tryptify Recorder (`:recorder`)

A screen recorder and video editor — `applicationId tf.monotrypt.recorder`,
label "Tryptify Recorder". It grew out of the debug screen recorder in the
Tryptify music app (`DebugScreenRecorder`, a native-resolution capture of the
app's own audio for A/B tests) and was first built as a module of the Tryptify
repository; this repository is its standalone home.

## Build and test

```
./gradlew :recorder:testDebugUnitTest    # 66 JVM tests, no device
./gradlew :recorder:lintDebug            # clean: 0 errors, 0 warnings
./gradlew :recorder:assembleDebug        # signed with the committed recorder/debug.keystore
./gradlew :recorder:assembleRelease      # R8-minified, ~4 MB
```

No native code and no submodules: it assembles from a bare clone with the
Android SDK installed.

### Release signing

The recorder has **its own release key**, separate from the Tryptify music app's:
`tryptify-recorder-release.jks` (PKCS12, alias `tryptify-recorder`, RSA 4096,
valid until 2054). It is never committed; the owner keeps it with its password
note. Its certificate SHA-256 is

```
80:11:86:31:D0:FF:C4:2B:2B:F1:FF:3C:BE:EC:E5:98:D0:37:1F:E1:2F:1F:C9:3B:C2:2E:E3:72:34:EB:3D:2B
```

To sign a release, copy the `.jks` outside the repository and write
`recorder/keystore.properties` (gitignored — `keystore.properties.example`
beside it shows the fields). It lives in the module rather than the repository
root so the module can never pick up another app's key: Android will not
install an update signed with a different key than the installed build.
Without the file the release APK comes out unsigned. Check the result before
handing it over:

```
$ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs \
    recorder/build/outputs/apk/release/recorder-release.apk
```

The signer's SHA-256 must start `80:11:86:31`.

## What it does

**Recording**
- Screen capture at a chosen resolution (native, 1440p … 480p), frame rate
  (24–165, default 165, capped by the panel's *fastest* refresh rate) and
  quality, oriented the way the phone is held
  when recording starts. The device's encoder is asked what it supports, and the
  recorder steps down rather than failing (`EncoderFit`).
- Audio: 48 kHz **stereo AAC at 320 kbps** — none, device audio (other apps'
  playback, captured digitally — media, game and unknown usages), microphone
  (stereo where the phone has the microphones for it, mono otherwise), or both
  mixed into one stereo track.
- Pause and resume, with the gap cut out of the file (`PauseClock`).
- Countdown (off, 3, 5 or 10 s).
- Started and stopped from the app, the **floating record button** (an overlay
  over every app: tap to record, tap for pause/stop, long-press for "open app" /
  "hide", drag to move), the **Quick Settings tile**, or the notification.
- Optional: take the floating button off screen while recording, so it is not in
  the video.
- Saved to `Movies/TryptifyRecorder` through MediaStore (pending until finished,
  so galleries never show half a file). A "saved" notification offers Edit and
  Share.

**Editing** — preview with Media3 `CompositionPlayer`, export with Media3
`Transformer`, both fed the same `Composition`, so the preview is the export.
- Timeline with the playhead fixed in the middle; scroll to seek, zoom in/out.
- Clips: add (any video), split at playhead, trim (range slider or "start/end
  at playhead"), speed 0.25×–4× (pitch kept), volume 0–200 %, mute, duplicate,
  reorder, delete.
- **Separate video and sound**: *Detach audio* turns a clip's sound into its
  own piece on an audio lane, lined up where it was. It can then be moved,
  trimmed, split, faded, turned up or down, or deleted independently of the
  picture; *Restore audio* puts it back. Export as *Video, no sound* (silent
  MP4) or *Sound only* (M4A in `Music/TryptifyRecorder`); the library also has a
  one-tap *Extract audio*.
- Music and other audio files on lanes; overlapping pieces mix.
- Titles: text, position, size, colour, background box, start/end on the
  timeline.
- Canvas: aspect (original, 16:9, 9:16, 1:1, 4:5, 4:3), fit or fill, rotate,
  flip, crop.
- Look: filters (mono, sepia, vivid, cool, warm, invert), brightness, contrast,
  saturation, fade in from / out to black.
- Undo/redo (a slider drag is one step), drafts autosaved as JSON.
- Export resolution (original, 1080p, 720p, 480p) and quality.

## How it is put together

```
recorder/src/main/java/tf/monochrome/recorder/
  capture/   ScreenRecorder (the engine), RecorderService (foreground service,
             owns capture + floating button), FloatingButton(+Content),
             CaptureConsentActivity, RecordTileService, RecorderState,
             CapturePlanner / EncoderFit / PauseClock / Pcm (pure helpers)
  editor/    model/  Project, Timeline ops, History      — pure, JVM-tested
             export/ ExportPlanner (pure) → CompositionFactory (Media3),
                     Effects, ExportManager
             ui/     EditorViewModel, EditorScreen, TimelineView, ToolPanels
             store/  ProjectStore (drafts)
  media/     MediaStoreFile, MediaProbe, Thumbnails, RecordingsRepository
  settings/  RecorderSettings, SettingsStore
  ui/        Record, Library and Settings screens, theme
```

The rule that keeps it testable without a phone: decisions live in pure Kotlin
(`CapturePlanner`, `PauseClock`, `Pcm`, `Timeline`, `History`,
`ExportPlanner`), and the Android/Media3 layers only carry them out.

### Why things are the way they are

- **One service, two foreground types.** While only the floating button is up
  the service is `specialUse`; consent adds `mediaProjection`, which Android 10+
  requires *before* `getMediaProjection()`. The type is never dropped while a
  capture is running. Consent is asked for every recording: on Android 14+ a
  token is good for one projection.
- **165 fps, and what decides whether a file gets it.** The cap is the panel's
  fastest mode at its current resolution, not its current mode: adaptive
  120/144/165 Hz panels idle at 60 Hz whenever nothing moves (the consent dialog
  included), and capping by that would turn a 165 setting into 60. The encoder
  is asked for real-time priority and a 165 operating rate (retried without
  them if it refuses). Whether a size/rate is supported is read from the
  encoder's size/rate table *or* its declared performance points — the table
  under-reports on encoders that do 240 fps slow motion. If it is not supported,
  resolution gives way first, down to 720p, and only then the rate (144, 120,
  90, 60, 30). And the screen only sends frames while something changes, so a
  165 fps recording of a still screen has very few: the file is variable frame
  rate, which every player handles.
- **Export bitrates follow the source's frame rate.** `MediaProbe` reads the
  first 600 frame timestamps and takes the typical gap between frames
  (`FrameTiming`) — the average would understate a variable-rate recording that
  sat still for a while. A 165 fps clip exports with 165/60 of the bits a 60 fps
  one gets. Exported audio is 320 kbps too; Media3's default is 128.
- **Muxer gate.** The debug recorder blocked a drain thread up to a second per
  sample waiting for the other track's format. Samples now queue until the muxer
  can start, and if audio never produces a format the file is written without it
  rather than not at all.
- **Pausing never drops video frames** (a P-frame needs its predecessor); a frame
  already in flight is clamped to the pause's start. No
  `KEY_REPEAT_PREVIOUS_FRAME_AFTER`: a repeating encoder would invent frames
  inside the pause.
- **Mic + device audio** run off different clocks. The mic is the master; device
  audio goes through a bounded FIFO that pads underruns with silence and drops
  the oldest samples on overrun, so latency cannot creep.
- **Volume above 100 %** uses `VolumeEnvelope`, not Media3's `GainProcessor`,
  which is specified for [0, 1] and wraps 16-bit samples above that.
- **Time-based effects** (fades, titles) use composition time. Both
  `CompositionPlayer` and `Transformer` hand per-item effects composition-time
  timestamps (checked in the 1.10.1 sources: `VideoEncoderGraphInput` adds each
  item's offset), so a title lands at the same moment in preview and export.
- **Media3 1.10.1** (catalog key `media3Editor`): the editor needs track-typed
  sequences, gaps and `CompositionPlayer`, none of which older releases have.
  Not 1.11.x: it pulls kotlin-stdlib 2.2.10, newer than this build's Kotlin
  2.1.0 can read. `CompositionPlayer` is `@ExperimentalApi`.

## Not verified on a device yet

Everything above compiles, lints clean and passes its JVM tests, and the release
build shrinks without missing rules — but it was written in a container with no
emulator, so none of it has run on a phone. Before a release, check at least:

- [ ] Record with each audio source; play back; A/V sync after a long recording
      and across pause/resume. Mic recordings are really stereo on a phone
      with two microphones.
- [ ] On a 165 Hz phone, a game recorded at 165 fps: check the file's frame
      rate in a media-info app, and logcat `EncoderFit` for any step-down.
- [ ] Floating button: drag, snap, tap to record, pause/stop, long-press menu,
      "hide while recording"; survives leaving the app.
- [ ] Quick Settings tile start/stop; notification actions.
- [ ] Android 14+ consent dialog, including "share one app".
- [ ] Editor preview plays, seeks and updates for every tool; export in all
      three modes; detached audio lines up after export.
- [ ] Titles and fades appear at the same times in preview and export.
- [ ] Drafts reopen after the app is killed (persisted document permissions).

Known limits: exports run in-app (the screen is kept on while one runs) rather
than in a background service; preview of a very long project at high zoom is
capped to keep the timeline under Compose's layout size limit; "show touches"
cannot be enabled by a regular app.
