# Bad Watch product plan — v0.3 completion record

**Status:** implementation complete for the v0.3 product contract. Platform surfaces and exact
existing-task Tile launch have scoped retained hardware evidence. Final-APK device retests cover
start/recover/save, hour-plus Summary and History at normal/`1.30` text, and the recovered Summary
notice/composition. Final-build Home, Progress, Tile rendering/gap marker, recovered-History
marker, and Live details were not physically recaptured; those paths have automated regression
coverage only. The definitive powered 180-minute screen-off lifecycle rerun is still in progress:
`FINAL_ENDURANCE_PENDING`. Battery drain remains deliberately unclaimed because this lifecycle
gate is powered.

**Updated:** 2026-07-26

**Platform:** standalone Wear OS app plus an optional self-hosted web dashboard

This document closes the original speculative roadmap. Every proposal in that roadmap now has
one of three explicit outcomes:

- **Delivered** when it makes a trustworthy session product better today.
- **Research-gated** when real-player evidence is required before a user-facing claim is safe.
- **Excluded** when one racket-wrist watch cannot observe it, the claim would be misleading, or
  the cost would pull the product away from its purpose.

There are no unchecked implementation promises in this plan. Future work begins from measured
player need and passes the gates below; it does not inherit entitlement from the old wish list.

---

## 1. Product contract

Bad Watch is a fast, private badminton session companion for the racket wrist. It should help a
player answer four questions without pretending to be an electronic coach:

1. **Did my whole session record?**
2. **What did the watch actually detect, and how can I correct it?**
3. **How did this comparable session differ from my own recent play?**
4. **Can I keep, inspect, and export the evidence myself?**

The watch is the primary product. It works offline, without an account or subscription. The
optional dashboard provides room for detailed review, editing, JSON backup, CSV export, and
model-research tooling; it is not required to record or understand a session.

### The five non-negotiables

1. **One tap to play.** The home screen, Tile, and ongoing flow put recording ahead of setup.
2. **Glanceable in motion.** Large numerals, a single visual hierarchy, ambient support, and
   optional—not mandatory—detected-hit haptics.
3. **Evidence before adjectives.** Values are named measured, detected, estimated, reported, or
   inferred. An insight cites its evidence or stays silent.
4. **Corrections propagate everywhere.** Reviewed detected hits, time window, and rebuilt
   exchanges feed recap, history, progress, Tile, complication, dashboard, insights, and CSV.
   Reported missed hits remain a separate untimed number.
5. **Raw evidence remains recoverable.** Reviews append provenance; they never rewrite the raw
   recording. Persistence and sync failures must be visible and retry-safe.

### What the app never claims

A single watch does not see shuttle contact, the opponent, partner, point winner, court position,
knee alignment, grip, tactics, pain, or technique cause. Bad Watch therefore does not claim:

- true rally length, exact active playing time, shuttle speed, landing position, or shot quality;
- automatic scoring, serve order, errors, winners, pressure, or momentum;
- a global beginner/intermediate/advanced level inferred from wrist movement;
- fatigue, recovery, readiness, calorie accuracy, tissue load, injury risk, or a safe workload;
- personalized heart-rate zones without a sourced maximum heart rate, or heart-rate reserve
  without both sourced resting and maximum values.

These boundaries are part of the product, not disclaimers added after the interface is designed.
The canonical vocabulary and formulas live in [SPORT_MODEL.md](SPORT_MODEL.md).

---

## 2. Research translated into product decisions

### Badminton technique and common practice themes

The BWF Level 1 coaching material organizes movement around start, approach, hit, and recover;
it also teaches split-step timing, balanced lunges, adaptable grip, and side-on overhead
preparation. Those are useful themes for a player-selected practice library. They are not things
a wrist watch can personally grade.

**Decision:** ship sourced general practice cards and a six-corner shadow prompt. Every card says
what the player is practising and what the watch does not assess. The shadow routine measures only
cue and player-confirmation timestamps. No Footwork Index, automatic split-step timing, lunge
quality score, or corrective technique diagnosis is shown.

Sources: [BWF Coach Education Level 1](https://development.bwfbadminton.com/coaches/level-1) and
the [BWF Level 1 manual](https://badminton.lv/faili/bwf_coach_education_coaches_manual_l1-2nd-edition-midres.pdf).

### Recognizing a player's level

Research can distinguish groups under controlled protocols, but the sensor setup matters. A
40-player study found useful temporal and acceleration features, while later novice/experienced
work used a 17-IMU whole-body system and analysed seven body segments. Video footwork studies use
information a wrist does not contain.
None validates a global level badge from this product's single racket-wrist stream.

**Decision:** keep two truths separate:

- an editable **self-reported experience** field; and
- a multidimensional **play pattern** after at least five inference-eligible, like-for-like
  sessions across at least three days.

The play pattern reports medians such as corrected detected hits per minute, estimated active
share, detected hits per burst, and heart-rate reserve only when authorized. It never converts
them into “good,” “advanced,” or a single score. Different matches and drills are not ranked
against each other.

Sources: [temporal/acceleration study](https://pubmed.ncbi.nlm.nih.gov/32546052/),
[multi-segment 17-IMU study](https://doi.org/10.1186/s13102-025-01163-w), and
[video footwork study](https://doi.org/10.3389/fspor.2026.1753118).

### Match demands, exertion, and injury language

Badminton work/rest findings vary by discipline and protocol, so an untrimmed club practice
should not be judged against an elite match average. Sports-load guidance also calls for external
work, internal response, context, and player report to be considered together. Injury literature
is too heterogeneous for a personal risk predictor, and acute:chronic workload ratios do not
justify universal danger bands.

**Decision:** compare a player primarily with their own earlier, inference-eligible, like-for-like
sessions.
Keep recorded duration, detected volume, estimated exchange structure, measured optical HR,
reported RPE, soreness, completion, and context as separate evidence. Display transparent
session-RPE (`reviewed minutes × reported RPE`) and HRR-minutes only when their inputs exist. Do
not collapse them into readiness or injury advice.

Sources: [temporal-structure review](https://doi.org/10.3389/fspor.2025.1466778),
[IOC load consensus](https://pubmed.ncbi.nlm.nih.gov/27535989/),
[badminton injury review](https://bmjopensem.bmj.com/content/11/1/e002127), and
[ACWR critique](https://pubmed.ncbi.nlm.nih.gov/32502973/).

### Wrist classification

Controlled wrist studies establish feasibility, not Bad Watch accuracy. Cross-player results,
representative non-badminton negatives, real match play, device variation, and calibration all
matter. A plausible-looking heuristic confidence is not a probability.

**Decision:** the current detector may count candidate motion events and display its stroke label
only as **provisional**. Stroke labels cannot drive coaching, level, load, achievements, or match
outcomes. The Detection Lab freezes consent and protocol metadata at recording start and provides
a player-grouped evaluation pipeline, but no learned model is deployable until the release gates
in section 7 pass.

Sources: [controlled wrist study](https://doi.org/10.1177/17543371211048328),
[cross-player study](https://doi.org/10.1109/BigData55660.2022.10020984),
[BadminSense](https://doi.org/10.1145/3772318.3790998), and
[MultiSenseBadminton](https://www.nature.com/articles/s41597-024-03144-z).

---

## 3. Complete player journeys

### Record and recover a session

1. The player confirms racket-hand wear and handedness once.
2. **Start session** launches a health foreground service. A gyroscope is required;
   accelerometer and Health Services heart rate degrade independently.
3. Recording continues with the display off. The live face leads with detected hits and elapsed
   time; estimated exchanges remain secondary, and unavailable optical HR stays visible as the
   truthful `--` placeholder.
4. If Wear OS recreates the process, the durable journal restores the same session ID and start
   time, marks recording quality partial, and appends an immutable process-absence interval from
   the last durable checkpoint through restore. Whole-session elapsed time and heart-rate coverage
   retain their wall-clock bounds, but the app never invents sensor coverage, connects detections
   across that evidence boundary, or calls the interval estimated quiet play. Repeated recoveries
   accumulate distinct intervals.
5. **Stop & save** persists one atomic JSON record. Discard is explicit and confirmed.

If the sensor flow itself fails after recording has begun, Bad Watch does not strand the player
or silently erase the checkpoint. The failure screen makes the choice explicit: **Dismiss** keeps
recoverable data for a later retry, while **Discard** requires confirmation and routes through the
same service command that clears the recorder, optical-HR session, foreground state, and durable
journal together. The next start is a fresh session only after that confirmed discard.

Detection Lab failures use a separate truthful decision. A collection failure offers
**Cancel capture** and clears the active sensors/service state. If collection completed but the
file write failed, **Retry save** keeps the same pending capture ID, writes it atomically, and only
then queues sync; it never restarts sensing or manufactures a second labelled example.

The service never starts a synthetic session after a sticky capture restart, never steals an
exercise already owned by another app, and never requires the optional heart-rate permission to
record motion.

### Review, correct, and understand it

1. A five-step optional diary records activity mode, RPE, soreness review, completion, and
   recording quality. Detailed context, equipment, conditions, goal, and notes remain editable.
2. Detection review can trim either edge, mark recent timestamped events false, and report a
   missed-hit total. Each change appends actor, time, reason, and revision ID.
3. The recap shows corrected **detected** hits as the primary total. Reported misses are separate
   because they have no timestamp or stroke type.
4. Detected exchanges, active span, summary, baselines, personal records, insights, exports, and
   server analytics are rebuilt from the reviewed window and surviving timestamped events.
   Immutable process-absence intervals remain in the raw audit record. Only their overlap with the
   reviewed window is unobserved time; trimming one outside that window can make the remaining
   evidence eligible again when diary quality is also eligible.
   The Summary composition legend says **Detected**, **No detected play**, and **Unobserved**
   (**Détecté**, **Aucun jeu détecté**, and **Non observé** in French). “No detected play” means
   observed time outside detected active spans, not proof that no badminton occurred.
5. Heart-rate zones appear only with a configured/age-estimated maximum. HRR-minutes require an
   explicitly configured resting rate as well. Age estimation uses `208 − 0.7 × age` for adults
   and an exact maximum overrides it.

### Build a useful history

- Home shows the latest usable recap and a literal seven-day activity card.
- History provides status, review/edit, correction, and confirmed deletion.
- Progress supports player-chosen seven-day session and observed reviewed-minute goals, personal
  archive records, self-reported experience, and the like-for-like play pattern. Known unobserved
  process time is subtracted from minute goals and the longest-recording record without deleting
  the recovered session or its detected-hit evidence.
- Sessions marked **Unusable** stay in the audit trail but do not teach baselines, goals,
  progress, Tile, or complication aggregates.
- Sessions marked **Partial**, **Unusable**, or containing a process-absence interval inside the
  reviewed window never produce player-facing insights or teach personal baselines/play patterns.
  Editing diary quality to Complete cannot erase immutable recovery provenance.
- The Tile starts a session and summarizes the last session/week. The watch-face complication is
  explicitly “7-day corrected detected hits,” not load or readiness. Home, History, and the Tile
  show the exact known-unobserved duration when it overlaps the current reviewed window.

### Keep score manually

The standalone match scoreboard supports singles and doubles, rally-point games to 21, two-point
winning margin capped at 30, best of three, interval-at-11, change-of-ends prompts, service side,
undo, and ambient display. The player taps the rally winner; the watch never invents one.
This standard format was checked on 2026-07-26 against the official
[BWF Laws of Badminton](https://system.bwfbadminton.com/documents/folder_1_81/Statutes/CHAPTER-4---RULES-OF-THE-GAME/SECTION%204.1-%20Laws%20of%20Badminton.pdf);
alternative competition formats are not silently inferred or supported.

The active match and its undo history are atomically checkpointed and restored after process
death. It is deliberately a durable live utility rather than a second, incompatible session
archive. Players who want movement/HR evidence run the ordinary session recorder.

### Practise without fake coaching

- A balanced six-corner shadow routine covers every corner once per block and avoids a duplicate
  at block boundaries. Racket-relative wording works for either handedness.
- Learnable haptics encode forehand/backhand and front/mid/rear while the screen remains the
  source of truth.
- Pause, resume, early finish, process restoration, and corruption recovery are durable. Downtime
  is excluded from cue timing.
- BWF-derived cards give general practice instructions with a visible source and measurement
  boundary.

### Own and move the data

- Sessions and eligible Detection Lab captures are file-per-record JSON with atomic replacement,
  orphan-temp recovery, and corrupt-file quarantine.
- Sync uses WorkManager, bearer authentication, no credential-following redirects, per-record
  acceptance/rejection, exponential retry, and payload fingerprints. An unchanged server-rejected
  record stops retrying and shows a localized action category; editing it creates a new upload
  candidate. A changed diary is accepted only when its acknowledged base matches the current
  server revision, so an offline branch cannot leapfrog an intervening browser edit. An incomplete
  or malformed acknowledgement is retryable and no local success marker is written until every
  submitted record has an explicit accepted/rejected result.
- The dashboard binds to loopback by default. A non-loopback bind requires a token. Data API
  responses are `Cache-Control: no-store`; TLS is delegated to a reverse proxy.
- The authenticated browser supports reviewed analytics; activity, completion, recording-quality,
  and comparison-tag filters with URL state/reset/error handling; a raw-vs-reviewed detail audit;
  diary editing; deterministic lossless JSON backup; reviewed CSV; and validate-before-write
  restore. Its default aggregate excludes **Unusable** recordings, while an explicit audit filter
  can include them. Recovery coverage is visible in list/detail cards and as shaded raw timeline
  intervals; reviewed wall time is the shared active-share denominator, so known unobserved time is
  neither active nor quiet. CSV adds `process_absence_count`, `unobserved_seconds`, and
  `observed_seconds`. Raw motion enters an archive only when recording-time model-training consent,
  participant ID, and protocol are all present.

At browser/server repository checkpoint `836f116`, with the Wear APK unchanged, `:server:test`
depends on `:server:dashboardBrowserTest` and requires Node 22. It executes six journeys against
JavaScript extracted from shipped `index.html`: stale-token prompt/retry; URL-filter
hydrate/apply/error/reset; deep-linked reviewed detail; successful revisioned diary save and
aggregate refresh; HTTP 409 reload/conflict replacement; and archive-restore success/error. This
proves client state/request contracts, not responsive layout or real-browser rendering. See the
[dashboard browser regression gate](dashboard.md#browser-regression-gate).

---

## 4. Interface and brand completion

The visual direction is **court at night**: OLED black, a cool off-white text hierarchy, mint as
the primary court-line/accent color, blue for supporting action, and fixed semantic colors where
meaning must not drift. It is modern without becoming a tiny phone UI.

Delivered rules:

- the home screen has one dominant start action, a small training/match choice, and three
  recognizable secondary destinations;
- live play has one glance face, one details page, and a deliberate stop/discard boundary;
- numerals are large, high-contrast, and stable in ambient mode; burn-in-sensitive motion and
  controls disappear there;
- dense groups of three or more metrics stack vertically when font scale reaches `1.20`, keeping
  labels and values separated instead of squeezing a phone-like row into the round viewport;
- hour-plus reviewed durations use `h:mm:ss` and receive a full-width row at normal text size;
  enlarged text uses the same accessibility stack. Summary, Home, History, and weekly cards share
  this rule, so a three-hour recording is never clipped into a narrow three-up metric cell;
- at the same large-text threshold, live recording reserves a 48 dp action lane and uses the
  compact visible label **Finish** / **Finir** while retaining the full **Stop & save** accessible
  name;
- match scoring/interval prompts and the shadow HUD switch to their compact responsive layouts at
  `1.20`, with reducer behavior unchanged; failure screens place the safe/recoverable action
  before any destructive confirmed alternative;
- round-screen safe areas, edge actions, Wear-native lists, concise cards, and semantic grouping
  are used throughout;
- haptics are opt-in for detected hits and purposeful for score/training confirmation;
- destructive actions have confirmation; recoverable actions expose undo where appropriate;
- all user-facing watch-app flows are in English and French, use resource/plural formatting, and expose
  merged semantics/content descriptions for screen readers;
- target SDK 36 permission denial is a supported state, not an error screen.

The delivered design and screen inventory are recorded in
[ui_ux_overhaul_plan.md](ui_ux_overhaul_plan.md); accessibility decisions are in
[accessibility-localization.md](accessibility-localization.md).

---

## 5. Delivery matrix

| Area | Outcome | Evidence in the repository |
| --- | --- | --- |
| Racket-hand onboarding and handedness | Delivered | `OnboardingScreen`, `SettingsStore` |
| Required gyro, optional accelerometer | Delivered | `FusedSensorCollector` capability/failure paths |
| Health Services optical HR | Delivered | `ExerciseHeartRateSession`, coverage and timestamp tests |
| Screen-off recording | Delivered; final lifecycle rerun pending | health FGS `SessionService`, active journal, Detection Lab doze proof, ambient callback proof, scoped short smokes, and a [passing pre-final 180-minute run](evidence/v0.3-pixel-watch-4/endurance/20260726T071128Z/report.json); definitive final report: `FINAL_ENDURANCE_PENDING` |
| Process-death session recovery | Delivered | `ActiveSessionJournal`, immutable `ProcessAbsenceGap` provenance, repeated-recovery/controller regressions, and the [passing final schema-2 watch probe](evidence/v0.3-pixel-watch-4/recovery-final/20260726T121444Z/report.json) |
| Failed-session recovery decision | Delivered | explicit preserve/discard UI, EN/FR copy, and throwing-flow regression |
| Detection Lab terminal recovery | Delivered | serialized cancel/retry-save commands, stable capture ID, cleanup-order regressions |
| Zero-copy detector window | Delivered | `SampleWindow` and pipeline tests |
| Atomic session/capture persistence | Delivered | shared `AtomicFileWriter`, recovery/quarantine tests |
| Detected exchange estimates | Delivered | gap-aware `RallySegmenter`, hard observed-segment boundaries, measurement vocabulary |
| Optional post-session diary | Delivered | typed `SessionContext`/`PostSessionReport`, EN/FR watch flow |
| Append-only detection correction | Delivered | `SessionCorrections`, `ReviewedSessionAnalysis` |
| Evidence-backed insights | Delivered | prior-only like-for-like baseline, historical-recap recomputation, and Partial/Unusable/process-gap silence tests |
| Adult HR profile provenance | Delivered | exact/estimated/placeholder sources and gating tests |
| Transparent HRR-min and session-RPE | Delivered | coverage-gated physiology and explicit formulas |
| History, goals, records, play pattern | Delivered | observed-minute records/goals, exact recovery markers, inference-eligible filters, and `PlayProfileBuilder` |
| Manual BWF scoreboard | Delivered | scoring reducer, durable controller, ambient UI |
| Six-corner shadow routine | Delivered | balanced reducer, durable controller, haptic grammar |
| Sourced practice library | Delivered | BWF cards with non-measurement notes |
| Tile and watch-face complication | Delivered | corrected detected-hit semantics, exact latest-session unobserved marker, and refresh tests |
| Self-hosted dashboard configuration | Delivered | release UI with connection test and HTTP warning |
| Dashboard review/editor/filtering | Delivered | authenticated APIs, activity/completion/quality/tag URL filters, default Unusable exclusion, gap-aware list/detail/timeline, responsive browser UI, and six executable Node 22 client journeys |
| Backup, reviewed CSV, restore | Delivered | deterministic archive, recovery coverage columns, validation tests, and the browser archive-restore success/error journey |
| Payload-specific sync completion | Delivered | incomplete acknowledgements retry; accepted/rejected fingerprints alone create terminal markers |
| Consent-bound Detection Lab | Delivered | immutable consent/protocol/participant metadata |
| Player-independent ML tooling | Delivered as research infrastructure | grouped ingestion/training/evaluation and acceptance gate |
| Automatic learned classifier | Research-gated | no model is accepted without section 7 evidence |
| English/French resources and accessibility semantics | Delivered | resource checks and semantics; a source-scoped earlier normal/`1.30` matrix and French spot checks; final-APK Summary/History/recovery checks, with final Home, Progress, Tile, recovered-History, and Live visuals covered only by regressions |
| Android platform / target SDK 36 | Delivered | granular HR permission and denied-path proof on Android 17 / API 37 hardware |
| CI and release-tag automation | Delivered | Python, JVM, lint, debug and release assemblies plus the Node 22 six-journey browser gate |

---

## 6. Original roadmap disposition

This section is the closure record for ideas intentionally not carried into the product.

### Research-gated, not promised to players

- **Learned hit/stroke-family perception.** Keep collecting consented, protocol-complete real play
  and publish an offline evaluation artifact. The heuristic remains the safe fallback.
- **Audio contact onset.** It requires separate consent, noise/privacy analysis, battery evidence,
  and held-out halls. No microphone permission or dark capture exists in v0.3.
- **Automatic movement events.** Lunge, jump, landing, split-step, and court-direction claims need
  body-appropriate ground truth; a racket wrist is not assumed sufficient.
- **Optional video review.** It would be a new consent and storage product, not a hidden extension
  of watch capture.

These are external-evidence programs. Their absence is not an incomplete v0.3 checkbox.

### Excluded from this product contract

- automatic point/winner/error/serve inference and automatic match scoring;
- shuttle/racket speed presented as fact, court heatmaps, dead-reckoned coverage, or opponent and
  partner tracking from one watch;
- technique grading, adaptive prescriptions, global skill tier, pressure/momentum, and causal
  “focus areas” generated from provisional labels;
- fatigue curves, recovery/readiness scores, per-tissue load, ACWR safety bands, injury alerts,
  and precision calories;
- engagement-farming streaks, social leaderboards, squad surveillance, and achievements based on
  unvalidated stroke labels;
- a mandatory phone companion, account, proprietary cloud, subscription, Health Connect write,
  FIT/TCX/Strava publishing, or automatic cloud backup;
- speculative gear-performance correlations such as string tension causing faster smashes.

Specific old-roadmap decisions are also closed:

- the ordinary-session “tag the last shot” idea is superseded by timestamped false-hit review,
  reported misses, and the separate protocol-labelled Detection Lab; it would not create reliable
  ground truth during a match;
- the gear “locker” and shoe-mileage alerts are reduced to optional per-session equipment
  snapshots—useful context without maintenance theater or injury implications;
- generic hydration prompts are excluded; the watch does not know an individual's hydration need
  and Wear OS already provides timers;
- automatic encrypted local/cloud backup is replaced by authenticated deterministic owner export;
  encryption at rest belongs to the operator's storage and keys, not an undocumented app secret;
- the old “under 45% battery in three hours” target is not claimed without an unpowered hardware
  run. The reproducible probe measures lifecycle first and reports drain only when every battery
  sample proves the watch was off power; no fictional low-power mode is promised;
- Chinese, Indonesian, and Danish were removed from v0.3 rather than machine-translated without a
  reviewer. English and French are the supported release locales; another locale needs a fluent
  review and the same lint/device gate.

Some could be valid products with additional sensors, users, consent, infrastructure, and evidence.
They are excluded here because the best Bad Watch is a focused session companion, not a broad
platform that fills missing sensors with confident copy.

---

## 7. Gates for any stronger model claim

A model may not replace the heuristic or remove “provisional” language until a versioned report
shows all of the following:

1. fixed collection protocol and annotation guide;
2. real drills plus singles/doubles play and representative non-badminton negatives;
3. multiple participants, handedness, experience bands, halls, strap fits, and supported watch
   models;
4. participant-grouped train/validation/test splits with no window leakage;
5. hit-event precision/recall and timing tolerance, per-family precision/recall, confusion matrix,
   and explicit not-detected outcome;
6. confidence intervals and subgroup/context failure reporting;
7. calibrated probabilities before any percentage is shown;
8. an on-device fixed-corpus regression and latency/battery result;
9. a signed model artifact, feature schema, decision threshold, and model card;
10. acceptance criteria declared before the final test set is scored.

`tools/model_acceptance.json` is deliberately offline: training can produce evidence, but cannot
silently deploy a model. User-independent test data remains the authority.

---

## 8. Architecture that shipped

```text
:core    Pure Kotlin models, detector, exchange segmentation, corrections, scoring,
         training reducers, physiology, insights, progress, and shared wire schema.
:app     Wear OS sensing, Health Services, foreground lifecycle, durable stores, sync,
         Compose UI, Tile, complication, English/French resources.
:server  Ktor persistence/API/analytics plus the responsive self-hosted dashboard.
tools/   Consent-aware ingestion, grouped evaluation/training, and offline model gate.
isolate/ Wear/emulator inspection helpers.
tooling/ Release and real-device endurance probes.
```

Files remain the right persistence shape for this standalone watch: listing, reading, replacing,
syncing, and deleting whole session envelopes are its operations. Server-side analytics own the
cross-session query workload. Room would add migration cost without making those operations safer.

The shared `:core` schema is the wire and archive contract. Raw events and original summaries are
immutable; reviewed views are deterministic projections. Compatibility-only schema-1
recovery/fatigue/effort fields decode but current producers write zero and no surface reads them.

---

## 9. Definition of done

v0.3 is done only when all of these are true:

| Acceptance condition | Result |
| --- | --- |
| The full app can be used offline with no account/dashboard | Complete |
| Session and Detection Lab capture survive screen-off under the health FGS | Scoped Detection Lab/short-smoke checks and a pre-final powered 180-minute run pass; definitive frozen-final-APK run is `FINAL_ENDURANCE_PENDING` |
| Optional HR failure/denial leaves a truthful motion-only session | Device-verified |
| Process death restores stable identity and preserves a durable unobserved evidence boundary without fabricating sensor samples or inferred quiet time | Device-verified by the final schema-2 recovery report |
| A failed sensor flow can be preserved or deliberately discarded without trapping the next start | Complete; confirmed-discard UI and a real throwing-flow regression prove journal removal and a fresh next ID |
| Player review changes every derived primary metric and preserves raw evidence | Complete |
| Match and shadow utilities checkpoint every command and restore safely | Complete; physical reports retain match identity/action log and shadow seed/cue timing across process death |
| Sync acceptance/rejection is durable, visible, and payload-specific | Complete; physical offline-retry and authenticated acceptance verified |
| Browser backup/CSV/restore is authenticated and loss-safe | Complete; six Node 22 journeys include revisioned save/conflict behavior and archive-restore success/error |
| English/French, accessibility, lint, JVM tests, debug and release builds pass | Complete; a source-scoped earlier `1.30` matrix covers navigation, permission, live/review/recap, safe failure, match/interval, and EN/FR practice/shadow. Final-APK physical retests cover only Summary/History/recovered-Summary paths; final Home, Progress, Tile, recovered History, and Live visuals have automated regression coverage only |
| A target-36 Pixel Watch run proves the final APK's core flow | Complete for the physically exercised final subset: final APK identity/byte match, start/recover/save, hour-plus Summary/History, recovered Summary notice/composition, and cleanup. Home, Progress, Tile rendering/gap marker, recovered-History marker, and Live details were not recaptured on the final build; older platform evidence is source-scoped and final-source coverage for those paths is automated |
| A three-hour screen-off probe produces exactly one duration-correct session | `FINAL_ENDURANCE_PENDING`; the older passing run is retained as pre-final evidence and does not close this final-APK gate |
| Documentation describes shipped behavior and rejected claims without stale roadmap text | Complete except for replacing `FINAL_ENDURANCE_PENDING` with the definitive retained endurance result |

### Validation and evidence

Node 22 is required because `:server:test` now depends on the browser-client journey task. The CI
and release workflows install it before running this reproducible software gate:

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 -m py_compile tools/ingest.py tools/train.py \
  tooling/wear_session_probe.py tooling/wear_recovery_probe.py
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --stacktrace --no-daemon
```

The frozen local APK candidate additionally ran the broader artifact gate:

```bash
./gradlew clean test lint assembleDebug assembleRelease bundleRelease \
  --stacktrace --no-daemon
```

At the pre-`836f116` frozen APK checkpoint `0bdbe98`, that gate passed all 31 Python unit tests,
`py_compile`, resource XML validation, browser JavaScript syntax validation, and 132 Gradle tasks:
129 executed and three up-to-date. This historical count belongs only to the frozen artifact gate;
the broader local command produces the release AAB, while CI deliberately retains its existing
APK-focused command.

At browser/server repository checkpoint `836f116`, with the Wear APK unchanged, `:server:test`
depends on `:server:dashboardBrowserTest` and requires Node 22. It executes six journeys against
JavaScript extracted from shipped `index.html`: stale-token prompt/retry; URL-filter
hydrate/apply/error/reset; deep-linked reviewed detail; successful revisioned diary save and
aggregate refresh; HTTP 409 reload/conflict replacement; and archive-restore success/error. This
proves client state/request contracts, not responsive layout or real-browser rendering. No current
aggregate Gradle task total is inferred from the earlier artifact run.

The failure-recovery regression is intentionally end-to-end at the controller boundary: its
sensor flow emits one valid sample and then throws, verifies the journal survives Dismiss,
confirms Discard removes it without creating a saved session, and proves the following start uses
a new session ID. This locks the UI's preserve/discard promise to real failure behavior instead
of testing only a dialog callback.

Detection Lab has a complementary storage-failure regression: retry keeps the same completed
capture/ID, persists it once, enqueues sync only after durable success, and orders all terminal
cleanup through serialized service commands. This covers the branch where “try again” means retry
the file write—not silently restart sensor collection.

The physical ledger combines separately source-scoped runs on a Pixel Watch 4 running Android 17 /
API 37. The frozen final APK verifies byte identity, start/recover/save, hour-plus Summary and
History at normal/`1.30` text, and the recovered Summary notice/composition. Earlier candidates
retain permission, ambient, manual-match, training, French, and broader enlarged-text evidence;
they are not relabelled as final-APK captures. Final-build Home, Progress, Tile rendering/gap
marker, recovered-History marker, and Live details were not physically recaptured and have
automated regression coverage only. The lifecycle/recovery tooling runs:

```bash
python3 tooling/wear_session_probe.py \
  --duration-minutes 180 --sample-seconds 60 \
  --suppress-system-wakes \
  --output build/wear-session-probe

python3 tooling/wear_recovery_probe.py \
  --output build/wear-recovery-probe
```

The frozen app/APK checkpoint is `0bdbe985756d962e73b47dfc5ca098a709a9a86f`. Its debug APK
SHA-256 is `5d637e2d0c7fad57f15503c140d9cb11e25f4b94df1732256e88f2ca2a0046fb`; the device's installed
`base.apk` matched byte-for-byte and verified with an APK Signature Scheme v2 debug signer. The
same build produced unsigned release APK
`f46405c1a9ee9d420d61b8e997da27ca75090665801279549eb734c4777fa6d8` and unsigned AAB
`70d3c1816612565187d27f299ff69ca7060e978f717fd5698bcb5ae812fe58dc`.
The installed package reported version `0.3.0` (`versionCode=300`) and last update
`2026-07-26 14:08:34`.

The current session probe includes exact post-stop diary/settled-recap classification, bounded
error cleanup, and transactional wake restoration. It locates the compact Finish/Finir control
through the full localized Stop-and-save accessibility name. The recovery probe validates schema-2
process-absence provenance rather than inferring it from a frozen sample count. System-wake
suppression does not modify battery telemetry or turn a powered run into a battery measurement.
The evidence ledger separates earlier candidate artifacts from final-APK retests so that a
visually convincing screenshot is never allowed to hide source drift.

The endurance probe requires the foreground service and health type to remain present, the screen
to stay asleep/dozing, sensor checkpoints to advance at every interval, exactly one new durable
session, saved duration within five seconds of observed wall time, a captured optional post-stop
diary when shown, and a separately classified settled Summary. The recovery probe additionally
requires valid ordered process-absence intervals, an exact recovered-checkpoint/final-session
match, coverage from the last durable journal boundary through the restart request, and overlap
with the forced stop. Battery percentage is reported only when every sample says the watch was
unpowered; a charging run is lifecycle evidence, not a battery-drain claim.

The final schema-2 [recovery report](evidence/v0.3-pixel-watch-4/recovery-final/20260726T121444Z/report.json)
retains session `5fde6181-2421-4919-ac3b-6a16d0360b59`, stable identity/start, a frozen 1,185-sample
checkpoint, 2,375 samples after restoration, one `Partial` export, and a 42,607 ms wall duration.
Its immutable interval `1785068100751..1785068112485` is 11,734 ms: a conservative durable
evidence boundary that covers journal-to-restart and overlaps the narrower 3,626 ms host-observed
forced-stop interval. Interval validity, checkpoint exact match, coverage, overlap, and overall
provenance booleans all pass.

The older [180-minute report](evidence/v0.3-pixel-watch-4/endurance/20260726T071128Z/report.json)
remains useful pre-final lifecycle/tooling evidence, but it ran on checkpoint `6f6f6cd` before the
long-session, sync, and process-absence work above. It cannot close the frozen-final-APK gate. The
definitive retained result and exact measurements will replace `FINAL_ENDURANCE_PENDING` after the
in-progress run completes. Because that run is powered, it will not substantiate battery drain.

The target-36 granted/denied-HR proof and observed journal sample counts are retained in
[accessibility-localization.md](accessibility-localization.md#target-sdk-36-heart-rate-permission-evidence-2026-07-26).
The current v0.3 screenshot, platform-surface, and recovery artifacts are indexed in the
[Pixel Watch 4 evidence ledger](evidence/v0.3-pixel-watch-4/README.md). The only open blocking v0.3
lifecycle gate in [device-validation.md](device-validation.md) is `FINAL_ENDURANCE_PENDING`; that
does not imply final-build visual recapture of Home, Progress, Tile, recovered History, or Live.
Its separate unpowered battery row remains explicitly unclaimed and is not a v0.3 release blocker.
Generated artifacts without a matching ledger entry do not count as evidence.

CI runs the same software gate, including the six Node 22 browser journeys, on every `master` push
and release tag. A release workflow also verifies package ID, version, APK signature, and
checksums before publication.

---

## 10. Product handoff

Bad Watch v0.3's implementation is a complete, useful session tracker—not a promise that a watch
understands all of badminton. Its strongest features are the unglamorous ones that preserve
trust: recording survives real Wear OS lifecycle pressure, uncertainty is named, corrections
flow through the whole product, subjective context stays subjective, and the player owns the
archive.

Any next version should start with observed court use. The first questions are whether players
actually use the review step, whether optional haptics distract, which diary fields earn repeat
use, and where the detector fails in real matches. New sensor-derived claims come only after the
evidence gates above—not because an old roadmap had an empty box.
