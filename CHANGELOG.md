# Changelog

## [Unreleased]

## [0.3.0] - 2026-07-26

### Product and interface

- Rebuilt the watch around a modern **court at night** hierarchy: one dominant start action,
  glance-first live face, evidence-led recap, compact history, Progress, training hub, and an
  adaptive launcher mark. Wear Compose Material 3 provides round-screen lists, edge actions,
  dialogs, dynamic surfaces, ambient behavior, and accessible semantics.
- Added complete English and French release resources, plural-safe quantities, content
  descriptions, merged row semantics, and large-text/round-screen validation.
- Added player-chosen seven-day session and recorded-minute goals, personal archive records, an
  editable self-reported experience, and a multidimensional like-for-like play pattern. The app
  never guesses a global skill level from wrist motion.
- Added a manual singles/doubles BWF scoreboard: 21-point games, two-point margin capped at 30,
  best of three, service court, interval at 11, change-of-ends prompts, undo, ambient UI, and
  durable process recovery. Points are always player-entered.
- Added a balanced six-corner shadow routine with racket-relative visual/haptic cues, explicit
  return-to-base confirmation, pause/resume/early finish, downtime-safe restoration, and a BWF
  practice library whose cards state what the watch cannot assess.
- Added a rolling seven-day corrected-detected-hit watch-face complication alongside the Start/
  recap Tile. Unusable sessions and reported misses are excluded from the literal detector count.

### Session trust and physiology

- Replaced raw `SensorManager` heart-rate sampling with Health Services `ExerciseClient` using the
  badminton exercise type. Capability, permission denial, stale/out-of-order readings, another
  app's active workout, and motion-only fallback are all explicit and tested.
- Added a durable active-session journal. A killed process restores the original session ID/start,
  resumes the health foreground service, excludes unobserved downtime, and marks recording quality
  partial instead of creating a second session or pretending coverage was continuous.
- Required successful gyro registration, retried unbatched delivery when FIFO registration fails,
  and degraded optional acceleration independently. A watch without the required sensor now fails
  visibly instead of running a timer that records no motion.
- Added sourced heart-rate profile settings. Adult age estimates maximum HR with `208 − 0.7 × age`;
  an exact maximum overrides it. Zones require a sourced maximum; HR reserve and HRR-minutes require
  both sourced resting and maximum values. Legacy numeric defaults authorize nothing.
- Added distinct heart-rate traces/coverage, coverage-gated HRR-minutes, approximately one-minute
  post-final-burst optical HR change, and transparent session-RPE (`reviewed minutes × reported
  RPE`). Removed all user-facing recovery, fatigue, readiness, and generic effort scores; legacy
  schema fields decode but current producers write zero.

### Diary, corrections, and analysis

- Added a typed optional post-session diary: activity/comparison context, opponent, partner, hall,
  goal, completion, recording quality, RPE, reviewed soreness, notes, equipment, and conditions.
  Sensor coverage and completion are deliberately separate questions.
- Added append-only correction revisions for edge trim, false detected events, and reported missed
  hits. Raw events and original summaries remain immutable with actor/time/reason provenance.
- Added `ReviewedSessionAnalysis`, rebuilding the reviewed time window, corrected detected total,
  summary, HR coverage, and inferred exchanges. The reviewed projection now drives recap, history,
  Progress, personal records, Tile, complication, baselines, insights, server analytics, browser
  detail, and CSV. Reported misses remain a separate untimed value and never inflate the detected
  headline or an inferred exchange.
- Restricted personal insight baselines to earlier, usable, explicitly like-for-like sessions.
  Insight copy describes observed exchange shortening and HR rise without diagnosing fatigue,
  tactics, errors, fitness, or causation.
- Excluded sessions marked `Unusable` from weekly/progress/complication aggregates while retaining
  them in history and the raw audit trail.

### Data ownership, sync, and dashboard

- Added on-watch release configuration and connection testing for the self-hosted dashboard, with
  an explicit plain-HTTP warning. The browser dashboard now supports responsive reviewed charts,
  raw-vs-reviewed session detail, typed diary editing, filters, and accessible table fallbacks.
- Added deterministic lossless JSON backup, reviewed spreadsheet CSV, and fully validate-before-
  write merge/restore. Archives include raw motion only when recording-time self-hosted-training
  consent, participant ID, and collection protocol are present.
- Hardened watch persistence with shared durable temp-write/rename, valid orphan-temp recovery,
  corrupt-file quarantine, and serialized capture finalization so samples cannot race save/discard.
- Added payload-fingerprinted accepted/rejected sync markers. An unchanged server rejection is kept
  with reason/time and stops retrying; editing clears it; a stale in-flight response cannot tag the
  replacement; later acceptance wins. Capture and session acknowledgements share the same tested
  contract.
- Added acknowledged diary ancestry to the monotonic revision. Multiple offline watch edits still
  merge when based on the current server head, while a stale branch can no longer leapfrog and
  overwrite an intervening browser edit.
- Disabled HTTP redirects in bearer-authenticated watch requests so a token cannot follow a server
  redirect to another origin.
- Made the server listen on `127.0.0.1` by default and require a bearer token for any non-loopback
  bind. Authenticated data APIs emit `Cache-Control: no-store`; IDs, schema versions, diary values,
  archives, and training-consent metadata are validated before persistence.

### Detection research

- Froze participant, device, profile, consent, protocol, watch metadata, and app version before the
  first raw sample of a Detection Lab drill. Enabling sharing later never releases older local-only
  captures.
- Grouped training/evaluation by participant rather than window or device, generated versioned
  model/evaluation artifacts, and added an offline acceptance gate. Training can produce evidence
  but cannot silently declare a model deployable.
- Documented representative real-play, non-badminton negative, subgroup, calibration, on-device,
  and battery gates required before provisional stroke labels can become product claims.

### Platform, validation, and delivery

- Updated `compileSdk` and `targetSdk` to 36 with Android 16 `READ_HEART_RATE`, legacy
  `BODY_SENSORS` compatibility, health-FGS prerequisites, and denial-path behavior.
- Expanded CI and tag builds to run Python tooling tests/compilation, every JVM/app unit test,
  strict lint, and both debug and release assemblies. Release publication verifies package ID,
  semantic version, APK signatures, and SHA-256 checksums.
- Added a real-device endurance probe that starts through the production Tile/Activity path,
  samples service/battery state without holding the display awake, stops through the visible UI,
  and requires exactly one duration-correct persisted session.
- Added a process-death probe that proves stable session identity, resumed motion collection,
  explicit `Partial` quality, and exactly one saved export after forced app termination.
- Replaced the speculative product/UI roadmaps with a completed product contract, measurement
  vocabulary, current architecture, operating guides, and explicit research/exclusion decisions.

### Fixed

- Sessions no longer disappear, duplicate, silently stop at screen-off, or lose their identity
  after normal Wear OS process recreation.
- Capture consent and metadata can no longer change between the first sample and export.
- Capture completion now waits for the collector before snapshotting, removing sample/save races.
- Server/client analytics can no longer mix raw inferred exchanges with reviewed hit totals.
- Reported missed hits can no longer masquerade as detected events in primary totals.
- `Unusable` recordings can no longer teach progress or personal-history baselines.
- A rejected upload can no longer loop forever or be displayed as merely queued.
- Bearer credentials can no longer leak through automatic redirect following.
- Match and shadow actions no longer appear before their durable checkpoint succeeds; failed
  writes keep the last restorable state visible with a localized retry warning.

## [0.2.0] - 2026-07-25

The first end-to-end prototype connected real IMU capture, a health foreground service, atomic
session JSON, heuristic hit detection, inferred exchange segmentation, labelled drill capture,
grouped evaluation tooling, a Ktor dashboard, offline sync, a Wear Material 3 live/recap UI, Tile,
and evidence-carrying session observations. It also removed fake gyroscope diagnostics and fixed
NaN serialization, at-rest sample loss, screen-off recording loss, and clear/smash direction
confusion.

v0.3 supersedes its provisional load wording, raw-primary analytics, legacy HR path, incomplete
permission model, and unfinished roadmap.

## [0.1.0] - 2024-05-24

- Initial Wear OS project skeleton, Compose UI, core analytics module, and basic tests.
