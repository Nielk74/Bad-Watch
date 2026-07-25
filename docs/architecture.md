# Bad Watch Architecture

## Overview

Bad Watch records badminton sessions from a Wear OS watch worn on the racket wrist, and
serves the analysis from a self-hosted web dashboard. Capture, classification and storage
all happen on the watch; the server is an optional consumer of finished sessions.

## Design commitments

These are the decisions everything else follows from.

1. **Wrist-only.** No racket sensors, no external hardware. A feature that needs extra
   hardware does not ship.
2. **Racket wrist only.** Shot detection reads the swing, so the non-dominant wrist has no
   usable signal. The app states this in onboarding rather than degrading silently.
   `WristPlacement` exists in the data model so the constraint is explicit in every
   exported session, and so a future footwork-only mode has somewhere to live.
3. **On-device and offline-first.** Sessions are durable on the watch the moment they end.
   Sync is best-effort and entirely optional; there is no account.
4. **One schema.** The sync contract is a set of `@Serializable` Kotlin types in `:core`,
   compiled into both the watch and the server. There is no hand-maintained JSON schema.
5. **Glanceable-first.** The live screen is designed for about half a second of attention.
   Anything that requires reading a sentence belongs in the post-session recap.

## Modules

```
:core     Pure Kotlin/JVM. No Android dependencies, fully unit-tested.
          model/       SensorSample, ShotEvent, Rally, TrainingSession, PlayerProfile
          classifier/  MotionFeatureExtractor, ShotClassifier
          pipeline/    ShotDetectionPipeline (sliding window)
          capture/     SwingSegmenter (labelled training windows)
          insight/     SessionInsightEngine — session observations, evidence-backed
          eval/        ClassifierEvaluator — scores the classifier against ground truth
          session/     SessionRecorder, TrainingSessionAggregator, RallySegmenter
          sync/        SessionExport, SyncEnvelope, SyncResponse — the wire contract

:app      Wear OS application.
          sensors/     FusedSensorCollector (gyro + accel + HR)
          domain/      SessionController — owns the live session at application scope
                       CaptureController — runs labelled data-collection drills
          service/     SessionService — foreground service, survives screen-off
          data/        SessionStore, CaptureStore (file-per-drill), SettingsStore
          sync/        DashboardClient, SyncWorker
          ui/          Compose surfaces
          debug/       adb configuration receiver (debug builds only)

:server   Ktor dashboard. Depends on :core for the contract.
          Application.kt      routes and configuration
          SessionRepository   file-per-session storage
          Analytics           transparent volume and measured-HR aggregation
          SyntheticSessions   fixtures and demo seeding (developer command only)
          resources/static    the dashboard page
```

## Recording path

```
FusedSensorCollector          100 Hz gyro + accel, ~1 Hz heart rate
        │                     monotonic SensorEvent.timestamp → epoch, FIFO batched
        ▼
SessionController             application-scoped; owns state across Activity death
        │
        ▼
SessionRecorder (:core)       ── ShotDetectionPipeline → ShotClassifier → ShotEvent
        │                     ── TrainingSessionAggregator (distinct HR readings + coverage)
        │                     ── RallySegmenter (detected-hit bursts + estimated quiet gaps)
        ▼
SessionStore                  one JSON file per session, atomic write via temp + rename
        │
        ▼
SyncWorker → DashboardClient → POST /api/v1/sessions
```

`SessionService` is a foreground service of type `health`. It does not do the work itself —
it keeps the process alive and publishes an ongoing notification. The state lives in
`SessionController` at application scope, because a session must outlive the Activity: the
watch screen sleeps within seconds of the player putting their wrist down.

## Sensing decisions

The original collector had three defects that made analysis impossible, all now fixed:

| Was | Now | Why it mattered |
| --- | --- | --- |
| `System.currentTimeMillis()` on the delivery thread | `SensorEvent.timestamp` (monotonic nanos) anchored to epoch once | Dispatch jitter was baked into the data, making multi-sensor fusion unreliable |
| `distinctUntilChanged()` | every sample retained | A wrist at rest produces repeated readings; discarding them destroyed rest-interval detection |
| `SENSOR_DELAY_GAME` (~50 Hz), gyro only | 100 Hz, gyro + accel + HR, 200 ms FIFO batching | A stroke lasts 80–150 ms; 50 Hz gives only a handful of samples across the whole swing |

FIFO batching is also the single biggest battery lever in the capture path, and costs
nothing in fidelity because timestamps come from the sensor hub rather than delivery time.

## Shot classification

Currently rule-based, and **uncalibrated against real play**. `MotionFeatureExtractor`
derives peak and average angular velocity, vertical/horizontal component ratios, a pronation
score, directional trend and a stability score; `ShotClassifier` applies thresholds and a
weighted confidence score. Handedness mirrors the pronation axis, since the backhand
signature inverts between left- and right-handed players.

This is a placeholder with the right shape, not a finished classifier. Phase 2 of the
product plan replaces it with a TFLite model trained on labelled swings, keeping the
rule-based path as a fallback.

### Collecting training data

The **Collect data** drill records labelled swings: the player picks a stroke, hits
repetitions, and `SwingSegmenter` cuts a window around each angular-velocity peak.

Segmentation is deliberately independent of `ShotClassifier`. Using the classifier to
find swings would mean the training set only ever contains strokes the heuristics already
recognise, so the model would inherit exactly the blind spots it is meant to remove. The
only assumption `SwingSegmenter` makes is physical — a stroke produces a sharp isolated
peak in |ω| — which holds for every stroke type equally.

Labels come from the player's drill selection, never from inference. Windows are stored raw
and unfiltered so feature engineering stays a decision for the training pipeline in
`tools/` rather than something baked into the watch.

### Measuring the classifier

`ClassifierEvaluator` scores the shipped classifier against collected labelled swings —
`./gradlew :server:evaluateClassifier`, and a card on the dashboard. Until this existed,
"uncalibrated" was an honest admission that gave Phase 2 nothing to beat.

It reports the full confusion matrix and **per-class** recall, not just accuracy. A
drill-collected corpus is always unbalanced (people hit more smashes than backhand drives),
and accuracy on an unbalanced corpus is dominated by the largest class. "Not detected" is
counted as an explicit outcome rather than dropped, so a detector that simply never fires
cannot score well by having nothing counted against it.

The harness immediately found a real defect: `verticalComponentRatio` is built from
`abs(z)`, so it cannot distinguish a smash's downward arc from a clear's upward one. Because
the smash rule was tested first, every clear fast enough to pass the smash threshold was
reported as a smash. Both overhead rules now test the *sign* of the vertical component, and a
swing with no decisive direction falls through rather than being assigned one.

## Detected-play segmentation

Consecutive detected racket-wrist hits less than 4 s apart belong to the same inferred
exchange. Bursts with a single hit are discarded as detector noise. A real one-contact point
exists, and the watch does not see an opponent's or partner's contacts, so these groups are
explicitly **not** authoritative rally boundaries.

This yields useful within-player estimates: detected hits per exchange, the span between the
first and last detected hit, and quiet gaps. Product copy calls these rally bursts or detected
exchanges and labels active time as estimated. See [`SPORT_MODEL.md`](SPORT_MODEL.md) for the
full measurement contract.

## Persistence

**One JSON file per session, on both the watch and the server.** The earlier plan called for
Room; that was over-engineered for the real access pattern. The watch only lists, reads,
marks-synced and deletes — all aggregation happens on the server. A file per session gives
durable storage, the export format and the sync payload in a single representation, with no
annotation processor in the build.

Writes go to a temporary file and are renamed into place, so a crash mid-write cannot leave
a truncated session that fails to parse on next launch. Sync state lives in a sibling marker
file rather than inside the JSON, so an uploaded payload stays byte-identical and re-uploads
are idempotent.

Revisit if on-watch trend queries over hundreds of sessions become a real feature.

## Testing

- `:core` — deterministic JVM tests for feature extraction, classification, rally
  segmentation and the full `SessionRecorder` path. The entire recording pipeline is
  testable without an emulator.
- `:server` — `testApplication` round-trips using `:core`'s own serializers, so a
  watch/server wire-format disagreement fails the build.
- `:app` — storage and UI-mapping logic have JVM tests. The primary home and live-recording
  paths are also checked on a Pixel Watch 4; automated service-lifecycle instrumentation is
  still a gap.

## Known gaps

- The classifier is uncalibrated; stroke labels are provisional.
- Heart rate uses `SensorManager` directly. Distinct optical readings are now timestamped,
  deduplicated and coverage-gated, but Health Services `ExerciseClient` remains the intended
  recording source.
- No battery measurement harness yet, despite battery being a stated hard requirement.
- Release-build dashboard configuration has no UI; only the debug adb receiver exists.
- The Tile and ambient session HUD exist; complications and match-mode ambient UI do not.
- Process death can still lose the in-progress portion of a session; foreground-service
  survival is tested, but checkpoint/recovery is not implemented yet.

See [`PRODUCT_PLAN.md`](PRODUCT_PLAN.md) for how these are sequenced.
