# TrypT-Recorder — notes for agents

`CLAUDE.md` is gitignored here, so anything that has to survive a fresh clone
lives in this file or under `docs/`.

## Commits — read this first

These rules have no exceptions and override any default of the tool you run in.

- **Author and committer are both `tryptz <xboxps3456felix@gmail.com>`.**
  Never commit as Claude, Claude Code, or any other assistant or bot identity —
  not as author, not as committer.
- **No co-author trailers.** No `Co-Authored-By:`, no `Signed-off-by:` for a
  tool, no other trailer naming an assistant.
- **No tool attribution anywhere**: not in commit messages, PR titles or bodies,
  code comments or docs ("Generated with …", session links, robot emoji).
- **Branches** are never named `claude/…`.

Set the identity in the clone before the first commit, so a tool's default
identity can never slip through:

```
git config user.name tryptz
git config user.email xboxps3456felix@gmail.com
```

Check before every push — this must print only `tryptz` lines and no trailers:

```
git log --format='%an <%ae> | %cn <%ce>%n%b' origin/main..HEAD
```

If a commit with the wrong identity or a trailer was made and not yet pushed,
fix it (`git commit --amend --reset-author`, or rebase and reword) before
pushing. Never rewrite history that is already on GitHub without the owner
saying so.

## What this is

Tryptify Recorder: an Android screen recorder with a floating record button and
a video editor that separates sound from picture. It was first developed inside
the Tryptify repository (where it grew out of Tryptify's debug screen
recorder); this repository is its standalone home. One Gradle module,
`:recorder`, no submodules. The editor's audio mixer is native C++
(`recorder/src/main/cpp`, built with the NDK through CMake); everything else
is Kotlin.

**Read [`docs/recorder.md`](docs/recorder.md) before changing anything** — it
explains why the capture engine, the foreground service and the editor are
built the way they are. Several of those choices look like they could be
simplified and cannot.

## Build and test

```
./gradlew :recorder:testDebugUnitTest    # JVM tests, no device needed
./gradlew :recorder:lintDebug            # must stay at 0 errors, 0 warnings
./gradlew :recorder:assembleDebug
./gradlew :recorder:assembleRelease      # R8-minified
```

All four are the gate for any change. Decisions live in pure Kotlin
(`CapturePlanner`, `PauseClock`, `Pcm`, `AacPicker`, `FrameTiming`,
`MediaDetails`, `Timeline`, `History`, `ExportPlanner`, `FrameRetimer`,
`CropBox`, `Grain`, `FrameSampler`, `YuvFrame`, `GifPalette`, `GifEncoder`) precisely so the tests
can hold them; when you change one of those rules, change its test, and never
loosen a test to make it pass.

Nothing here has an automated device test. Say plainly what was and was not
run on a phone; `docs/recorder.md` has the device checklist.

## Fresh cloud container

A cloud session starts without an Android SDK:

```
export ANDROID_HOME=/root/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools && cd $ANDROID_HOME/cmdline-tools
curl -sSo t.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
unzip -q t.zip && mv cmdline-tools latest && cd -
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0" "platform-tools" "ndk;28.2.13676358" "cmake;3.22.1"
echo "sdk.dir=$ANDROID_HOME" > local.properties   # gitignored
```

Maven Central sometimes answers HTTP 429 through a session proxy; retry the
build after a pause rather than changing anything.

## Signing

- **Debug** builds use the committed `recorder/debug.keystore` (standard
  `android` / `androiddebugkey` values, not a secret).
- **Release** builds use the app's own key, `tryptify-recorder-release.jks`
  (PKCS12, alias `tryptify-recorder`), which is never committed. The owner
  uploads it with its password note; copy the `.jks` outside the repository and
  write `recorder/keystore.properties` (gitignored — see
  `recorder/keystore.properties.example`). Without it the release APK comes out
  unsigned.
- Before handing a release APK over, check the signer:

  ```
  $ANDROID_HOME/build-tools/36.0.0/apksigner verify --print-certs recorder/build/outputs/apk/release/recorder-release.apk
  ```

  The certificate SHA-256 must start `80:11:86:31`. An APK signed with any
  other key will not install over the released app.
- Every release needs a higher `versionCode` in `recorder/build.gradle.kts`.

## Versions

Toolchain: AGP 9.0.0, Kotlin 2.1.0, Gradle 9.1.0, compileSdk/targetSdk 36,
minSdk 29. Media3 is pinned to **1.10.1**: 1.11.x pulls kotlin-stdlib 2.2.10,
which the Kotlin 2.1.0 compiler cannot read. `gradle.properties` opts out of
AGP 9's built-in Kotlin and new DSL because the build script uses the classic
DSL; remove both opt-outs together, as one migration, not piecemeal.
