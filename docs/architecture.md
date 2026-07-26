# Bad Watch architecture

## Product boundary

Bad Watch is a standalone Wear OS session recorder for the racket wrist. Capture, provisional
detection, reviewed session analysis, and durable storage work on the watch without an account or
network. The optional Ktor server receives completed records and provides a larger review,
portability, and research surface.

The architecture follows five rules:

1. **Racket wrist only.** A one-wrist detector cannot observe the shuttle, opponent, partner,
   score, or court position. Unsupported facts are reported by the player or not shown.
2. **Offline first.** A network failure cannot prevent, stop, or erase a recording.
3. **One contract.** `:core` owns the serializable session, capture, diary, correction, and sync
   types used by both watch and server.
4. **Raw evidence is immutable.** Review creates deterministic projections and append-only
   provenance; it does not rewrite the original detector output.
5. **Glanceable first.** The live UI favors one large fact and keeps explanation for review.

The measurement vocabulary and prohibited claims are defined in
[`SPORT_MODEL.md`](SPORT_MODEL.md).

## Shipped modules

```text
:core    Pure Kotlin/JVM.
         model/       sensor, shot, session, HR-profile, and match types
         classifier/  rule-based provisional stroke classifier
         pipeline/    zero-copy sliding detection window
         session/     recorder, aggregation, and detected-exchange segmentation
         sync/        wire schema, diary, corrections, reviewed analysis
         insight/     evidence-backed observations and like-for-like baselines
         physiology/  descriptive, coverage-gated HR calculations
         progress/    context-specific play pattern
         match/       manual BWF scoring reducer and append-only action log
         training/    shadow reducer and sourced practice library

:app     Wear OS application.
         sensors/     100 Hz gyro plus optional acceleration
         health/      capability-checked Health Services ExerciseClient boundary
         service/     health foreground service and ongoing notification
         domain/      application-scoped session, capture, match, and shadow controllers
         data/        atomic stores, active-session journal, settings, sync markers
         sync/        release dashboard setup, connection check, WorkManager upload
         ui/          Wear Compose UI, ambient variants, English/French presentation
         tile/        start/last-session/seven-day Tile
         complication seven-day corrected detected-hit complication

:server  Ktor API and browser dashboard.
         file-per-record repositories, reviewed analytics, diary updates,
         authenticated backup/CSV/restore, and consent-filtered capture research APIs

tools/   Consent-aware capture ingestion and participant-grouped offline model evaluation.
isolate/ Wear/emulator inspection helpers.
tooling/ Release helper and real-device session/endurance probe.
```

The app compiles and targets API 36. Heart-rate permission is optional; the manifest-only
high-sampling-rate sensor permission is what allows motion-only recording to continue under the
`health` foreground-service type when HR is denied.

## Recording path

```text
Health Services ExerciseClient      optical HEART_RATE_BPM when supported and permitted
        |                           source duration-from-boot converted to epoch once
        v
FusedSensorCollector                100 Hz gyro + optional linear/fallback accelerometer
        |                           monotonic SensorEvent.timestamp, 200 ms FIFO batching
        |                           carries only the latest fresh optical-HR observation
        v
SessionController                   application-scoped command owner
        +--> ActiveSessionJournal   atomic checkpoint at start and about every 12 seconds
        v
SessionRecorder (:core)             detector -> provisional ShotEvent
        |                           distinct HR trace + coverage
        |                           inferred wearer-hit exchanges
        v
SessionStore                        one atomic JSON file per completed session
        |
        +--> recap/history/progress/Tile/complication
        +--> SyncWorker -> DashboardClient -> POST /api/v1/sessions
```

`FusedSensorCollector` requires a gyroscope. Linear acceleration is preferred, ordinary
acceleration is the fallback, and gyro-only recording remains possible if neither registers.
Sensor callbacks retain the hardware timestamp; repeated at-rest readings are not discarded.
Optical HR is not sampled at 100 Hz. Health Services owns its cadence, and the aggregator retains
each distinct source-timestamped reading once.

### Health Services lifecycle

For a real session, `HealthServicesExerciseBackend` requests only `HEART_RATE_BPM` for
`ExerciseType.BADMINTON`; GPS and auto-pause are disabled and calories are not requested. The app:

- checks exercise and data-type capabilities before starting;
- installs the callback before opening a new exercise;
- never supersedes an exercise owned by another app;
- reattaches when this app already owns a compatible badminton exercise after a restart;
- converts each source `timeDurationFromBoot` rather than using callback-arrival time;
- rejects replayed/out-of-order points and stops presenting a reading after 15 seconds;
- continues a truthful motion-only session on denial, unsupported hardware, service absence, or
  exercise contention.

`ExerciseHeartRateSessionTest` covers the platform-independent lifecycle decisions. Final device
permission and OEM behavior remain part of the release evidence gate in
[`device-validation.md`](device-validation.md).

## Lifecycle and durability

`SessionService` owns the foreground lifetime; `SessionController` owns the in-memory recorder.
Application scope survives Activity recreation but is not treated as durable. The active journal
contains the stable session ID/start time, accumulator state, and only the detector's bounded edge.

On a fresh or `START_STICKY` service start, recovery is resolved before Health Services opens:

- a valid checkpoint resumes the same identity and marks the eventual export `Partial`;
- unobserved process downtime is not presented as measured sensor coverage;
- a crash after the final session rename is reconciled as already saved;
- concurrent duplicate Stop commands return the same durable export;
- corrupt or incompatible journals are quarantined rather than retried forever.

The same command-before-publish rule applies to manual matches and shadow routines: each explicit
action is atomically persisted, then made visible. Their durable documents restore after process
death and surface corruption instead of silently resetting.

## Raw, reported, and reviewed session data

`SessionExport` keeps three layers separate:

1. immutable recorded/model output (`session`, shots, HR trace, original exchange profile);
2. player-reported diary context and post-session report;
3. append-only trim and hit-correction revisions with actor/time/reason provenance.

`ReviewedSessionAnalysis` applies the effective edge trim, removes only resolved false-event IDs,
rebuilds timestamp-backed detected exchanges, and recalculates the reviewed summary/HR coverage.
Reported missed hits remain an untimed count and never enter exchange timing or provisional stroke
mix.

The reviewed projection is the source for primary recap values, history, progress, insights, Tile,
complication, server cards/detail, and reviewed CSV. The raw export remains available in the
authenticated detail envelope and lossless archive for audit. Any separately computed recap metric
must also be regression-tested against trim and false-hit corrections before it can be included in
the v0.3 completion claim.

## Detection and research boundary

The shipped detector is rule-based and uncalibrated against representative match play. It emits
candidate **detected hits** and provisional stroke labels; its confidence is an internal ranking,
not a calibrated probability. `RallySegmenter` groups two or more wearer-hit detections separated
by no more than four seconds. These are detected exchanges, not complete rallies, and their
first-to-last spans are estimates rather than true playing time.

The Detection Lab records player-selected labelled windows independently of the classifier.
Recording-time consent, pseudonymous participant ID, and protocol version determine whether a raw
capture may sync. The Python pipeline evaluates offline candidates with participant-grouped folds
and always keeps deployment blocked until the model gates in `PRODUCT_PLAN.md` are met. There is no
TFLite, microphone, automatic scoring, or movement-quality model in v0.3.

## Persistence and sync

Watch and server use one JSON file per record and fsync a sibling temporary file before replacing
the destination. Session/capture stores recover valid orphan temporary files, uniquely quarantine
invalid payloads without overwriting earlier evidence, and leave transiently unreadable bytes in
place. The active-session journal also quarantines corrupt checkpoints. The
watch's sync marker is a sibling document containing payload identity plus accepted/rejected state,
so an edit becomes a new upload candidate while an unchanged rejected record does not retry
forever.

`SyncWorker` uploads eligible captures and sessions independently with network constraints and
backoff. `DashboardClient` does not follow redirects with the bearer credential. The server shares
`:core` serializers, isolates deterministic upload failures by record ID, defaults to loopback,
and requires a token for a non-loopback bind. Generic transport/storage failures stay retryable.
Recorded evidence is immutable for a stable ID; a monotonic diary revision plus its acknowledged
base provides optimistic concurrency, while correction histories merge only by append-only
prefix. Stale uploads therefore cannot erase a newer diary and divergent branches become visible
conflicts rather than last-writer-wins data loss. Deployment and TLS details are in
[`dashboard.md`](dashboard.md).

## Verification boundary

- `:core` has deterministic tests for detection, recording, reviewed analysis, insights,
  physiology, progress, scoring, shadow, and serialization.
- `:app` JVM tests cover atomic stores, recovery, command ordering, Health Services decisions,
  sync state, and complication aggregation.
- `:server` tests cover authenticated routes, safe binding, reviewed analytics, diary mutation,
  consent, and deterministic portability.
- Python tests cover ingestion and the non-deploying offline acceptance gate.

Compilation and JVM tests do not prove a three-hour Wear lifecycle, OEM HR behavior, ambient
legibility, TalkBack, enlarged text, or battery drain. Those remain explicit device-evidence slots
in [`device-validation.md`](device-validation.md); none should be marked complete from a short
charged smoke run.
