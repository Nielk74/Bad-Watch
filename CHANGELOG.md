# Changelog

## [Unreleased]

### Added
- **Real session recording.** Fused gyroscope + accelerometer + heart-rate capture at 100 Hz
  with hardware FIFO batching, driven by a `health` foreground service so a session survives
  the watch screen turning off.
- **Rally analysis.** `RallySegmenter` groups shots into rallies and derives rally length
  distribution, work:rest ratio and playing-time density — the numbers that actually
  characterise an interval sport.
- **Session persistence.** One JSON file per session on the watch, written atomically, with
  history and post-session recap screens.
- **Self-hosted dashboard.** New `:server` Ktor module with session ingest, an optional
  bearer token, and a browser dashboard showing training volume, rally distribution, shot
  mix and an acute:chronic shoulder-load trend.
- **Dashboard sync.** WorkManager-backed upload with exponential backoff and per-session
  acknowledgement; the watch stays fully functional offline.
- `SessionRecorder` in `:core`, making the whole recording path unit-testable on the JVM.
- Racket-wrist onboarding and left/right handedness, which mirrors the backhand pronation
  feature.
- Synthetic session generator (`:server:seedDemoData`) for developing against the dashboard.
- CI running JVM tests, lint and a debug assemble.

### Changed
- `:app` now depends on `:core`. Previously the analytics module was compiled but never
  referenced by the application.
- Sensor timestamps come from the monotonic `SensorEvent.timestamp` rather than wall-clock
  time read on the delivery thread.
- Persistence uses per-session JSON files rather than the previously documented (and never
  implemented) DataStore.
- Migrated to Kotlin 2.1 with the `org.jetbrains.kotlin.plugin.compose` Gradle plugin.
  AGP 8.13's lint crashed reading Kotlin 1.9 metadata, which made `lintDebug` fail from a
  clean build; aligning the toolchain fixed it and removed the need to suppress anything.

### Removed
- The gyroscope diagnostics UI, its ViewModel and its sensor collector. These computed
  "insights" and "focus areas" from raw gyroscope magnitude thresholds and were unrelated to
  badminton.
- The dataset-capture CSV export button, which built a correct CSV and then discarded it
  into `Log.d`.

### Fixed
- Recording no longer stops when the screen sleeps. Capture was previously bound to the
  Activity lifecycle, so it ended seconds after the player started playing.
- `distinctUntilChanged()` no longer drops repeated sensor readings — a wrist at rest
  produces exactly those, and discarding them made rest-interval detection impossible.

### Documentation
- Rewrote `README.md`, `docs/architecture.md` and `docs/usage.md`, which described a feature
  set that did not exist. Added `docs/dashboard.md` and `docs/PRODUCT_PLAN.md`.

## [0.1.0] - 2024-05-24
### Added
- Initial Wear OS Pixel Watch 3 project skeleton, Compose UI and core analytics module.
- Unit tests covering classifier, pipeline and session aggregation.
- Documentation and release tooling skeleton.
