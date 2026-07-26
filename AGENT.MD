# Bad Watch – Maintainer Guide

For anyone (human or agent) developing, debugging or triaging this project. Read
[`docs/PRODUCT_PLAN.md`](docs/PRODUCT_PLAN.md) for the completed product contract and
research boundaries; this file is about how to work on it.

## Ground rules

1. **Do not describe unbuilt features as built.** This repository previously shipped docs
   for a shot classifier, DataStore history and a foreground service that did not exist,
   which cost real time to untangle. Mark roadmap items as roadmap.
2. **`:core` is platform-free.** No Android imports. It is where the testable logic lives,
   and the reason the recording pipeline can be verified without an emulator.
3. **`:core` owns the wire contract.** `com.badwatch.core.sync` types are compiled into both
   the watch and the server. Changing them changes both — bump `SessionExport.SCHEMA_VERSION`
   for anything breaking.
4. **The classifier is not calibrated.** Stroke labels are provisional. Do not build
   user-facing claims on top of them unless every model gate in the product plan has passed
   on separately held-out real play.
5. **Never put NaN in a serialized field.** JSON cannot represent it, and the failure only
   surfaces on save — after the recording is already lost. Use null for "no reading".
6. **Raw and reviewed are separate.** Never overwrite `SessionExport.session` or its original
   `rallyProfile`. Use `reviewedAnalysis()` for current user-facing metrics and keep reported
   misses separate because they have no timestamp.
7. **A server rejection is not a transport failure.** Only permanent record validation may
   create a `.rejected` marker. Network, disk and server failures must remain retryable.

## Environment

- **JDK 17.**
- **Android SDK** with `platforms;android-36`, `build-tools;36.0.0`, `platform-tools`.

```bash
brew install --cask android-commandlinetools          # macOS
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

`local.properties` is gitignored and must exist before `:app` will configure.

**Toolchain note.** Kotlin 2.2, AGP 8.13, Compose BOM 2026.01, Wear Compose 1.6.2
(Material 3 — there is no Wear Material 2 dependency any more), `compileSdk` 36. The Compose
compiler is applied as the `org.jetbrains.kotlin.plugin.compose` Gradle plugin — there is no
`composeOptions.kotlinCompilerExtensionVersion` any more.

These versions are coupled, and getting them out of step breaks lint rather than the build,
which makes it confusing to diagnose. `ComposableFlowOperatorDetector` ships with
`androidx.compose.runtime`'s lint checks and throws `InconsistentKotlinMetadataException` on
any file using a Flow operator when its metadata reader is older than the Kotlin that
produced the class files. It aborts the entire analysis task, taking every other check with
it. If `lintAnalyzeDebug` starts failing with "a bug in lint", the Compose BOM is too old for
the Kotlin version — bump the BOM, do not add suppressions (they do not work; the detector
crashes before severity is applied).

## Module map

| Module | What lives there |
| --- | --- |
| `:core` | Models, detector, exchange segmentation, reviewed projection, physiology, insights, progress, match/training reducers, and shared sync/archive contract. Pure JVM. |
| `:app` | Wear sensing/Health Services, health FGS, active journal, atomic stores, sync markers, Compose UI, Tile, and complication. |
| `:server` | Ktor auth/ingest, persistence, reviewed analytics, diary editing, backup/CSV/restore, browser dashboard, and fixtures. |
| `tools/` | Consent-aware capture ingestion and participant-grouped offline classifier evaluation/training. |

Key entry points: `SessionController` owns the live session at application scope;
`SessionService` is the foreground service that keeps the process alive; `SessionRecorder`
is the `:core` orchestrator you probably want to change for analysis work. Active recordings
checkpoint to `files/active-session/journal.json`; do not clear that file except after a
durable final save or an explicit user discard.

## Build and verify

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 -m py_compile tools/ingest.py tools/train.py tooling/wear_session_probe.py
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --stacktrace --no-daemon
./gradlew :server:run         # dashboard on 127.0.0.1:8080
```

Lint is strict on purpose. Two families of check are disabled in `app/build.gradle.kts`
(upstream-version-freshness checks), each with a comment explaining why — do not disable
anything else without one.

## Working on analysis logic

Prefer `:core`. Anything you can express as "samples in, numbers out" belongs there and gets
a deterministic JVM test. `SessionRecorderTest` shows how to feed synthetic swing profiles
through the whole pipeline; `RallySegmenterTest` covers rally grouping.

Changing classifier thresholds: adjust one at a time and update the tests to state the
physics you expect. The tests encode intent, not just current behaviour.

## Working on the dashboard

```bash
./gradlew :server:seedDemoData -PdataDir=/tmp/badwatch-demo
BADWATCH_DATA_DIR=/tmp/badwatch-demo ./gradlew :server:run
```

The page is a single self-contained `server/src/main/resources/static/index.html` — inline
CSS/JS, hand-rolled SVG charts, no build step and no external requests. Aggregation happens
server-side in `Analytics.kt`; keep the page a thin renderer.

## Running on a device or emulator

There is no Wear system image required for a smoke test — `uses-feature` does not block
`adb install`, so a phone emulator runs the app fine (stretched, but functional). A real
Wear AVD is still needed for layout work.

**A 480 x 480 Pixel Watch 4 running Android 17 / API 37 is available on the local network** for real
on-device verification — prefer it over the emulator for anything visual, haptic or
ambient. The machine and watch are already paired; reconnect with:

```bash
adb mdns services          # find the _adb-tls-connect._tcp id, then:
adb -s <mdns-id> shell getprop ro.product.model   # expect "Pixel Watch 4"
```

If the pairing is ever lost: on the watch, Settings → Developer options → Wireless
debugging → Pair new device, then `adb pair <ip>:<port> <code>` (run `adb kill-server`
first — a stale server fails pairing with a cryptic "protocol fault"). Wireless
debugging is user-controlled and may be off; if the watch is unreachable, ask the user
to enable it rather than burning time on the emulator for hardware-only checks. The
debug build is installed; `adb install -r -g app/build/outputs/apk/debug/app-debug.apk`
updates it. Two quirks verified on this device: Wear M3 edge buttons only grow in as
you scroll toward the end of a list (not a bug), and AlertDialog confirm/dismiss are
icon-only circles with no text labels in the accessibility tree.

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.badwatch.badwatch/com.badwatch.app.MainActivity
python3 isolate/wearos_tool.py --serial <mdns-id> screenshot \
  --output isolate/artifacts/screenshots/screen.png
```

Point a debug build at a dashboard:

```bash
adb shell am broadcast -a com.badwatch.app.SET_DASHBOARD \
  -n com.badwatch.badwatch/com.badwatch.app.debug.DashboardConfigReceiver \
  --es url "http://10.0.2.2:8080"
```

`10.0.2.2` is the host loopback from inside an emulator. Debug builds permit cleartext HTTP
via `src/debug/res/xml/network_security_config.xml`; **release builds do not**, so a sync
failure in a release build against a plain-HTTP server is expected behaviour, not a bug.

`isolate/wearos_tool.py` wraps emulator control, screenshots and UI dumps in a JSON-speaking
CLI if you want scripted verification.

## Debugging checklist

| Symptom | Where to look |
| --- | --- |
| Recording stops when screen sleeps | `dumpsys activity services com.badwatch.badwatch` — expect `isForeground=true types=0x100` |
| Sessions not syncing | `adb logcat -s BadWatchSync` — the worker logs the cause. Cleartext HTTP in a release build is the usual answer. |
| No shots detected | Expected on an emulator (static virtual gyro). On device, check the strap and that the watch is on the racket wrist. |
| Session files | `adb shell run-as com.badwatch.badwatch ls files/sessions` — `*.json` plus fingerprinted `.synced`/`.rejected` markers |
| Recording restored after a process kill | `adb shell run-as com.badwatch.badwatch ls -l files/active-session` before restart; the resumed export keeps its ID and is marked `Partial` |
| Classifier regressions | Add the trace as a fixture in `:core` tests rather than debugging on device |
| Drill lost on save | Should be impossible now — heart rate is nullable and `SensorSample` rejects NaN. If it recurs, check `logcat -b crash` for `JsonEncodingException`. |

## Release

1. Clean tree and the complete Python/JVM/lint/debug/release gate above green.
2. `tooling/tag_release.sh <version>` bumps `VERSION.md`, runs tests, commits and tags.
3. `git push origin master --tags` — pushing the `v*` tag triggers
   `.github/workflows/release.yml`: it re-runs tests and lint, builds the APKs, and
   publishes a GitHub Release with notes extracted from `CHANGELOG.md`.
4. Release notes from `CHANGELOG.md`.

The release always carries the debug-signed APK (installable via adb). To attach a
properly signed release APK, add four repository secrets — `RELEASE_KEYSTORE_BASE64`
(`base64 -i release.keystore | pbcopy`), `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD` — and the workflow signs the unsigned AGP output with
`zipalign` + `apksigner`. No keystore ever enters the repo.

Keep this file current when the topology or toolchain changes.
