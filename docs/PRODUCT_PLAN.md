# Bad Watch — Plan to Finish

> A Wear OS training companion for badminton players. This document is the honest
> state of the project, the product thesis, the full feature vision, and a phased
> plan to get from "gyroscope demo" to "the thing serious players wear every session."

**Progress: Phase 0 complete · Phase 1 substantially complete · dashboard, labelled data
pipeline and session insights delivered.** The blocker on Phase 2 is now data collection
with real players, not missing tooling. Six of the seven v1.0 promises are met or partly
met; the outstanding one is shot-classification accuracy.
See [Part 5 — Roadmap](#part-5--roadmap) for per-phase status and
[Part 8 — Changes to this plan](#part-8--changes-to-this-plan) for decisions that have been
revised since it was written.

---

## Part 0 — Where we started

> **Historical.** This audit describes the repository as found. Most of it has since been
> fixed — the table is kept because it explains why the architecture looks the way it does.
> For current state see the [roadmap](#part-5--roadmap) and the README.

Before planning, an audit. The docs in this repo described an app that did not exist.

| `docs/` claims | Repository reality |
| --- | --- |
| Real-time classification of smash/clear/drop/drive/backhand | `core/` contains `ShotClassifier`, `ShotDetectionPipeline`, `TrainingSessionAggregator` — unit-tested and **never referenced by `app/`**. `app/build.gradle.kts` has no `implementation(project(":core"))`. |
| Heart-rate zones, fatigue, effort, recovery scores | No HR sensor subscription, no `BODY_SENSORS` permission, no Health Services dependency. `HeartRateZone` and the aggregator are orphaned. |
| DataStore session history (40 entries), history screen, "Clear history" | No DataStore dependency, no repository, no persistence of any kind. State dies with the Activity. |
| Foreground `SensorService`, 50 Hz, windowed median filter, 250 ms batching | `SensorCollector` is a `callbackFlow` on `SENSOR_DELAY_GAME`, started from `MainActivity.onStart()`. No service, no filter, no batching. Stops when the screen sleeps. |
| Export as JSON/CSV | `GyroViewModel.exportCaptureCsv()` correctly builds a CSV string. The UI button discards it and writes a line to `Log.d`. |
| Session insights, focus areas, warm-up detection | Computed from raw gyro *magnitude* thresholds (`< 1.2` = Idle, `> 4.5` = Burst). Nothing badminton-specific. Copy is invented at render time. |
| "Tested analytics pipeline plus ViewModel unit coverage" | True, and genuinely decent — but it tests code that is either unreachable (`core`) or not about badminton (`GyroViewModel`). |

Also missing at the platform level: no permissions declared beyond the gyro feature flag,
no foreground service, no Tile, no complication, no ambient/always-on mode, no companion
phone app, no ProGuard config that matters, no CI, and no local Android SDK on this machine
(`ANDROID_HOME` unset — the project has never been built here).

**The one honest asset:** the `core` module is a reasonable, testable, platform-free skeleton.
It is the right shape. It just needs to be plugged in and made real.

**The one honest liability:** the docs. They describe aspiration as fact. Step zero of this
plan is to rewrite them as a roadmap, so nobody (human or agent) burns a day chasing a
`SensorService` that was never written.

---

## Part 1 — The spirit

Read the repo charitably and the intent is clear, and it's a good one:

> **Badminton is the fastest racket sport in the world and it is almost completely
> un-instrumented.** Tennis has Babolat Play. Golf has Arccos. Running has everything.
> A badminton player — a sport of 30 million+ regular players, dominant across Asia and
> Europe — walks off court after two hours with no idea how many smashes they hit, how
> long their rallies were, whether their footwork degraded in the third game, or whether
> today's session was heavier than their shoulder can take.
>
> Bad Watch says: **you are already wearing the sensor.** A modern smartwatch has a
> 6-axis IMU, an optical HR sensor, a microphone, a haptic motor, and a screen 40 cm from
> the racket. That is enough to reconstruct a badminton session in remarkable detail —
> with no cameras, no racket dongles, no subscriptions, and no cloud.

Three commitments that follow from that thesis, and that should govern every decision:

1. **Wrist-only.** No extra hardware. If a feature needs a sensor in the racket handle,
   it doesn't ship. Optional phone/video adds *depth*, never a dependency.
2. **On-device and private.** Sessions are health data. Everything computes on the watch.
   Sync is opt-in, export is user-initiated, no account required to get full value.
3. **Glanceable-first, haptic-first.** During a rally you have ~0.4 s of attention.
   The primary output channel is the vibration motor, not the display. The display is for
   between points. The phone is for after.

**Anti-goals** (say them out loud so the roadmap doesn't drift): not a social network,
not a live-streaming scoreboard, not a general fitness tracker, not a subscription funnel.

---

## Part 2 — What the sport actually demands

Feature ideation should come from the sport, not from what's easy to build. So:

**Badminton is an interval sport.** Rallies average 6–9 s in singles, 4–7 s in doubles,
with 10–20 s between them. Work:rest is roughly 1:2. Effective playing time in a 60-minute
session is ~30–35%. Any "session duration" metric that ignores this is lying to the player.

**The stroke vocabulary is large and hierarchical.** Overhead (smash, jump smash, half-smash,
slice/cut smash, clear — attacking and defensive, drop, slice drop, around-the-head variants),
midcourt (drive, block, push, defensive lift), front court (net shot, tumble/spin net, net kill,
net lift), and serves (low serve, flick, drive serve, high serve). Plus backhand versions of most.
A five-class classifier is a starting point, not the goal.

**Footwork is half the sport, and it's more measurable than strokes.** Split-step timing,
lunge depth and recovery, chassé vs. crossover, scissor-kick jumps, return-to-base discipline,
six-corner coverage. A watch on *either* wrist sees body rotation, jumps, and step cadence.
This is genuinely underserved — nobody instruments amateur footwork.

**Injuries are predictable and specific.** Shoulder (rotator cuff, from smash volume),
knee (patellar tendon, from lunges), Achilles (from jump landings — the single most common
career-ending badminton injury), and lower back (from around-the-head). Every one of these
correlates with a countable event the IMU can see. Load management is not a nice-to-have,
it is arguably the highest-value feature in the whole product.

**Players are obsessive about conditions.** String tension (kg/lb), racket balance, shuttle
speed grade (76–78, and it shifts with hall temperature and altitude), grip, hall lighting
and drafts. They will absolutely log this if you make it two taps, and correlating it with
performance is a feature no competitor has.

**Which wrist matters enormously.** Habitually, most people wear a watch on the
*non-dominant* wrist — which for a racket sport is a completely different signal: no swing
at all, but good body rotation, footwork and lunge/jump data.

> **Decided:** Bad Watch requires the **racket wrist**, and says so in onboarding. Supporting
> both would mean two models and two honest-but-different feature sets before either is good.
> The non-dominant, footwork-only mode is deferred, not rejected — `WristPlacement` keeps a
> place for it in the data model. See [Part 8](#part-8--changes-to-this-plan).

The footwork work in Pillar 3 is what would eventually make that second mode viable, since
almost all of it works on either wrist.

---

## Part 3 — The feature vision

Organized as seven pillars. Each is scoped to what wrist sensors can genuinely support;
where something is speculative it is marked **[R]** for "needs research spike."

### Pillar 1 — Shot intelligence

The foundation. Replace the threshold heuristics with a real perception stack.

- **Multi-sensor fusion.** Gyroscope + accelerometer + linear acceleration + rotation vector
  at 100–200 Hz, using hardware FIFO batching and `event.timestamp` (monotonic nanos), not
  wall-clock. The current `System.currentTimeMillis()` + `distinctUntilChanged()` combination
  destroys timing fidelity and silently drops legitimate repeated samples.
- **Impact detection via microphone.** The shuttle-on-string "pock" is a ~2–5 kHz transient
  with a very distinctive envelope, audible over hall noise at wrist distance. Fusing an audio
  onset with an IMU swing peak gives near-certain "this was a real shot, not a practice swing"
  — and the *absence* of a pock after a full swing is a **miss/mishit**, which no IMU-only
  system can detect. Audio never leaves the device; only onset timestamps are retained. **[R]**
- **Sweet-spot estimation.** Off-centre hits produce a duller, lower-frequency impact and a
  characteristic post-impact frame vibration in the accelerometer. Combined, they yield a
  per-shot "clean contact" score. This is the closest thing to a technique coach the wrist can offer. **[R]**
- **Racket-head speed estimation.** ω × effective radius (wrist-to-string-bed, calibrated per
  player and racket) gives head speed; with a shuttle-transfer coefficient it becomes an
  estimated shuttle speed. Players *love* this number. Report it as a range, honestly labelled
  as an estimate, and use it primarily for *relative* trends (your smash today vs. your baseline).
- **Full stroke taxonomy** — grow from 5 classes to ~15, with a confidence-gated hierarchy:
  always confident about the family (overhead / midcourt / net / serve), less confident about
  the specific stroke. Show the family when the stroke is uncertain, rather than guessing.
- **Handedness and wrist-placement models.** Two model heads, selected at onboarding and
  verifiable ("swing three smashes"). Non-dominant wrist gets a *different feature set*:
  no stroke classification, but full footwork, rally, and load analysis — advertised honestly.
- **Personal calibration.** A 90-second guided routine ("hit five smashes… five clears…") that
  fine-tunes thresholds and, later, adapts the model's final layer on-device. Re-offered when
  confidence drifts.
- **Active learning loop.** When the classifier is unsure, the watch buzzes once at the next
  natural pause: "That last shot — smash or clear?" Two taps. Every answer is training data,
  and the player feels the model getting better at *them*.

### Pillar 2 — Rally & match engine

The layer that turns a stream of shots into badminton.

- **Rally segmentation.** Cluster shots by inter-shot interval and motion energy to detect
  rally start/end. Yields rally length (shots and seconds), inter-rally rest, and the true
  work:rest ratio — the single most informative number about how the session actually went.
- **Auto-scoring.** Rally boundaries plus serve detection (a low serve has an unmistakable
  low-amplitude, low-and-forward signature) drive a rally-scoring model: 21 points, two-point
  margin, cap at 30, best of three. Ambiguity is resolved by the wrist: a flick gesture or
  a tap awards the point. Serve court (odd/even) is tracked automatically, which is the thing
  club players get wrong constantly.
- **Match mode on the wrist.** Giant score, server indicator, correct service court, automatic
  interval reminder at 11, change-of-ends prompt, and a between-games 120-second timer.
  Always-on ambient rendering so the score is readable without a wrist flick.
- **Rally intensity profile.** Every rally scored on shot rate, peak head speed, and movement
  volume. Surfaces "your rallies over 12 shots are where you lose points" — a genuinely
  actionable pattern that amateurs never see.
- **Momentum and pressure analytics.** Performance at 19-19 vs. 5-2. Error rate in the last
  three points of a game. Serve consistency under pressure. This is the stuff coaches talk
  about and nobody measures.
- **Doubles awareness.** Detect front/back vs. side-by-side formation from movement-envelope
  shape, and rotation events. Log partner and compute per-pairing stats. **[R]**

### Pillar 3 — Footwork & movement

The pillar that works on either wrist, and the one with the least competition.

- **Split-step detection and timing.** A small, sharp vertical accelerometer signature.
  Measure whether it happens, and its latency relative to the opponent's contact (which the
  microphone can hear). "You split-stepped on 61% of rallies" is elite-level feedback
  delivered to a club player. **[R]**
- **Lunge counting, depth, and recovery time.** Deceleration signature into the lunge,
  push-off impulse out. Recovery time to base is the fatigue tell — it lengthens before the
  player notices anything.
- **Jump and landing analysis.** Flight time from free-fall duration → jump height. Landing
  impact magnitude → Achilles/knee load. Cumulative landing load is the injury-prevention
  input nobody tracks.
- **Court coverage estimate.** Inertial dead-reckoning over 3–8 second rallies (short enough
  that drift stays bounded), producing distance covered and a directional-bias rose:
  "68% of your movement was to the forehand rear corner." Sold as an estimate, never as GPS truth. **[R]**
- **Shadow footwork trainer.** The best standalone feature in the product, and it needs no
  detection at all — only haptics. Six corners, six distinct vibration patterns. The watch
  calls corners in a randomized or scripted sequence; you move; it measures your time to reach
  and return using the motion signature. Progressive difficulty, ghost-race against your best
  run, and a Footwork Index that trends over weeks. Works alone, at home, in a car park.
- **Recovery discipline score.** Percentage of shots after which you actually returned to base
  before the next contact. Simple, brutal, and exactly what club coaches shout about.

### Pillar 4 — Physiology, load & injury prevention

Where the product stops being a toy and starts being something you'd miss if it broke.

- **Health Services integration.** Use `ExerciseClient` rather than raw `SensorManager` for
  HR, calories, and steps — better battery, better accuracy, correct behavior with the
  system's exercise state, and automatic pause/resume.
- **Badminton-specific HR analysis.** Zone histogram weighted by rally vs. rest, HR recovery
  between rallies (a strong fitness marker), and time-above-threshold within rallies.
  Generic "average heart rate" is nearly meaningless for an interval sport.
- **Fatigue detection from technique, not just heart rate.** Racket-head speed decline vs.
  session baseline, swing-variance increase, lunge-recovery lengthening, split-step drop-off.
  When three of four degrade, the watch says so: *"Smash speed down 11%, lunge recovery up 18%.
  This is where injuries happen."* This is the flagship insight of the whole app.
- **Per-tissue load accounting.** Shoulder load = Σ(overhead shots × intensity³). Knee load =
  Σ(lunge depth × count). Achilles load = Σ(landing impulse). Tracked daily, weekly, and as an
  **acute:chronic workload ratio**, with the sports-science standard warning band above 1.5.
- **Readiness score and training prescription.** Combines ACWR, HRV/resting HR trend, sleep
  (via Health Connect), and days since last heavy session into a single go/caution/rest signal
  shown *before* you leave the house — on a Tile, not buried in the app.
- **Hydration and interval prompts** during long sessions and tournaments, sensitive to
  session intensity and (via weather, if the phone is present) hall conditions.

### Pillar 5 — Coaching, drills & progression

Turning measurement into improvement.

- **Drill library** with wrist-guided execution: multi-shuttle feeds, pattern play, net drills,
  defensive blocks. Each drill knows what it expects to detect, so it can score your execution
  rather than just time you.
- **Adaptive weekly plan.** Detected weaknesses drive prescriptions: backhand drive under 4%
  of shots → backhand drive block. Split-step rate under 50% → shadow footwork. Third-game
  fatigue → conditioning. Periodized backwards from a tournament date you enter once.
- **Technique consistency scoring.** Per-stroke-type variance in swing trajectory over time.
  A rising consistency score is the most motivating number in skill acquisition, and unlike
  "shot count" it can't be gamed by just playing more.
- **Goal setting with real targets** — smash count, rally endurance, footwork index,
  weekly load — with honest progress deltas and a "personal best" ledger.
- **Coach mode.** A coach's phone view over multiple players: session compliance, load flags,
  assigned drills, and side-by-side comparisons. This is the natural B2B wedge (clubs, academies,
  national junior programs) if the project ever wants to be more than a personal tool.
- **Video sync — the killer post-session feature.** Prop a phone on a tripod. The app aligns
  IMU-timestamped shot events to the video timeline (clap-sync or Data Layer clock sync), then
  auto-generates: a highlight reel of every smash above a speed threshold, a clip of every rally
  over 15 shots, and a tap-a-shot-jump-to-frame timeline. Everyone films themselves and nobody
  ever watches the footage — this makes it watchable in ninety seconds. **[R]**

### Pillar 6 — Context, gear & the social layer

Cheap to build, disproportionately loved.

- **Gear locker.** Rackets with string type and tension, grips, shoes with mileage tracking
  (shoe midsole death is a real injury cause). Two taps at session start selects the setup.
- **Conditions log.** Hall, shuttle brand and speed grade, temperature, drafts. Then the payoff:
  *"Your smash speed is 6% higher with the racket at 25 lb vs 27 lb"* — a genuinely novel
  correlation that no product offers, and exactly the argument every club has weekly.
- **Opponents and partners.** Head-to-head records, per-partner doubles win rate, and
  a local ELO. Not a social network — a personal ledger.
- **Achievements with taste.** Milestones tied to the sport (1,000 smashes, a 40-shot rally,
  a month of consistent split-steps), not engagement-farming streak guilt.
- **Club and squad challenges** — opt-in leaderboards for a weekly footwork index or rally
  endurance, shareable by link, no account required to view.

### Pillar 7 — Platform & data ownership

The unglamorous work that makes it a real app rather than a demo.

- **Wear OS surfaces done properly:** a Tile showing readiness and last session; complications
  for weekly load and shot count; ongoing-activity notification with live score; always-on
  ambient mode for match play; rotary-crown navigation; Wear-native `ScalingLazyColumn`
  ergonomics throughout.
- **Companion phone app.** The watch is capture and live feedback; the phone is analysis:
  full charts, shot timelines, coverage roses, load trends, video sync, gear management.
  Wear Data Layer sync, offline-first on both ends.
- **Export and interoperability.** JSON and CSV (finish what `exportCaptureCsv()` started),
  Health Connect write-back, FIT/TCX for Strava and Garmin Connect, and the configurable HTTPS
  endpoint already sketched in `docs/architecture.md` — WorkManager-backed with retry/backoff,
  token auth, and a visible delivery audit log.
- **Backup and restore** via encrypted local archive; optional end-to-end-encrypted cloud sync.
- **Battery budget as a first-class requirement.** Target: a 3-hour session on under 45% of a
  Pixel Watch 3 battery. Achieved with sensor FIFO batching, duty-cycled microphone (armed only
  when the IMU suggests a swing window), a quantized model under 200 KB, and a strict inference
  budget. Every release runs a measured battery-drain test — this constraint kills more
  smartwatch sports apps than bad features do.

---

## Part 4 — Architecture

The current two-module split is right; it just needs filling in and connecting.

```
:core            Pure Kotlin/JVM. Sensor contracts, feature extraction, rally engine,
                 scoring rules, load models, session math. No Android imports. Fast tests.
:core-ml         TFLite inference wrapper + model assets + fallback to :core heuristics.
:data            Room database, repositories, export/serialization, Data Layer sync.
:sensing         Android sensor + audio capture, Health Services, foreground service.
:wear-app        Wear Compose UI, Tiles, complications, ambient, haptic engine.
:phone-app       Companion Compose app: analysis, video sync, gear, coach mode.
:shared-ui       Design system shared across watch and phone.
tools/           Python: dataset ingestion, labeling UI, model training, TFLite export.
isolate/         Existing headless emulator tooling — keep, it's genuinely useful.
```

Key technical decisions to make early, because they're expensive to reverse:

1. **Timestamps.** Everything keyed to `SensorEvent.timestamp` (monotonic nanos), with a single
   conversion to wall-clock at persistence time. The current mixing of wall-clock and sensor
   time makes multi-sensor fusion and video sync impossible.
2. **Ring buffers, not `ArrayDeque` copies.** The current pipeline calls `buffer.toList()` on
   every sample — an allocation per sample at 100 Hz across three sensors. Pre-allocated
   circular float buffers, zero allocation in the hot path.
3. **Persistence: Room, not DataStore.** Sessions contain thousands of shot and movement events
   and need querying by date, stroke type, and gear. DataStore is a key-value store; the docs'
   choice was wrong for the data shape.
4. **Foreground service with `FOREGROUND_SERVICE_HEALTH`,** driven by Health Services'
   `ExerciseClient`, so tracking survives screen-off — the single most important correctness
   fix, since the app currently stops recording the moment the watch sleeps.
5. **Model fallback chain.** TFLite model → calibrated heuristics (`:core`) → raw motion
   metrics. The app must degrade gracefully and never show a blank screen.
6. **Feature flags** for everything speculative, so the audio and dead-reckoning spikes can
   ship dark and be validated with real players before they're promised in the UI.

---

## Part 5 — Roadmap

Seven phases. Each ends with something a real player can use on a real court — no phase is
purely internal, because a smartwatch sports app that isn't tested on court is fiction.

### Phase 0 — Truth and foundation ✅ **Complete**

Stop the docs from lying, and make the thing buildable.

- ✅ Rewrote `README.md`, `docs/architecture.md`, `docs/usage.md` to describe what exists,
  with everything else marked as roadmap. Added `docs/dashboard.md`.
- ✅ Documented Android SDK bootstrap; `local.properties` setup in the README.
- ✅ Wired `implementation(project(":core"))` into `:app`. Deleted the dead gyro-diagnostics
  UI, ViewModel and sensor collector that the app was actually running.
- ✅ CI: `./gradlew test lint assembleDebug` on every push.
- ✅ Replaced the export button that discarded its output into `Log.d` — sessions are now
  durable JSON files, readable via `adb`.

**Shipped:** a truthful repo that builds, tests, and gets data off the watch.

### Phase 1 — Real capture 🟡 **Substantially complete**

You cannot build a classifier without data, and there was no way to collect any.

- ✅ Multi-sensor capture: gyro + accel + HR at 100 Hz with FIFO batching and monotonic
  `SensorEvent.timestamp`. Removed the `distinctUntilChanged()` that was silently dropping
  every at-rest sample.
- ✅ Foreground service (`health` type), so recording survives screen-off — the single most
  important correctness fix in the app.
- ✅ Session persistence (file-per-session JSON; see
  [Part 8](#part-8--changes-to-this-plan) for why not Room).
- ✅ `SessionRecorder` in `:core` joins pipeline + aggregator + rally segmentation, fully
  unit-tested on the JVM without an emulator.
- ✅ Rally segmentation with work:rest analysis — pulled forward from Phase 3 because the
  dashboard needed it and it is pure, testable math.
- ⬜ Health Services `ExerciseClient` instead of raw `SensorManager` for HR.
- ✅ On-watch labelled capture: pick a stroke, hit repetitions, save the drill.
  `SwingSegmenter` cuts windows on angular-velocity peaks, deliberately independent of the
  rule-based classifier so the training set does not inherit its blind spots.
- ✅ `tools/` ingestion and training pipeline with device-grouped cross-validation.
- ⬜ Post-hoc "tag that last shot" flow during ordinary sessions.
- ⬜ Battery instrumentation harness.
- ⬜ Zero-allocation ring buffers in the hot path (`ShotDetectionPipeline` still calls
  `buffer.toList()` per sample).

**Remaining before Phase 1 closes:** Health Services, the battery harness, and the
zero-allocation hot path. The dataset path — the thing that actually gates Phase 2 — is now
open end to end: record a drill on the watch, sync it, run `tools/ingest.py`, train.

**Phase 1 status: substantially complete.** What is left is optimisation and accuracy of the
physiological signal, not capability.

### Phase 1.5 — Dashboard ✅ **Complete** *(pulled forward)*

Not in the original plan as a separate phase; built early because analysis surfaces were the
first thing asked for after the plan was written, and because it forced the sync contract to
be designed properly rather than retrofitted.

- ✅ `:server` Ktor module sharing `:core`'s `@Serializable` types — one schema, no drift.
- ✅ `POST /api/v1/sessions` ingest with per-session acknowledgement, schema-version
  rejection, and optional bearer token.
- ✅ `SyncWorker` on the watch: WorkManager, exponential backoff, offline-first.
- ✅ Browser dashboard: headline tiles, shots per session, rally length distribution, shot
  mix, and an acute:chronic shoulder-load trend.
- ✅ Synthetic session generator for development and fixtures.
- ⬜ Release-build dashboard configuration UI (only the debug adb receiver exists).
- ⬜ Per-session detail view with a rally timeline.

### Phase 2 — Perception *(4–6 weeks)* — **next**

- Python training pipeline: classical baselines (Random Forest, gradient boosting on windowed
  features) before neural approaches, since they're interpretable and often competitive here.
- 1D CNN / small LSTM in TFLite, quantized, target under 200 KB and under 5 ms inference.
- Both wrist-placement model heads; handedness handled at onboarding.
- Audio-onset spike: can we detect the shuttle "pock" reliably in a noisy hall? Ship dark.
- Per-stroke confidence, honest fallback to stroke *family* when uncertain.
- Personal calibration routine.
- Evaluation harness: per-class precision/recall, confusion matrices, held-out players.

**Ships:** real shot detection. Target: >85% accuracy on the four major families, on the
dominant wrist, across players not in the training set.

### Phase 3 — The session product *(3–4 weeks)*

- ✅ Rally segmentation and work:rest analysis *(delivered in Phase 1)*.
- 🟡 Live HUD: the glanceable layout (giant primary number, rally metrics, post-session
  recap) is built. Haptic-first feedback, rotary navigation and ambient mode are not.
- ✅ Session insights — delivered early, because they only need signals that are already
  trustworthy. See [Part 9](#part-9--what-an-insight-is-allowed-to-say).
- Post-session recap: shot distribution, rally timeline, HR profile, fatigue curve.
- Session history with trends; goals and personal bests.
- Tile and complications.
- Fatigue detection from technique degradation — the flagship insight.

**Ships:** v1.0. A complete, useful, self-contained badminton training app.

### Phase 4 — Movement *(4 weeks)*

- Lunge, jump, and landing detection with load accounting.
- Split-step detection and timing.
- Shadow footwork trainer with the six-corner haptic engine (this one can ship earlier if
  the team wants a quick win — it has no detection dependency).
- Court-coverage dead-reckoning spike.
- Footwork Index and recovery-discipline scoring.
- Non-dominant-wrist feature set fully validated.

**Ships:** the differentiator. Nobody else measures amateur footwork.

### Phase 5 — Match & load *(4 weeks)*

- Auto-scoring, match mode, service-court tracking, interval and change-of-ends prompts.
- Pressure and momentum analytics.
- Per-tissue load models, ACWR, readiness score on the pre-session Tile.
- Health Connect integration; sleep and HRV inputs.
- Gear locker and conditions log with correlation analysis.

**Ships:** the app you wear to a tournament.

### Phase 6 — Companion & depth *(6 weeks)*

- Phone app with full analysis surfaces and Data Layer sync.
- Video sync and auto-highlight generation.
- Drill library and adaptive training plans.
- Coach mode and squad dashboards.
- Export to FIT/TCX/Strava; the configurable HTTPS export endpoint.

**Ships:** the platform.

### Phase 7 — Polish and release *(ongoing)*

Onboarding, accessibility, localization (English, French, Chinese, Indonesian, Danish —
follow the sport's actual geography), Play Store listing, crash and quality telemetry
(opt-in, anonymized), and a public beta with the club that supplied the training data.

---

## Part 6 — Risks and open questions

| Risk | Mitigation |
| --- | --- |
| **Non-dominant wrist is the majority case** and cannot do stroke classification | Lead with footwork and load for that mode; be explicit in onboarding about what each placement can measure. Never fake it. |
| **Battery.** 100 Hz IMU + mic + inference over 3 hours is brutal | Hard budget, measured every release. FIFO batching, duty-cycled mic, quantized model. Ship a low-power mode that drops to 50 Hz and skips audio. |
| **Labeled data is the bottleneck**, not the model | Phase 1 exists purely to solve this. Recruit real players early; the labeling UX is a first-class feature, not a debug screen. |
| **Audio in a sports hall** — six courts, shouting, background music | Ship dark behind a flag, validate before promising. IMU-only must remain fully functional. |
| **Estimated shuttle speed will be wrong** and players will screenshot it | Report ranges, label as estimate, emphasize relative trends over absolute numbers. |
| **Dead-reckoning drift** | Bound it to rally-length windows; present as directional bias, never as a court map with coordinates. |
| **Scope.** This document describes several years of work | Every phase ships something usable. Phase 3 is a complete product; everything after is optional depth. |
| **Health claims.** Load and injury features shade toward medical advice | Frame as training-load information, not diagnosis. Cite the sports-science basis for ACWR. Legal review before any injury-risk language ships. |

**Open questions to resolve with real players, not in a text editor:**

1. Do players want live in-rally feedback at all, or is any buzz during play unwelcome?
2. Is auto-scoring trustworthy enough to be useful, or does one wrong point destroy all trust?
3. Which matters more to a club player: shot analytics, or footwork and fitness?
4. Would players actually record video if the app made the footage genuinely watchable?
5. What is the acceptable battery cost? Is 45% for three hours fine, or is 25% the real bar?

---

## Part 7 — Definition of done for v1.0

The end of Phase 3. Bad Watch v1.0 ships when a player can:

| # | Promise | Status |
| --- | --- | --- |
| 1 | Start a session with one tap and have it record reliably for three hours with the screen off | 🟡 One tap and screen-off recording work. Three-hour reliability and battery cost are unmeasured. |
| 2 | Get shot counts by type with >85% accuracy on the dominant wrist, for a player the model has never seen | ❌ Classifier is rule-based and uncalibrated. Phase 2. |
| 3 | See rally count, rally length distribution, and true work:rest ratio | ✅ On the watch and on the dashboard. |
| 4 | Receive one honest, specific, actionable insight per session — not four generated adjectives | ✅ `SessionInsightEngine` derives insights from rally structure and heart rate only — never from uncalibrated stroke labels. Every insight cites its evidence, and the engine returns nothing when the data is thin. |
| 5 | Review the last thirty sessions and see whether they are getting better | ✅ History on the watch, trends on the dashboard. |
| 6 | Get their data out, in a real format, without a computer | 🟡 JSON is durable and syncs to the dashboard; on-watch share/export has no UI. |
| 7 | Do all of the above with no account, no network, and no subscription | ✅ No account exists anywhere in the system; the watch is fully functional offline. |

Everything in this document beyond that line is upside. But that line is the promise — and
the honest summary today is that the *plumbing* is real and tested, while the *perception*
is not. Shot counts and stroke labels remain the weakest claim in the product, and nothing
downstream of them should be trusted until Phase 2 lands.

---

## Part 8 — Changes to this plan

Decisions revised since the plan was written, with the reasoning.

**Dominant wrist is now a requirement, not a mode.** The original plan hedged with two model
heads and a footwork-only feature set for the non-racket wrist. Scoped to one wrist: it
removes an entire parallel model, and the honest framing ("we read the swing, so wear it on
the racket hand") is better than a silently degraded second mode. `WristPlacement` remains
in the data model so the constraint is recorded in every exported session and a future
footwork mode has somewhere to live.

**A web dashboard replaced the companion phone app.** The plan's Phase 6 assumed an Android
companion synced over the Wear Data Layer. A self-hosted server plus a browser page is
strictly simpler: no second app to install or maintain, the same URL works on phone and
desktop, charts are far cheaper in HTML than in Compose, and `:server` sharing `:core`'s
types means the wire contract cannot drift. The watch remains fully functional with no
server at all.

**Persistence is files, not Room.** The plan specified Room because sessions contain
thousands of events. But the watch only lists, reads, marks-synced and deletes — all
querying happens on the server. A file per session gives durable storage, the export format
and the sync payload in one representation, with no annotation processor in the build.
Revisit if on-watch trend queries over hundreds of sessions become a real feature.

**Rally segmentation moved from Phase 3 to Phase 1.** It is pure, testable math, and the
dashboard is far more useful with it than without.

**The dashboard was built before Phase 2.** Out of order deliberately: it forced the sync
contract to be designed properly rather than retrofitted, and it gives every later phase a
place to display results.

**Health Services is still deferred.** The plan called for `ExerciseClient` in Phase 1;
heart rate currently goes through `SensorManager`. This works, but it is less accurate and
less power-efficient, and it should be replaced before any battery claim is made.


---

## Part 9 — What an insight is allowed to say

The app this grew out of generated "insights" like *"Swing variance is 62%. Focus on
repeatable arcs before pushing pace"* from raw gyroscope magnitude. It read as coaching and
was closer to a random number generator. Avoiding a repeat needs a rule, not good intentions.

**An insight may only be derived from a signal we measure, not one we infer.**

Today that means rally structure (a rally boundary is a gap in time) and heart rate (a
sensor reading). It explicitly excludes stroke type, because the classifier is uncalibrated
heuristics — so "you hit too few backhands" is not an insight, it is a guess wearing a
coach's jacket. When Phase 2 makes stroke labels trustworthy, stroke rules join as
*additions*.

Four supporting constraints, all enforced in `SessionInsightEngine` and its tests:

1. **Every insight carries its evidence.** The number the claim rests on is displayed. A
   player can then disagree with the interpretation while still trusting the measurement,
   and a wrong insight is debuggable rather than merely irritating.
2. **Silence is a valid output.** Under five rallies, nothing is said. Rally-decay analysis
   needs twelve. Cardiac drift needs heart rate on both halves. Roughly half of
   `SessionInsightEngineTest` asserts that *nothing* is produced — a rule that never stays
   quiet is a bug.
3. **Compare against the player, not a population.** Sport-wide norms are the fallback used
   only until three sessions of history exist, and baselines use medians so one freak
   session cannot redefine normal.
4. **At most three, cautions first.** A fatigue signal must not be buried under a personal
   best.

The observable effect: on nineteen seeded sessions the engine speaks about thirteen and says
nothing about six. That ratio is the feature.
