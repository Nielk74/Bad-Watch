# Changelog

## [Unreleased]

### Added
- **Dashboard session detail.** Clicking a session on the dashboard opens a hash-routed
  detail view (`#/session/<id>`): stat strip, a rally timeline (rally bands scaled by shot
  count, per-shot ticks colored by type, tooltips, legend), and the session's shot mix —
  all rendered client-side from the existing `GET /api/v1/sessions/{id}` payload.
- **Wear OS 6 dynamic theming.** The app now takes its palette from the user's watch face
  where the system offers one (`dynamicColorScheme`), falling back to the brand scheme
  elsewhere. Semantic colors (HR zones, shot families, severities) stay fixed — they carry
  meaning and must not drift with the watch face.
- **Shot haptics.** Every detected shot fires a haptic from `SessionController.shots` —
  the flow existed for exactly this and was previously unwired. Haptic-first, as the
  product thesis demands.
- **Always-on ambient HUD.** `AmbientLifecycleObserver` drives a dim, static, burn-in-safe
  rendering of the live HUD (count, HR, duration — no animations, no actions) so the
  session stays glanceable with the wrist down. Recording continues in the foreground
  service as before.
- **Digit-morph shot counter.** On API 31+ the HUD count uses `AnimatedText` with
  variable-font weight interpolation on top of the spring pulse.
- **Watch-face Tile.** A `TileService` (protolayout) shows the last session's
  shots/rallies/duration and the rolling seven-day load, with a Start chip that deep-links
  into the app and begins recording. The tile reads `SessionStore` directly — the system
  binds the service on its own schedule, so pulling in the whole app container would be
  dead weight. Freshness interval: 30 minutes.

### Changed
- **Zero-allocation detection window.** `ShotDetectionPipeline` now keeps its sliding
  window in `SampleWindow`, a pre-allocated ring buffer with a zero-copy list facade,
  instead of calling `ArrayDeque.toList()` on every sample — an allocation per sample at
  100 Hz across three sensors.

## [0.2.0] - 2026-07-25

### Added
- **Classifier evaluation.** `ClassifierEvaluator` scores the shipped rule-based classifier
  against collected labelled swings — `./gradlew :server:evaluateClassifier`, plus a
  dashboard card. Reports per-class recall and the full confusion matrix rather than just
  accuracy, which on an unbalanced drill corpus is dominated by the largest class, and
  counts "not detected" as an explicit outcome so a detector that never fires cannot look
  good by default.
- **Session insights.** `SessionInsightEngine` produces at most three evidence-backed
  observations per session — excessive rest, rally-length decay, cardiac drift, a longest
  rally — derived **only** from rally structure and heart rate. Stroke type is deliberately
  excluded while the classifier remains uncalibrated. The engine stays silent when the data
  is thin, and roughly half its test suite asserts exactly that. Shown on the watch recap
  and on the dashboard, computed by the same `:core` code on both.
- **Labelled data collection.** A "Collect data" drill records swings with a ground-truth
  label: pick a stroke, hit repetitions, save. `SwingSegmenter` cuts windows on
  angular-velocity peaks, deliberately independent of the rule-based classifier so the
  training set does not inherit its blind spots.
- **Training pipeline** in `tools/`: `ingest.py` flattens captures into a feature dataset
  (standard library only), `train.py` fits a baseline with cross-validation grouped by
  device so a player never appears in both train and test.
- Capture upload endpoint (`POST /api/v1/captures`) and dataset-progress summary.
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
  Its heart-rate model is a drifting baseline plus a within-rally ramp that recovers during
  rest; the naive version ratcheted upward every shot, producing a 40+ bpm session rise that
  no human does and making the cardiac-drift insight fire on every seeded session.
- CI running JVM tests, lint and a debug assemble.

### Changed
- **Whole-watch UI rebuilt on Wear Compose Material 3 (1.6.2).** Every screen was rewritten:
  the live HUD is now a two-page pager — a glanceable face (giant spring-animated shot
  count, heart-rate zone ring around the bezel, last-shot badge) with rally/body detail one
  swipe away — primary actions moved into bottom `EdgeButton`s, destructive actions sit
  behind `AlertDialog` confirms, history rows delete via `SwipeToReveal`, and session recaps
  lead with insights followed by rally-structure and shot-mix bars drawn on canvas. New
  "court at night" design system: OLED-black palette with semantic colors (heart-rate zones,
  shot families, insight severities), the M3 numeral type scale for glanceable numbers, and
  expressive motion. Screens are selected by session state as before, now crossfaded via
  `AnimatedContent` inside `AppScaffold`/`ScreenScaffold`. Toolchain: Kotlin 2.2.21,
  Compose BOM 2026.01.01, `androidx.wear.compose` 1.4.1 → 1.6.2 (Material 2 dropped
  entirely).
- `:app` now depends on `:core`. Previously the analytics module was compiled but never
  referenced by the application.
- Sensor timestamps come from the monotonic `SensorEvent.timestamp` rather than wall-clock
  time read on the delivery thread.
- Heart rate is `Float?` rather than `Float` throughout. A session with no readings now
  reports null instead of standing in the 60 bpm resting baseline, which was a quiet lie.
- Persistence uses per-session JSON files rather than the previously documented (and never
  implemented) DataStore.
- Migrated to Kotlin 2.1 with the `org.jetbrains.kotlin.plugin.compose` Gradle plugin, and
  bumped the Compose BOM (2024.05 → 2025.06), Wear Compose and `compileSdk` (34 → 36).
  `lintDebug` was crashing inside `ComposableFlowOperatorDetector` on any file using a Flow
  operator: the 2024 detector could not read Kotlin 2.x metadata. Updating the toolchain
  fixed it properly, with no suppressions. `targetSdk` deliberately stays at 34.

### Removed
- The gyroscope diagnostics UI, its ViewModel and its sensor collector. These computed
  "insights" and "focus areas" from raw gyroscope magnitude thresholds and were unrelated to
  badminton.
- The dataset-capture CSV export button, which built a correct CSV and then discarded it
  into `Log.d`.

### Fixed
- **Fast clears were reported as smashes.** `verticalComponentRatio` is built from `abs(z)`
  and so cannot tell a downward smash from an upward clear; since the smash rule was tested
  first, it swallowed every clear quick enough to pass its threshold. Both overhead rules now
  test the sign of the vertical component, and a swing with no decisive direction falls
  through rather than being assigned one. Found by the new evaluation harness on its first
  run. Regression tests cover both the fast clear and the ambiguous case.
- **Saving a session or drill no longer crashes the app.** Heart rate used `Float.NaN` as
  its "no reading" sentinel, and `kotlinx.serialization` cannot encode NaN — so persisting
  anything recorded before the optical sensor gets a lock killed the process and lost the
  recording. Heart rate is now nullable end to end, `SensorSample` rejects NaN at
  construction, and a serialization test covers the no-heart-rate path.
- **Data-collection drills survive backgrounding.** Drills now run under the same foreground
  service as sessions; previously the process could be killed mid-drill, silently discarding
  every collected swing.
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
