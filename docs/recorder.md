# Tryptify Recorder (`:recorder`)

A screen recorder and video editor — `applicationId tf.monotrypt.recorder`,
label "Tryptify Recorder". It grew out of the debug screen recorder in the
Tryptify music app (`DebugScreenRecorder`, a native-resolution capture of the
app's own audio for A/B tests) and was first built as a module of the Tryptify
repository; this repository is its standalone home.

## Build and test

```
./gradlew :recorder:testDebugUnitTest    # 239 JVM tests, no device
./gradlew :recorder:lintDebug            # clean: 0 errors, 0 warnings
./gradlew :recorder:assembleDebug        # signed with the committed recorder/debug.keystore
./gradlew :recorder:assembleRelease      # R8-minified, ~7.5 MB
```

The app is in English only, and the build keeps only English resources
(`androidResources.localeFilters`): AndroidX libraries carry their strings in
89 locales, which made up 280 KB of `resources.arsc`.

The audio mixer builds with Android NDK 28.2.13676358 and CMake 3.22.1.
The pinned DSP source is included, so a checkout needs no submodule setup.

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
  Share. A recording stops and saves itself when free storage falls below
  200 MB ("Storage almost full"), and does not start below that.

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
- Music and other audio files on lanes. Assign clips to Dialogue, Device,
  Music or SFX, then open **Mixer** to set faders, pan, mute, solo and FX.
  Each role and Master has a reorderable chain with per-effect parameters,
  bypass and wet mix. Projects save these controls with their undo history.
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
- **Film grain**: presets (Fine, 35 mm, 16 mm, Super 8, Mono), then amount;
  character (size, sharpness, texture); tone (how much shows in shadows,
  midtones and highlights); colour (monochrome to per-channel grain, and
  red, green and blue strength); motion (every frame, 24 or 12 fps, still);
  and *Show grain only* to judge it on grey in the preview. The same in
  preview and export.
- Undo/redo (a slider drag is one step), drafts autosaved as JSON.
- Export resolution (original, 1080p, 720p, 480p) and quality.
- **Export frame rate**: *Source* keeps every recorded frame at its own
  time; or 24, 25, 29.97, 30, 60, 120 or 165 frames a second, exactly. With
  *Optical flow* on, the frames a higher rate or a slowed clip needs are made
  from the motion between the recorded ones; off, frames are repeated or
  dropped. Video exports only — a GIF has its own rate. (The Markers tool's
  *Timeline frame rate* is only the step of the frame-by-frame buttons and
  the rate written into a project hand-off; it does not change an export.)

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
(`CapturePlanner`, `PauseClock`, `Pcm`, `AudioTimeline`, `SessionKeys`,
`ButtonVisibility`, `StorageGuard`, `CleanupRules`, `BundleValidation`,
`Timeline`, `History`, `ExportPlanner`, `FrameRetimer`, `CropBox`, `Grain`,
and for GIFs `FrameSampler`, `YuvFrame`, `GifPalette`, `GifEncoder`), and the
Android/Media3 layers only carry them out.

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
- **Audio is stamped by counting samples, and checked against the clock**
  (`AudioTimeline`). Counting is exact until samples go missing (the pump held
  up past the capture buffer) or the audio clock drifts from the monotonic one
  (tens of ppm: hundreds of ms over hours); either shifts all later sound. A
  block cannot be read before it was captured, so a stamp more than 30 ms
  after `now − block length` is impossible: frames are dropped from the block
  until it fits. A stamp more than 30 ms *before* it for every block of a
  window longer than the capture buffer means samples were lost: that much
  silence is encoded first. The window is what keeps a backlog read after a
  short stall (late, nothing lost) from counting. The stream stays
  continuous rather than jumping, since players that clock from the audio
  smooth over jumps. Capture buffers are half a second (were ~80 ms), falling
  back to the old size if a device refuses.
- **Setup and saving are off the main thread.** The service takes the
  projection and registers its callback on main, in that order (Android 14);
  encoder queries, the MediaStore row, codecs, AudioRecord and the
  VirtualDisplay are set up on IO, and a start that is cancelled meanwhile is
  torn down when it lands. Stopping (up to 3 s per worker join) and publishing
  run in `RecorderApp.appScope`, so a save started by `onDestroy` still
  finishes. A worker that outlives its join cannot write any more: the muxer
  gate is closed under its lock, and a failure after stop() is not reported.
- **The encoder whose limits were checked is the one used.** `EncoderFit`
  returns each size/rate with the codec name that supports it, and the
  recorder creates it by name; creating by type could pick another codec. If
  configure() still refuses, the next size is tried (up to six).
- **Edit-ready stems are written on their own thread**, from a bounded queue
  (~2.7 s). The pump only copies into it, so a slow disk never holds up the
  AAC encoder; if it overflows, that stem is dropped and the recording keeps
  its mixed track. A stem that reaches the WAV limit (4 GiB, ~6 h 13 min)
  ends there as a valid file, flagged `truncated`, and the editor then uses
  the mixed track. Session folders are named after the MediaStore row id
  (`capture-sessions/m<id>`): the recorder's `external_primary` URI and the
  library's `external` one name the same row. Older folders (a hash of the
  URI) are still found. Deleting a recording deletes its folder.
- **Leftovers are swept at start** (`StorageSweep`, off main): this app's own
  pending rows from a process that died mid-write (unplayable — MediaMuxer
  writes the index last) unless an export queued for a retry still writes
  into one, and session folders whose recording is gone. Nothing is deleted
  if a query fails.
- **The floating button changes the screen as little as it can**: the screen
  sends a frame only when something changes, so a pulse redrawn every vsync
  turned a still screen into a full-rate recording. The ring pulses only
  while recording, ten steps a second, read in the draw phase (no
  recomposition); the timer ticks on the second; hidden, nothing runs.
- **Volume above 100 %** uses `VolumeEnvelope`, not Media3's `GainProcessor`,
  which is specified for [0, 1] and wraps 16-bit samples above that.
- **A speed ramp is sixteen steady steps** between two keys (`ClipTiming`),
  so the audio sees a bounded number of speed changes. The first step plays
  at the first key's rate and the last at the second key's: a key on the
  trim's end starts nothing, and with evenly spaced steps a 0.5×→2× ramp
  to the end of a clip used to stop at 1.91×. The *Ramp up* / *Ramp down*
  buttons go from half the clip's speed to twice it, within 0.25×–4×.
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
- **Film grain is a shader of its own** (`FilmGrain`, a Media3 `GlEffect`),
  since Media3 has none. What makes it look like film rather than noise:
  luminance only by default, so no colour speckle; close to Gaussian (four
  hashes summed); strongest in the midtones and a fifth as strong at black
  and white (`Grain.response`); two layers, the finer one at half size, for
  clumps with texture. The detail controls (`GrainDetail`, `GrainPreset`)
  default to exactly that first grain — checked pixel for pixel against the
  1.4.0 shader on Mesa — so older drafts look the same. Tone is the same
  quarter-circle curve through three points (black, grey, white). Colour
  blends a field per channel into the shared one, scaled to keep its
  strength. **Sharpness** raises each noise value to a power below 1
  (keeping its sign, normalised by the exact E|n|^2p so strength holds):
  edges between light and dark grains steepen while grains stay round.
  Sharpening the grid's interpolation was tried first and turned coarse
  grains into squares. Motion seeds from the time rounded to 1/24 or 1/12 s,
  or from nothing. The texture and colour layers cost nothing at zero. The noise grid is read with smooth interpolation and rescaled
  to the same strength everywhere, because plain interpolation is weaker
  between grid points and shows as a mesh. The hash avoids `sin`, which loses
  precision on mobile GPUs. Grain is added to gamma-encoded values: that is
  what Media3 hands SDR effects (its builder starts at
  `WORKING_COLOR_SPACE_DEFAULT`, whatever the javadoc says); HDR frames come
  linear and are encoded around the grain. It sits after colour and before
  fades and titles, so a fade ends on clean black and titles stay crisp.
  Size is relative to the frame, so the 720p preview matches the export, and
  grains smaller than a pixel fade in proportion — a pixel averages them — so
  a small GIF is not grainier than its video. Each frame's grain is seeded
  from its composition time: fresh every frame, identical in preview and
  export. The shader source was run on Mesa (llvmpipe) to check its strength,
  midtone response, evenness and frame-to-frame independence.
- **Time-based effects** (fades, titles) use composition time. Both
  `CompositionPlayer` and `Transformer` hand per-item effects composition-time
  timestamps (checked in the 1.10.1 sources: `VideoEncoderGraphInput` adds each
  item's offset), so a title lands at the same moment in preview and export.
- **A chosen frame rate is a fixed grid, filled by rule.** `FrameRetimer`
  (pure, tested) lays the source's frames on a grid of k / rate seconds
  counted from the start of the timeline, so every clip shares one cadence
  and a cut does not disturb it. A grid time that is a source frame (to
  within an eighth of a frame, so rounding never turns 60-from-60 into sixty
  blends a second) is that frame, untouched. One between two source frames is
  made between them — unless they are more than 125 ms apart at normal speed,
  when the earlier is held: a screen recording has a frame only when the
  screen changed, so two frames a second apart are a still picture and then a
  change, not one slow movement to invent frames for. The limit stretches
  with a clip's slowest speed, because a 30 fps clip at quarter speed has
  frames 133 ms apart that are exactly the ones worth filling in.
- **The frames between are made by optical flow, on the GPU**
  (`FrameInterpolation`). Motion is measured on small greyscale copies, coarse
  to fine (a pyramid from 640 px on the long side down, a few rounds of
  Lucas–Kanade at each size), and *symmetrically*: for a point of the frame
  halfway between, where it was in the earlier frame and where it will be in
  the later. That puts the motion field on the frame being made, so it has
  no holes where something moved away. Motion is stored as two 16-bit
  numbers packed into an ordinary 8-bit texture; float render targets, which
  not every phone can draw to, are not needed. Where the two frames still
  disagree after being moved — measured against how much the picture varies
  there, since a difference that is nothing across an edge is everything on
  a plain wall — the motion is not believed and the nearer source frame is
  shown as it is; if they disagree nearly everywhere it is a cut and the
  whole frame is the nearer one. A repeated frame is a smaller fault than a
  smeared one. It is the only effect that gives out more frames than it
  takes in, so it implements Media3's `GlShaderProgram` directly: it copies
  each source frame (handing the upstream's texture straight back), draws as
  many output frames as it has free textures for, and asks for the next
  source frame only when the current pair is finished.
  It sits after a clip's own picture is graded and before grain, fades and
  titles, which are then drawn fresh on every frame instead of being pushed
  about with the motion; with picture-in-picture layers it runs once on the
  joined picture. The preview never runs it. The encoder is told the chosen
  rate (`AacEncoderFactory`), since Media3 would tell it the source's.
  The shader source was run in headless Chromium (WebGL 2, the same shading
  language as OpenGL ES 3) on synthetic pictures with known motion: pans,
  scrolls, a zoom and a moving object up to an eighth of the frame per frame
  are followed to about a tenth of a pixel and the made frame is 8–23 dB
  closer to the true one than a cross-fade; beyond that, and across a cut,
  the fallback takes over and the output is the nearer frame exactly. The
  doubt thresholds were set on those pictures, not on camera footage.
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

### Editor audio routing

The editor resamples decoded clips to 48 kHz. Each source carries its stereo
signal in the pair of channels reserved for its role. Media3 aligns the sources
and sums them in floating point. Native inserts process each summed role before
its fader and pan. The role outputs sum into Master, which has its own inserts,
pan and fader. Stereo peak meters read the actual post-fader samples. The mixer
hands the final stereo PCM to both preview and export.

Media3 1.10.1 gap items cannot carry audio processors, so routed audio uses a
seekable sparse PCM file for silence. The same stereo pair mapping works when
audio begins after a gap, when a video clip is muted and when a video overlay
generates silence during preview.

The native build links `tac_dsp` from `tryptify-audio-core` revision
`09b3956d699b41125fd213562459477f9d8b6ce9`: the snap-ins and their factory
(`snapin_factory.cpp`, split out of `dsp_engine.cpp` here) and nothing else.
The core's `DspEngine` (`TAC_BUILD_ENGINE`) and USB driver (`TAC_BUILD_USB`,
whose sources this copy does not carry) are off, so the core also configures
and tests on its own (`cmake -S recorder/src/main/cpp/tryptify-audio-core`).
`dsp/oxford/oxford_dsp.h` and `dsp/wsola*.h` are vendored but not compiled.
The upstream license is in
`recorder/src/main/cpp/tryptify-audio-core/LICENSE`. The JNI adapter calls the
upstream snap-in factory directly because the original player's engine feeds one
shared stereo input to every bus. Effects run within the project duration.
Preview seeks start each chain with fresh effect state.

Fixes made here to the vendored DSP (each reproduced on the host first, then
re-run after the fix; an AddressSanitizer + UBSan fuzz of all 36 snap-ins is
clean):

- **Resonator** fed its comb output back *plus* a low-passed copy of it, a
  loop gain of about 1.4 at the default settings: full-scale noise within a
  second, then NaN. The feedback is now a blend of the two, never above 1.
- **Shaper (Mirror) and Nonlinear Filter (fold)** folded with a loop that took
  |x|/2 steps per sample and never ended for infinite input — the audio
  thread hung and the export never finished. Now a closed-form fold.
- **Ladder Filter** with Sat off ran away to NaN at high cutoff with resonance
  up. The feedback goes through tanh, transparent at normal levels.
- **An effect that outputs NaN or infinity is reset** (`FxChain`), instead of
  staying silent until the chain is rebuilt.
- **Denormals are flushed to zero** on every render (`MixerGraph`): silent
  reverb and filter tails were measured 25–140× slower than the sound itself.
- **Pitch Shifter** dropped the sign of its read offset, so every upward shift
  came out lower; **Reverser** read the same sample for a whole segment;
  **Tape Stop** never slowed down; and the delay line's cubic read
  interpolated towards the wrong neighbour (zipper noise in Chorus, Flanger,
  Delay, Reverb and the combs). All four corrected and measured.
- **The JNI bridge checks every index and buffer size** from Kotlin, so a
  mistake there is ignored instead of corrupting memory.
- **Limiter** wrote a raised-cosine attack into a buffer and then applied the
  deeper of it and an envelope that had already jumped to the full
  reduction, so the gain fell in one sample (measured: a 0.50 gain step on a
  quiet-to-loud step). It now takes the smallest gain any sample in the
  lookahead window needs and smooths that with two moving averages that
  together span the window exactly: provably never over the threshold, and
  the gain comes down along an S-curve across the whole lookahead (largest
  step 0.004). Its window was found from a signed `int` sample count that
  went negative after 2³¹ samples (12 h at 48 kHz) and indexed before its
  buffer; positions are now an unsigned counter compared only by difference.
- **Wet/dry is latency-aligned.** Each effect reports its latency — Limiter,
  Compressor and Gate lookahead, Compactor attack, and the oversampling
  filters' phase delay (3 samples at 2×, 5 at 4×, at 48 kHz) — and `FxChain`
  delays that effect's dry signal by it before blending, from buffers sized
  when the effect is added. Before, a limiter at 50 % wet summed two copies 5
  ms apart (a comb with notches every 200 Hz), and any effect at 2×/4× and
  50 % lost up to 80 % of the level near 6 kHz. Not compensated: a bus with
  lookahead effects still runs that much behind the other buses (at most a
  few tens of milliseconds with every lookahead at its longest). Pitch
  Shifter's grains have no fixed delay to report.
- **Knobs that did nothing** — the Gate, Compactor and Transient Shaper "SC"
  (no sidechain input exists), Bitcrush "DAC" and Reverser "Sync" — are no
  longer shown. Their slots stay, hidden (`ParamDef.reserved`, native
  `RESERVED_…`), because projects save effect values by index: removing them
  would have moved Bitcrush's Dither and Mix and Reverser's X-Fade and Mix
  onto the wrong knobs in saved projects. `FxParametersNativeTest` reads the
  native headers and fails if a Kotlin list and its native enum disagree.
- **Trance Gate** ran at a fixed 120 BPM and its patterns filled 16 of the
  32 steps Length allows. It has a BPM knob (40–240, default 120; the last
  parameter, so older projects keep their indices and their old tempo), and
  each pattern's bar repeats over steps 16–31.
- **Flanger Stereo** was shown as 0–100 % but read as 0–360°. It is a
  percentage now, 100 % = 180°; presets written in degrees were converted
  (90° → 50 %). A project saved before plays its Stereo value as a
  percentage, so 50 % becomes 90° instead of 50°.
- **Stereo** panned with an equal-power law, 3 dB down on both sides at the
  centre, so adding it at its defaults made a track quieter. It is now a
  balance with unity at the centre, the same law as the mixer's pan.
- **Chorus** sized its delay lines for 80 ms + 64 samples; eight voices at
  40 ms and full depth read 82 ms plus the cubic read's margin, past the end
  (at 48 kHz, 3,938 samples into a 3,908-sample line), which wrapped round to
  the newest audio. Sized from the real maximum now.
- **No `rand()` on the audio thread**: Bitcrush dither and Pitch Shifter
  jitter called it, and bionic's takes a lock. Each effect has its own
  xorshift generator (`FastRandom`). Compactor no longer reallocates its
  delay when Attack moves.

Host tests: `recorder_mixer_test` and `recorder_dsp_test`, which covers each
fix above (its limiter attack, wet/dry and Stereo checks were run against the
code before the fix and failed). Run them with
`cmake -S recorder/src/main/cpp -B /tmp/dsp -G Ninja && cmake --build /tmp/dsp && ctest --test-dir /tmp/dsp`.

### Creative assets and Music Links

Choose **LUT Library** in a clip's grade panel to use a bundled look or open a
local 3D .cube. The eight looks come from Striped Purple and FilmSim and are
stored as 17-point cubes. **Add audio** opens the Music Library, where six CC0
loops and downloaded tracks are available alongside the document picker.
Settings accepts direct HTTPS audio-file links and keeps successfully imported
audio in app storage for offline use and draft reopening. Provenance, source
revisions and license texts ship under `assets/creative/licenses`.

The FX chain uses the Tryptify rotary controls, graph math, graph handles and
preset values from revision `67a7f67c160de2e6b5fc1b9b41bb8c20b4a30eaa`.
Recorder retains its own bus routing and draft format. Oversampling runs inside
each native effect at the selected 1×, 2× or 4× factor.

**Settings → Music Links** provides the Tryptify band follower, feel, direction,
curve, smoothing and optional formula for four Recorder grading controls.
A version-1 Tryptify Music Links preset opens a mapping dialog so a source link
can be assigned to a Recorder control and edited before saving. Settings links
are copied into new projects. Use **Adjust → Apply Settings links** to copy them
into a draft. Preview and picture exports analyze the processed Master signal
and use the resulting values by timeline timestamp. That first pass adds time
when the audio or links change, and the app reuses recent analyses in memory.

- **The Adjust slider is the base a link moves around.** A linked brightness,
  contrast or saturation is the look's own slider value plus the link's
  movement, held to the control's -1..1 range (`MusicLinkTimeline.linked`);
  exposure, which has no slider in the look, moves around 0. The static
  effect leaves a linked control out, and `MusicGrade` applies it once, the
  same in preview and export.
- **Pause when silent** (on by default): once the followed band has been
  below -70 dB for 2 s the link goes to rest — lowest for Up, highest for
  Down, the middle for Both — instead of drifting on the follower's tail;
  the first sound after that wakes it.
- **The analysis encodes nothing.** It runs through Media3's analyzer mode
  (`ExperimentalAnalyzerModeFactory`): the whole audio pipeline and mixer
  run and are measured, but the encoder drops its input and the muxer writes
  no file.
- **One analysis per sound.** It is keyed by the audio-only plan (with the
  mixer, since the links follow the mixed Master — only switched-off effects
  and effect ids are left out) and the links. The preview and an export
  asking for the same key share one run; it keeps running for 3 s after its
  last waiter gives up, because starting an export stops the preview's wait
  just before the export asks. A failed analysis is remembered, and the
  preview plays without it rather than starting it again on every rebuild
  (an export still tries). A change to the mix alone keeps the player: the
  live mixer plays it at once, the links are measured again in the
  background, and the player is rebuilt once when that is done.

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
      four modes; detached audio lines up after export.
- [ ] Titles and fades appear at the same times in preview and export.
- [ ] Film grain: fine and natural at 1:1 on the phone, the same in preview
      and export, moving every frame; no mesh or banding at coarse size.
      Each preset and control changes the preview; *Show grain only* never
      reaches an export; 24 fps motion holds the grain across frames of a
      60 fps recording; a long 1080p export with colour grain still runs.
- [ ] Export frame rate: a 30 fps clip at 60 and at 165, a 165 fps recording
      at 60 and at 24, and a clip slowed to 0.25×, each with *Optical flow*
      on and off — the file reports exactly the chosen rate, lasts as long
      as the timeline, sound stays in step, motion is smooth with flow on;
      a cut between clips is clean (no frame mixing both); a screen recording
      that sat still and then changed does not morph; fast scrolling falls
      back to repeated frames rather than smearing; titles, grain and fades
      are crisp on made frames; with a picture-in-picture layer; a 4K export
      at 60 completes; an HDR source. Nothing of this has run on a phone.
- [ ] Crop tool: the box sits exactly over the picture; corners, edges and
      the inside each grab and follow the finger; a held shape stays held;
      the export matches the box — also after Rotate and Flip.
- [ ] GIF export of a portrait recording comes out upright, in
      `Pictures/TryptifyRecorder`, loops, and plays at the chosen rate in a
      browser and a chat app; cancel mid-way leaves no file; a still stretch
      stays a single long frame.
- [ ] Drafts reopen after the app is killed (persisted document permissions).
- [ ] Trim: drag both ends of a video clip, an audio piece and a picture-in-
      picture layer, quickly and slowly, while playing and paused, with and
      without mixer effects — the app stays up; the preview catches up when
      the finger pauses or lifts.
- [ ] Mixer effects: Resonator, Shaper (Mirror), Ladder (Sat off, high cutoff,
      resonance 100) never silence a bus or hang an export; Pitch Shifter up
      sounds higher; Reverser plays backwards; Tape Stop slows to a stop.
      Limiter on a loud master: no click as it clamps, level never over its
      ceiling; a limiter, and any effect at 2×/4×, at 50 % wet sounds like
      the effect, not hollow or phasey; Trance Gate's BPM knob changes the
      speed and Length 32 keeps gating; Stereo at its defaults does not
      change the level; a project saved before this (Bitcrush, Reverser,
      Flanger, Trance Gate in its chain) opens and sounds as it did, apart
      from Flanger's Stereo now meaning a percentage.
- [ ] Look: the accent follows the wallpaper (Android 12+); the top bar,
      export sheet, segmented control and dock are glass that bends and
      frosts what is under it (Android 13+), a plain surface on 10–12;
      every export setting still reachable (More); Save exports. The app's
      dock, the Library switch and the cards on all three pages are glass;
      lists scroll smoothly with many glass cards on screen.
- [ ] Crash report: after a crash (or a freeze), Settings shows *Last crash*
      and *Share report* sends it.
- [ ] Setup off the main thread: start from the app, the floating button
      (countdown 0 and 3, "hide while recording" on and off), the tile and
      the notification on Android 10, 13 and 14+; cancel during the countdown
      and right after it — no stray recording, button back on screen.
- [ ] Storage: fill the phone to ~250 MB free and record — it stops and saves
      with "Storage almost full", and the file plays.
- [ ] Audio clock: an hour-long recording with a game, and one with the CPU
      busy (exporting meanwhile): A/V sync at the end; logcat
      `ScreenRecorder` "audio behind the clock" only after a real stall.
- [ ] Edit-ready: record with it on, open the recording *from the Library* —
      stems and markers are there; delete the recording — its
      `capture-sessions/m<id>` folder goes; kill the app mid-recording,
      reopen — no pending file left in `Movies/TryptifyRecorder`.
- [ ] Floating button while recording a still screen: the file's frame rate
      stays low (a media-info app), and the ring pulses.
- [ ] Android 10: delete and rename a recording from a previous install
      (consent dialog, then it happens).
- [ ] Bundle: export and cancel mid-way — no partial `.zip` left; import a
      bundle zipped with a `media/` folder entry.
- [ ] Backup: `adb shell bmgr backupnow` then restore — settings and drafts
      come back; stems, bundles, music and the crash report do not.
- [ ] TalkBack: the export sheet's stepped sliders (Resolution, Frame rate,
      Quality) read their value and move a step with a swipe up or down;
      a timeline clip reads its name and length and "double tap to select";
      the Record screen's share button reads "Share".

**Look.** Material You: on Android 12+ the accent and its tones come from the
wallpaper (`dynamicDarkColorScheme`), on a near-black ground kept for video;
below 12 a fixed amber. Floating surfaces are Tryptify's liquid glass,
ported as `ui/glass` (see its header for which files and what was left out):
on Android 13+ an AGSL slab that builds bevel, refraction and rim from the
pane's shape over a live lens or Haze frost of what is behind it, and a
Material surface below 13 or wherever the shader will not compile. The
editor follows the owner's reference layout: Cancel / format / Save across
the top, the export sheet as a glass pane (stepped Resolution, Frame rate and
Quality sliders with the estimated file size), the transport, a tool row, and
a glass dock whose tabs (Edit, Audio, Text, Adjust, Filter, Frame) pick the
tools. The rest of the app is the same material: the
three pages sit under a floating glass dock (Record, Library, Settings), the
Library's Recordings/Drafts switch is the glass segmented control, and the
cards in every page — the Record screen's, each recording and draft, each
Settings section — are glass laid in the page (`GlassCard`: the slab alone,
no backdrop, as Tryptify's cards in its lists are). The editor's preview draws into a TextureView, not a SurfaceView: a
SurfaceView shows video through a hole cut in the window, and the glass, which
copies what is behind it, copied the hole — the video came through the export
sheet sharp. The export sheet is also *frosted* (Tryptify's option: the full
backdrop blur and a dark tint instead of the clear live lens), because it sits
over moving video. Glass panes are drawn *beside* the content they frost, never inside
it; a pane inside its own backdrop paints a flat slab. Tool sheets sit in the
page and are Material surfaces for that reason. Nothing of the glass has been
seen on a phone yet; the shaders are byte-for-byte Tryptify's.

**Crash reports.** `CrashLog` keeps the last crash in the app's private
storage: a Kotlin crash with its stack trace as it happens, and a native crash
or a freeze ("not responding", with every thread's stack) read back from
Android's exit history on the next start (Android 11+). Settings shows it under
*Last crash* with *Share report*. Nothing is sent anywhere unless the user
shares it.

**Preview rebuilds.** A slider drag changes the project many times a second;
the preview is rebuilt only once the drag pauses (450 ms) or ends, not on
every step, since each rebuild replaces decoders, video graph and mixer. A
rebuild that fails drops the player instead of leaving it half set up, and
every call into the player from the screen is guarded.

**What playback redraws.** The playhead changes 30 times a second while
playing, and the mixer's meters as often. Neither is read where the editor is
composed: the playhead goes down as a function, read only by the time, the
step buttons' enabled state (derived, so they change only at the ends), the
timeline's scroll effect, the mixer's seek row and the few tool panels that
show it (each in a scope of its own); buttons that act at the playhead read
it when tapped. The meters are read in their own drawing. The timeline ruler
draws only the marks on screen, measured against a fixed width so the text
measurer's cache serves them; a long project used to measure ~850 labels per
redraw. Nothing here has been profiled on a phone.

Known limits: exports run in-app (the screen is kept on while one runs) rather
than in a background service; preview of a very long project at high zoom is
capped to keep the timeline under Compose's layout size limit; "show touches"
cannot be enabled by a regular app.
