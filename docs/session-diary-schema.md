# Session diary schema

Bad Watch stores three different kinds of truth in one `SessionExport`, without allowing
one to overwrite another:

1. **Recorded and model output** — `session`, its immutable `ShotEvent` list, heart-rate
   trace, summary, and the original `rallyProfile`.
2. **Player/coach report** — structured `context` and `report` fields.
3. **Review history** — append-only `corrections` that produce transparent effective
   metrics and a rebuilt reviewed analysis while leaving item 1 intact.

The contract lives in `:core` under `com.badwatch.core.sync`, so watch, tools, and server
compile against the same serializers.

## Compatibility

The diary fields are an additive change to schema 1. Every new field has a truthful default,
so previously stored JSON decodes as:

- `ActivityMode.Unspecified`, not free play;
- completion unreported and recording quality unreviewed;
- empty equipment and conditions snapshots, with draft explicitly unreported;
- no post-session report;
- no corrections;
- diary revision `0`, the legacy optimistic-concurrency baseline;
- no acknowledged base revision (`diaryBaseRevision = null`).

For that reason `SessionExport.SCHEMA_VERSION` remains `1`. A schema bump is reserved for a
change that an older reader could not safely ignore or a newer reader could not safely
default. `SessionDiaryTest.schemaOnePayloadWithoutDiaryFields...` is the migration gate.

The older `notes: Map<String, String>` remains readable and writable as extension metadata.
New product features must use the typed context/report fields rather than inventing keys in
that map. Legacy keys are not silently promoted because their source and meaning are not
reliable enough to infer.

The schema-1 `TrainingSummary.recoveryScore`, `fatigueScore`, and `effortScore` fields are also
retained for decoding only. Existing non-zero values round-trip unchanged, but they have no
validated definition and are not promoted into diary or analytics truth. Current recorders and
synthetic fixtures write zero; reported RPE and named heart-rate metrics are separate fields.

`SessionExport.diaryRevision` versions the complete `context`/`report` document without changing
the schema number. `diaryBaseRevision` records the server revision from which the current offline
branch began. A local or browser diary save increments the revision exactly once; later offline
edits preserve the same base, and correction-only edits change neither value. After acceptance,
the watch atomically normalizes `base = revision` before fingerprinting the acknowledged payload.
Legacy revision-zero/null-base payloads can initialize an empty legacy diary, but cannot erase a
populated one.

## Reported context

`SessionContext` contains:

- activity mode: singles match, doubles match, conditioned game, drill, shadow, free play,
  conditioning, or unspecified;
- optional comparison tag, opponent, partner, hall, and session goal;
- whether the planned session was completed or stopped early;
- whether the recording was reviewed as complete, partial, or unusable;
- an equipment snapshot: racket, string, string tension in pounds, and shoes;
- a conditions snapshot: shuttle brand/model, reported speed grade, temperature in Celsius,
  and reported draft level.

Completion and recording quality are separate. A player may complete a match while the watch
records only part of it, or stop a drill early while the recording itself is complete.

`comparisonTag` is a short stable identifier for a protocol within a broad activity mode,
for example `rear-court-multishuttle`. It is not a prose note. Matches and free play may be
compared by mode alone. Conditioned games, drills, shadow routines, and conditioning need a
comparison tag before they are eligible for a personal baseline. Unspecified sessions are
grouped for display but never treated as comparable evidence.

## Post-session report

`PostSessionReport` stores:

- optional reported perceived exertion (`rpe`) on an integer 0–10 scale;
- zero or more soreness reports, each with body area, optional side, and 0–10 severity;
- whether the soreness question was explicitly reviewed, including a reviewed empty answer;
- an optional free-text diary note.

Missing values mean **not reported**. They must never be rendered as RPE 0 or “no soreness.”
These fields are subjective reports, not conclusions drawn from heart rate or motion.

Opponent, partner, hall, health, and free-text fields can identify a person or disclose
health information. Operators should set `BADWATCH_TOKEN`, use HTTPS at the reverse proxy,
and limit access to raw session files and backups.

### Validation and unknown values

Optional strings use `null` for not reported; blank values from the dashboard are normalized
to `null`. `DraftLevel.Unreported` is distinct from the player's explicit `None`. Shuttle
speed remains a bounded string because feather speed numbers and nylon slow/medium/fast grades
are both legitimate; the server does not pretend they are interchangeable.

The shared contract rejects values outside these bounds before storage:

| Field | Bound |
| --- | --- |
| Comparison tag | 64 characters |
| Opponent / partner | 120 characters each |
| Hall | 160 characters |
| Goal | 280 characters |
| Diary notes | 2,000 characters |
| Racket / string / shoes / shuttle brand | 120 characters each |
| Shuttle speed label | 40 characters |
| String tension | 10–50 lb when reported |
| Temperature | -30–60 °C when reported |
| RPE and soreness severity | integer 0–10 |

## Non-destructive corrections

`SessionCorrections` contains two ordered revision histories:

- `HitCorrectionRevision` is a complete snapshot of false-hit event IDs and the reported
  number of missed hits.
- `TrimCorrectionRevision` is a complete snapshot of milliseconds removed from the raw start
  and raw end.

Each revision includes a stable revision ID, actor category, recorded timestamp, and optional
reason. Array order is authoritative and the last revision of each kind is current. To clear
an edit, append an empty/zero revision; do not delete history. Timestamps are provenance, not
ordering, because client clocks can differ.

False hits reference immutable raw `ShotEvent.id` values. Unknown IDs stay visible in
`EffectiveSessionMetrics.unknownFalseHitIds` for audit but do not change totals. Duplicate IDs
count once. A missed-hit correction is a count only: because no event timestamp or motion
window exists, it cannot alter rallies, stroke distribution, active time, or heart-rate-at-hit
metrics.

Trim offsets are bounded to the raw recording. Start trim is applied first; end trim is then
bounded to the remaining duration. An overlong edit therefore yields a zero-duration window,
never negative time or invented time outside the recording.

The deterministic helpers expose the distinction:

```text
corrected detected hits
  = raw ShotEvents inside the effective time window
  - valid false-hit IDs inside that window

effective hit count
  = corrected detected hits
  + reported missed-hit count
```

Only the first term is composed of detector events. It is therefore the primary count on the
watch, Tile, complication, progress, and dashboard. The combined number may appear only as an
explicitly labelled effective/reported audit field; it must never be called detected hits.

`session`, its summary, and the original `rallyProfile` remain immutable evidence.
`SessionExport.reviewedAnalysis()` creates a deterministic projection instead: it applies the
bounded time window, removes valid false-event IDs, filters the HR trace, rebuilds the summary,
and re-segments exchanges from surviving timestamped detector events. Reported misses cannot
enter that projection because no event time or provisional type exists for them. This reviewed
projection drives every current primary analysis surface while raw values remain available in
the detail audit and backup.

## Server analytics and filtering

Each dashboard session card exposes its structured context, post-session report, canonical
comparison key, correction revision count, `EffectiveSessionMetrics`, and reviewed exchange
summary. The detail endpoint returns reviewed and raw views side by side. Compatibility fields
remain decodable, but current cards and aggregates use the reviewed projection.

`Analytics.build(sessions, filter)` supports activity mode, comparison tag, completion, and
recording-quality filters. Comparison groups are computed over the complete input corpus so
a client can switch groups even while displaying a filtered aggregate. Insight baselines use
only earlier sessions with an eligible, identical comparison key.

The authenticated `GET /api/v1/dashboard` endpoint accepts repeatable, case-insensitive query
parameters (comma-separated values also work):

```text
?activityMode=SinglesMatch
&activityMode=DoublesMatch
&completion=Completed
&recordingQuality=Complete
&comparisonTag=tuesday-league
```

An unknown enum value returns HTTP 400 with the supported values. The response echoes the
canonical `appliedFilter` and always includes the available `comparisonGroups`.

## Web diary updates

The authenticated session detail page edits the diary through
`PUT /api/v1/sessions/{id}/diary`. This is a complete, typed diary document containing only
activity, comparison tag, opponent, partner, hall, goal, completion, recording quality, RPE,
soreness-reviewed state, notes, equipment, and conditions. A successful edit marks the diary
`Reviewed` and returns the complete updated `SessionExport`.

The request includes the `baseDiaryRevision` read with the form. The server changes the record
only when that revision still matches, increments it atomically, and returns HTTP 409 when another
watch/browser edit won the race. The browser then reloads instead of silently replacing newer
work. The server replaces only `context` and `report`, preserves any existing structured soreness
entries, and writes through the repository's durable atomic path. The raw
`session`, original `rallyProfile`, legacy extension metadata, profile, and append-only
`corrections` are not accepted from this endpoint and cannot be overwritten by the form.
Malformed JSON, unknown enum values, and out-of-range or overlong fields return HTTP 400 before
the repository is touched. A request without the configured bearer token returns HTTP 401.

Watch uploads and archive restores use the same merge rules. Recorded evidence is immutable for a
stable session ID. A differing higher diary is accepted only when its base names the server's
current revision; a stale branch cannot become authoritative merely by accumulating a larger
number. Correction logs merge only when one is an exact prefix of the other. Divergent diary
branches or correction branches are explicit conflicts, never last-writer-wins replacements.

## Sync acknowledgement state

The watch keeps delivery state beside, not inside, the immutable payload. Accepted and rejected
markers contain a fingerprint of the exact JSON bytes. This has three consequences:

- a server rejection is retained with reason and time and the unchanged record is not retried
  forever;
- editing the diary/corrections changes the payload, clears old delivery state, and creates a new
  upload candidate;
- an acknowledgement from an older in-flight request cannot mark the edited replacement accepted
  or rejected.

An accepted acknowledgement also makes the local diary head self-based (`base = revision`) before
the accepted marker is written. A crash between those two atomic writes leaves the safe normalized
payload pending; its idempotent retry converges without losing an edit.

The upload API isolates deterministic problems per ID: invalid records, incompatible envelope
schemas, immutable-ID collisions, and divergent edit histories receive a payload-specific
rejection while independent records in the same batch continue. Repository and transport I/O
failures remain HTTP failures so WorkManager can retry them; they are never mislabeled as a
permanent player-data rejection.

Acceptance wins if a malformed server response names the same ID in both maps. Historical plain
`.synced` markers remain readable. Capture records use the same marker contract.
