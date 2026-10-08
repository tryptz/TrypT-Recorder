# TrypT-Recorder

**Tryptify Recorder** — an Android screen recorder with a floating record
button, and a video editor that can separate sound from picture.

Android 10 or newer.

## Recording

- **Floating record button** over every app: tap to record, tap again for
  pause and stop, long-press to open the app or hide it, drag it anywhere.
  It can hide itself while recording so it stays out of the video.
- Also from the app, a Quick Settings tile, or the notification.
- **Up to 165 fps**, capped by the screen's fastest refresh rate; resolution
  from native down to 480p; three quality levels. If a phone's encoder cannot
  manage a setting, the recorder lowers resolution first (to 720p), then frame
  rate, instead of failing.
- **48 kHz stereo AAC at 320 kbps**: device audio (captured digitally, no room
  noise), microphone (stereo where the phone has the microphones), both mixed,
  or none.
- Pause and resume, countdown. Saved to `Movies/TryptifyRecorder`.
- **File details** read back from each video: codec, profile and level,
  resolution, frame rate, video and audio bitrate, sample rate, channels.

## Editing

- Split, trim, speed (0.25×–4×, pitch kept), volume up to 200 %, mute,
  duplicate, reorder.
- **Detach audio**: a clip's sound becomes its own piece on an audio track, to
  move, trim, split, fade or delete independently; *Restore audio* puts it back.
- Export as video, **video without sound**, **sound only** (M4A), or a
  looping **GIF** (320–640p, 10–25 fps, with every edit in it) — plus a
  one-tap *Extract audio* in the library.
- **Crop by touch**: drag the corners, edges or the whole box on the picture,
  any size and shape, or held to 1:1, 4:5, 9:16, 16:9, 4:3 or 3:4.
- Music tracks, titles, aspect ratio, rotate, flip, filters, brightness,
  contrast, saturation, fades. Undo/redo; drafts save themselves.
- The preview plays exactly what the export will render.

## Build

```
./gradlew :recorder:testDebugUnitTest :recorder:lintDebug :recorder:assembleDebug
./gradlew :recorder:assembleRelease
```

Needs the Android SDK (platform 36, build-tools 36.0.0) and JDK 17+.
Release signing reads `recorder/keystore.properties`, which is never committed;
see `recorder/keystore.properties.example`.

## More

[`docs/recorder.md`](docs/recorder.md) — how it works and why, and what still
needs checking on a real device. [`AGENTS.md`](AGENTS.md) — rules for anyone,
human or tool, committing here.
