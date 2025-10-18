# Bad Watch – Agent Guide

This doc is for future maintainers who will develop, debug, or triage the Pixel Watch badminton trainer. It summarises the project topology, local environment requirements, verification steps, and common workflows.

## Environment bootstrap
- **Java:** Wear Compose targets JDK 17. Install Temurin 17 (or equivalent) under a stable path, e.g. `/home/you/.jdks/jdk-17.x`. Export `JAVA_HOME` and prepend `$JAVA_HOME/bin` to `PATH`.
- **Android SDK:** Minimum is API level 30 (Wear OS 4), target is API 34 (Wear OS 5.1). Install with `sdkmanager`:
  ```
  sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0"
  ```
  Create `local.properties` (`sdk.dir=/path/to/android-sdk`) so Gradle resolves the SDK without environment variables.
- **Gradle wrapper:** Committed with the repo (`./gradlew`). No global Gradle install required.
- **Optional tooling:** Android Studio Koala (or newer) for the Wear emulator, Compose previews, and profiler.

## Project map
```
app/        Wear OS app (Compose UI, services, DI, DataStore)
core/       Platform-agnostic analytics (shot classification, sessions)
docs/       Architecture notes, usage guide
tooling/    Release helper scripts (version bump, tagging)
```
- `BadWatchApplication` wires dependencies: sensor stream + DataStore-backed repository.
- `TrainingSessionViewModel` orchestrates sessions, sensor pipeline, and persistence.
- `core` holds the motion pipeline (`ShotClassifier`, `ShotDetectionPipeline`, `TrainingSessionAggregator`), extensively unit-tested to catch regressions.

## Build + verification
- **Assemble:** `./gradlew :app:assembleDebug`
- **Unit tests:** `./gradlew test`
  - Runs JVM tests from both modules; includes deterministic pipelines and ViewModel orchestrations.
- **Instrumentation (optional):** Once a Wear OS emulator/device is connected, extend `app/src/androidTest` and run `./gradlew connectedDebugAndroidTest`.
- **Static analysis:** Not enforced yet. If you add lint/detekt, gate them via `check` task.

## Emulator & device tips
- Use Android Studio Device Manager to create a **Pixel Watch 3, API 34** image.
- Accept the BODY_SENSORS permission prompt manually on first launch.
- For sensor debugging, log the gyroscope stream by adding `Log.d` in `SensorCollector`; keep logs behind a `BuildConfig.DEBUG` guard.
- To capture screenshots, use Device Manager’s “Take screenshot…” button or `adb exec-out screencap -p > screen.png`.

## Adding features
1. **Plan:** Update `docs/architecture.md` or add a new ADR if you substantially change the pipeline/UI.
2. **Core first:** Extend `core` with new data models or classification logic. Write unit tests before wiring into the app.
3. **App layer:** Introduce ViewModel changes, state flows, and Compose surfaces. Keep business logic in `core` or domain classes for testability.
4. **Persistence:** Update `TrainingSessionLog` schema if storing extra metadata. Bump DataStore version only when you add non-backward-compatible changes; consider migrations.
5. **Docs & versioning:** Record user-visible changes in `CHANGELOG.md` and bump `VERSION.md` if you are shipping.

## Bug fixing checklist
- Reproduce with deterministic tests where possible (`core` module is ideal for sensor maths).
- For runtime crashes, gather logs via `adb logcat | grep BadWatch`.
- If classification accuracy regresses, drop sample traces into a dedicated unit test (use `SensorSample` fixtures) to lock in expected output.
- Ensure `ShotClassifier` heuristics keep the tests green; tweak thresholds in one place at a time and update tests to reflect the expected physics.
- For DataStore issues, inspect `/data/data/com.badwatch.badwatch/files/datastore/training_sessions.json` on the emulator via `adb shell run-as com.badwatch.badwatch cat …`.

## Release workflow
1. Ensure the working tree is clean and tests pass.
2. Run `tooling/tag_release.sh <new-version>`; it bumps `VERSION.md`, runs tests, commits, and tags `v<version>`.
3. Push with `git push origin master --tags`.
4. Generate release notes from `CHANGELOG.md`.

## CI considerations
- Current repo relies on local execution. If you add CI (GitHub Actions), cache the Gradle/SDK directories and run `./gradlew test assembleDebug`.
- For emulator-based tests in CI, use headless Wear OS emulators (requires hardware acceleration and API 30+ system images).

## Support matrix
- **Pixel Watch 3, Wear OS 5.1** (primary target)
- Compatible with API 30+ for backward deployment (but classifier calibrations are tuned for PW3 sensors).

## Useful scripts
- `tooling/tag_release.sh` automates SemVer bump, tests, and tagging.
- To clean builds: `./gradlew clean`.
- To regenerate Dokka docs (when added): `./gradlew dokkaHtml`.

Keep this file synced with major architectural or tooling changes so future agents can ramp up quickly. Happy hacking! 👟🏸
