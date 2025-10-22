# Bad Watch Architecture

## Overview

Bad Watch is a Wear OS 5.1 application tailored for Pixel Watch 3 badminton training. It tracks heart-rate and gyroscope streams to identify shots in real time, surfaces coaching insights, and stores rich session analytics directly on the watch. The stack uses Jetpack Compose for Wear OS, a foreground service for sensor subscriptions, and a rule-based motion classifier encapsulated in a reusable core module.

## Goals

- Deliver reliable on-device shot detection without requiring network access.
- Provide at-a-glance training HUD, rich post-session statistics, and optional haptic/audio cues.
- Keep CPU/battery usage low by batching sensor reads and processing on coroutines.
- Ship a thoroughly tested core analytics engine with clear documentation and semantically versioned releases.

## Feature Set

- Real-time detection of smash, drop, clear, drive, and backhand drive shots using gyroscope vectors, derived swing angles, and heart-rate deltas.
- Live training screen showing current heart-rate zone, streak counters, and recent shot classification.
- Session recording with automatic warm-up detection, effort scoring, rally segmentation, and REST intervals derived from heart-rate recovery.
- Insights dashboard summarizing peak/avg heart-rate, total swings, shot distribution, rally durations, and fatigue indicators.
- Optional Coach Mode that provides haptic feedback when form deteriorates (e.g., repeated mishits or elevated fatigue score).
- Session history retained locally with the option to export as JSON for companion phone sync (future extension).

## Module Structure

```
bad-watch
├── app/           # Wear OS Compose application, services, UI, DI
├── core/          # Pure Kotlin analytics, motion classification, data contracts
├── docs/          # Architecture, manuals, changelog
└── tooling/       # Version bump scripts and static analysis configuration
```

- `core` exposes a `ShotClassifier`, session aggregators, and contracts used by both the UI and tests. It is completely platform-independent and covered with unit tests.
- `app` depends on `core`, provides sensor integrations, Compose UI, navigation, haptics, and persistence (DataStore).

## Data Flow

1. `SensorService` subscribes to `SensorManager` for heart-rate (`TYPE_HEART_RATE`) and gyroscope (`TYPE_GYROSCOPE`) events at a moderate sampling rate (50 Hz cap).
2. Samples enter a `SensorPipeline` that smooths noise (windowed median filter) and aggregates into 250 ms windows.
3. Windows feed into `ShotClassifier`, which produces `ShotEvent`s with confidence scores and fatigue metrics.
4. `TrainingSessionController` updates live state flows consumed by the UI and persists session summaries via `SessionRepository`.
5. Compose UI observes state and renders cards/chips optimized for round displays.

## Shot Classification

- Feature extraction: angular velocity magnitude, orientation change, wrist pronation estimate, and heart-rate delta over the last 5 s.
- Rule-based classification calibrated for badminton:
  - **Smash:** peak ω > 6 rad/s, downward vector, HR uptick > 3 BPM.
  - **Clear:** ω 4–6 rad/s, upward vector, wrist supination.
  - **Drop:** ω 2–4 rad/s with rapid deceleration and minimal HR change.
  - **Drive:** ω 3–5 rad/s primarily on horizontal plane.
  - **Backhand Drive:** ω 2–4 rad/s with pronation inversion.
- Confidence scoring uses weighted features with fallback to `Unknown`.
- Fatigue score derived from HR recovery slope, variance in swing speed, and detection jitter.

## Persistence & Versioning

- `SessionRepository` uses Proto DataStore to persist historical sessions (`badwatch_sessions.pb`).
- Semantic versioning tracked via `VERSION.md`, mirrored in `app/build.gradle.kts` (`versionName`) and changelog tags.
- Release automation script (`tooling/tag_release.sh`) validates tests and bumps version metadata.

## Testing Strategy

- `core` module: deterministic unit tests for feature extraction, classification, fatigue scoring, and session aggregation using fixture sensor streams.
- `app` module: Robolectric-based ViewModel tests for `TrainingSessionViewModel`; instrumentation tests stub sensor pipeline.
- Snapshot tests for Compose surfaces executed with `WearComposeTestRule`.
- End-to-end integration test scaffolding that replays captured sensor traces to validate session reports.

## Documentation

- README for setup, build, and deployment instructions.
- `docs/usage.md` user guide covering training modes and exporting data.
- CHANGELOG tracking releases.
- API docs generated with Dokka (invoked via `./gradlew dokkaHtml`).

## Future Extensions

- ML-based classifier utilizing TinyML (TensorFlow Lite) once a labeled dataset is available.
- Companion phone sync via Tiles/Complication or Health Services integration.
- Cloud backup, coach sharing, and stroke quality heatmaps.
- TODO: Sessions list + single-tap export to external API
  - Show a chronological list of saved sessions with basic stats.
  - Add an Export button that packages selected/all sessions and sends to a configurable HTTPS endpoint.
  - Use a background worker with retry/backoff; include simple auth (token) and request signing if needed.
  - Payload defaults to JSON; optional CSV transform for tabular pipelines.
  - Confirm delivery with lightweight status UI and local audit log.
