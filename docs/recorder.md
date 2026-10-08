# Tryptify Recorder (`:recorder`)

A screen recorder and video editor — `applicationId tf.monotrypt.recorder`,
label "Tryptify Recorder". It grew out of the debug screen recorder in the
Tryptify music app (`DebugScreenRecorder`, a native-resolution capture of the
app's own audio for A/B tests) and was first built as a module of the Tryptify
repository; this repository is its standalone home.

## Build and test

```
./gradlew :recorder:testDebugUnitTest    # 128 JVM tests, no device
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

**File details** — read back from the file itself, not from the settings:
container, duration, size and overall bitrate; video codec with H.264 profile
and level, resolution, frame rate, video bitrate and frame count; audio codec
(AAC object type), sample rate, channels and audio bitrate. *Details* in the
library and for editor clips, with *Copy* for the whole sheet; a one-line
summary under "Last recording" and in "Export finished". Bitrates are measured
from the samples in the file. A screen recording has a variable frame rate —
the screen only sends frames while something changes — so the frame rate shows
the rate while moving (`FrameTiming`) beside the file's average.

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
- **GIF export**: the edited picture as a looping animated GIF in
  `Pictures/TryptifyRecorder` — 320p, 480p or 640p on the short side (never
  above the source's), 10, 15, 20 or 25 fps. Every edit is in it: cuts, speed,
  canvas, look, titles, fades.
- Music and other audio files on lanes; overlapping pieces mix.
- Titles: text, position, size, colour, background box, start/end on the
  timeline.
- **Crop by touch**: while the Crop tool is open the preview shows the whole
  picture with a box over it — drag a corner or edge to resize, the inside to
  move. Any size down to 5 % of either side and any shape, or held to
  original, 1:1, 4:5, 9:16, 16:9, 4:3 or 3:4. With the canvas shape on
  *Original* the export takes the crop's own shape.
- Canvas: shape (original, 16:9, 9:16, 1:1, 4:5, 4:3), fit or fill, rotate,
  flip.
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
                     gif/ FrameSampler, YuvFrame, GifPalette, GifEncoder
                          (pure) ← GifTranscoder (MediaCodec)
             ui/     EditorViewModel, EditorScreen, TimelineView, ToolPanels
             store/  ProjectStore (drafts)
  media/     MediaStoreFile, MediaProbe, Thumbnails, RecordingsRepository
  settings/  RecorderSettings, SettingsStore
  ui/        Record, Library and Settings screens, theme
```

The rule that keeps it testable without a phone: decisions live in pure Kotlin
(`CapturePlanner`, `PauseClock`, `Pcm`, `Timeline`, `History`,
`ExportPlanner`, `CropBox`, and for GIFs `FrameSampler`, `YuvFrame`,
`GifPalette`, `GifEncoder`), and the Android/Media3 layers only carry them out.

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
- **320 kbps is chosen, not just asked for.** Creating "the" AAC encoder by
  type takes whichever the phone lists first, often a vendor hardware encoder,
  and some of those top out below 320 kbps — asking for 320 then quietly gives
  less. `AacPicker` takes only an encoder that accepts 48 kHz stereo *and* whose
  range includes 320 kbps, Android's own FDK-AAC encoder first, and sets
  constant bitrate where the encoder declares it. Exports use the same rule
  through `AacEncoderFactory`, since Media3's factory has no way to choose the
  audio encoder. The "Recording saved" notification states the bitrate the
  encoder was configured with, so a phone that genuinely cannot reach 320 says
  so instead of hiding it.
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
- **The crop is stored in the source's orientation, dragged in the
  picture's.** `CropInsets` are cut from the source before it is turned or
  mirrored, because that is where Media3's Crop runs. The box the user drags
  is over the picture as it comes out, so `CropBox` maps between the two:
  mirror first, then turn — the order Media3's ScaleAndRotateTransformation
  uses (post-scale, then post-rotate). While the tool is open the preview is
  planned without the crop and in the picture's own shape, so the box sits
  exactly over the frame and dragging it never rebuilds the player. Drags are
  measured from where they began, so a handle held at the picture's edge
  stays under the finger when it comes back; each drag is one undo step.
- **Time-based effects** (fades, titles) use composition time. Both
  `CompositionPlayer` and `Transformer` hand per-item effects composition-time
  timestamps (checked in the 1.10.1 sources: `VideoEncoderGraphInput` adds each
  item's offset), so a title lands at the same moment in preview and export.
- **A GIF is made in two steps.** Transformer renders the project as a silent
  MP4 at the GIF's size and high quality — the same Composition as every
  other export, so every edit is in it — and `GifTranscoder` decodes that
  with MediaCodec and writes the GIF in Kotlin (`GifEncoder`, no library).
  Three things in the decoded video are not what they seem: Transformer
  encodes a portrait picture lying on its side with a rotation flag, which
  a GIF cannot carry, so frames are turned upright while converting; the
  decoder's crop may include padding, so the track's size wins; and colours
  are BT.709 limited range unless the file says otherwise.
- **GIF frames follow the video's own timing.** A GIF has a fixed frame rate,
  a screen recording does not. GIF frame k shows the latest video frame at or
  before k / fps — never the nearest, which would show a change early — and
  frames are held until the next one's time is known, so only the ones that
  are shown get converted (`FrameSampler`). Delays are rounded on the running
  total, so 15 fps comes out 7, 7, 6 hundredths and the GIF lasts exactly as
  long as the video. Media3's `FrameDropEffect` would have saved rendering
  work, but it drops each clip's last frame — on a still screen that frame
  can be on screen for seconds.
- **One palette for the whole GIF**, from a first decoding pass over up to 120
  frames: median cut over a 32-level-per-channel histogram that also keeps
  each cell's exact mean, so a flat colour (an app bar, a dark background)
  comes back exact. Per-frame palettes would flicker.
- **Dither only where needed, and by position.** A pixel within a few levels
  of a palette colour takes it plainly, so flat UI stays clean; the rest get
  an 8×8 Bayer dither. Ordered rather than error diffusion because its
  pattern depends only on the pixel's position: an unchanged area comes out
  the same every frame, so it neither shimmers nor costs bytes.
- **Each GIF frame sends only what changed**: pixels that would come out as
  the colour already shown are transparent, the frame is cut to the box
  around the rest, and a frame with no change just lengthens the one before.
  Only an exact match counts: letting near matches stand saved 12 % on a test
  clip, but drew contours across a slowly changing gradient. The encoder was
  checked against Pillow, ImageMagick and ffmpeg, which decode its files
  identically.
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
- [ ] The saved notification reads "320 kbps stereo audio", and a media-info
      app agrees about the file (device audio: a game or music playing).
- [ ] Library → Details on a fresh recording: H.264 profile/level, 165 fps
      while moving, ~320 kbps AAC-LC stereo — and the same figures as a
      media-info app.
- [ ] On a 165 Hz phone, a game recorded at 165 fps: check the file's frame
      rate in a media-info app, and logcat `EncoderFit` for any step-down.
- [ ] Floating button: drag, snap, tap to record, pause/stop, long-press menu,
      "hide while recording"; survives leaving the app.
- [ ] Quick Settings tile start/stop; notification actions.
- [ ] Android 14+ consent dialog, including "share one app".
- [ ] Editor preview plays, seeks and updates for every tool; export in all
      three modes; detached audio lines up after export.
- [ ] Titles and fades appear at the same times in preview and export.
- [ ] Crop tool: the box sits exactly over the picture; corners, edges and
      the inside each grab and follow the finger; a held shape stays held;
      the export matches the box — also after Rotate and Flip.
- [ ] GIF export of a portrait recording comes out upright, in
      `Pictures/TryptifyRecorder`, loops, and plays at the chosen rate in a
      browser and a chat app; cancel mid-way leaves no file; a still stretch
      stays a single long frame.
- [ ] Drafts reopen after the app is killed (persisted document permissions).

Known limits: exports run in-app (the screen is kept on while one runs) rather
than in a background service; preview of a very long project at high zoom is
capped to keep the timeline under Compose's layout size limit; "show touches"
cannot be enabled by a regular app.
