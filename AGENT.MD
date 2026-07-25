# Bad Watch – Maintainer Guide

For anyone (human or agent) developing, debugging or triaging this project. Read
[`docs/PRODUCT_PLAN.md`](docs/PRODUCT_PLAN.md) for *why* the project exists and what is
planned; this file is about how to work on it.

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
   user-facing claims on top of them until Phase 2 lands.

## Environment

- **JDK 17.**
- **Android SDK** with `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`.

```bash
brew install --cask android-commandlinetools          # macOS
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

`local.properties` is gitignored and must exist before `:app` will configure.

**Toolchain note:** the project is on Kotlin 2.1 with AGP 8.13. The Compose compiler is
applied as the `org.jetbrains.kotlin.plugin.compose` Gradle plugin — there is no
`composeOptions.kotlinCompilerExtensionVersion` any more. Staying on Kotlin 1.9 with this
AGP made lint crash (`InconsistentKotlinMetadataException` in `ComposableFlowOperatorDetector`),
so do not downgrade Kotlin without also downgrading AGP.

## Module map

| Module | What lives there |
| --- | --- |
| `:core` | Models, feature extraction, classifier, rally segmentation, `SessionRecorder`, sync contract. Pure JVM. |
| `:app` | Wear app: `FusedSensorCollector`, `SessionService`, `SessionController`, `SessionStore`, `SyncWorker`, Compose UI. |
| `:server` | Ktor dashboard: ingest, aggregation, static page, synthetic fixtures. |

Key entry points: `SessionController` owns the live session at application scope;
`SessionService` is the foreground service that keeps the process alive; `SessionRecorder`
is the `:core` orchestrator you probably want to change for analysis work.

## Build and verify

```bash
./gradlew test                # JVM tests, both :core and :server — fast, run these constantly
./gradlew :app:lintDebug      # abortOnError + warningsAsErrors; treat failures as real
./gradlew :app:assembleDebug
./gradlew :server:run         # dashboard on :8080
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

```bash
adb install -r -g app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.badwatch.badwatch/com.badwatch.app.MainActivity
adb exec-out screencap -p > screen.png
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
| Session files | `adb shell run-as com.badwatch.badwatch ls files/sessions` — `*.json` plus `*.json.synced` markers |
| Classifier regressions | Add the trace as a fixture in `:core` tests rather than debugging on device |

## Release

1. Clean tree, `./gradlew test :app:lintDebug` green.
2. `tooling/tag_release.sh <version>` bumps `VERSION.md`, runs tests, commits and tags.
3. `git push origin master --tags`.
4. Release notes from `CHANGELOG.md`.

Keep this file current when the topology or toolchain changes.
