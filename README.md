# Bad Watch

A badminton training companion for Wear OS, built for the Pixel Watch 3. It records what
actually happened in a session — shots, rallies, work-to-rest ratio, heart rate — from the
wrist alone, and pushes it to a self-hosted dashboard you can open in any browser.

No racket sensors. No account. No cloud you do not run yourself.

> **Status: in development.** See [What works today](#what-works-today) for the honest
> current state, and [`docs/PRODUCT_PLAN.md`](docs/PRODUCT_PLAN.md) for where it is going.
> Shot classification is currently rule-based and uncalibrated — treat the stroke labels as
> provisional until the ML work in Phase 2 lands.

## Wear it on your racket hand

Bad Watch detects shots from the swing itself. Worn on the non-racket wrist there is simply
no swing to read, and stroke detection does not work at all. The app says so during
onboarding rather than producing confident nonsense. Handedness (left/right) is a setting,
because the backhand pronation signature mirrors between hands.

## What works today

| Area | State |
| --- | --- |
| Multi-sensor capture | ✅ Gyroscope + accelerometer + heart rate, fused at 100 Hz with hardware FIFO batching and monotonic sensor timestamps |
| Recording survives screen-off | ✅ Foreground service with `health` type; a session no longer dies when the wrist drops |
| Shot detection | ⚠️ Rule-based heuristics in `:core`, wired end to end but **not yet calibrated against real play** |
| Rally segmentation | ✅ Rally count, length distribution, work:rest ratio, playing-time density |
| Session insights | ✅ Evidence-backed, derived only from rally structure and heart rate — never from stroke labels |
| Session persistence | ✅ One JSON file per session on the watch, durable across restarts |
| Dashboard sync | ✅ WorkManager-backed upload with retry; the watch is fully functional offline |
| Web dashboard | ✅ Self-hosted Ktor server + browser dashboard with load trend, shot mix, rally distribution |
| Labelled data collection | ✅ On-watch drill records labelled swings; `tools/` ingests and trains a baseline |
| ML classifier | ❌ Not started — needs real players. See [`tools/README.md`](tools/README.md) |
| Footwork, lunges, jumps | ❌ Not started (Phase 4) |
| Auto-scoring, match mode | ❌ Not started (Phase 5) |

## Repository layout

```
app/       Wear OS app — sensing, foreground service, storage, sync, Compose UI
core/      Platform-free analytics — classifier, rally segmentation, session math,
           and the sync contract shared verbatim with the server
server/    Self-hosted Ktor dashboard — session ingest + browser dashboard
docs/      Architecture, usage, dashboard setup, product plan
tools/     Python capture ingestion and classifier training
isolate/   Headless Wear OS emulator tooling (screenshots, UI dumps, adb wrappers)
tooling/   Release helper scripts
```

`core` is the single source of truth for the data model. Both the watch and the server
compile against the same `@Serializable` Kotlin types, so there is no second schema to
drift out of sync.

## Getting started

### Prerequisites

- JDK 17
- Android SDK with `platforms;android-36` and `build-tools;36.0.0`
  (the app compiles against 36; `targetSdk` stays at 34)

On macOS:

```bash
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties
```

`local.properties` is gitignored and must exist before `:app` will build.

### Build and test

```bash
./gradlew test                    # JVM tests: core analytics + server contract
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Run the dashboard

```bash
./gradlew :server:run
# → http://localhost:8080/
```

To see it populated without a watch on a court:

```bash
./gradlew :server:seedDemoData    # writes synthetic sessions into ./badwatch-data
./gradlew :server:run
```

Full setup, including reaching the server from the watch and configuring a token, is in
[`docs/dashboard.md`](docs/dashboard.md).

## Documentation

- [Product plan and roadmap](docs/PRODUCT_PLAN.md) — the vision, phased
- [Architecture](docs/architecture.md) — how the pieces fit
- [Usage guide](docs/usage.md) — using it on court
- [Dashboard setup](docs/dashboard.md) — running and securing the server
- [Training pipeline](tools/README.md) — collecting labelled swings and training a classifier
- [Agent/maintainer guide](AGENT.MD) — environment, workflows, debugging
- [Changelog](CHANGELOG.md)

## Versioning

The version in `VERSION.md` is injected into the manifest at build time.
`tooling/tag_release.sh <version>` bumps it, runs tests, and tags a release.

## License

Apache 2.0.
