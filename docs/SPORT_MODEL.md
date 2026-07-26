# Bad Watch Sport and Measurement Model

## Purpose

This document is the truth contract between the badminton domain, the sensor pipeline,
and the product language. It answers two different questions that must never be blurred:

1. What does good badminton practice commonly teach?
2. What can a single watch on the racket wrist actually observe?

The first question can inform drills and educational cues. It does **not** make the watch a
technique judge. The second question governs every metric, chart, notification, insight, and
claim in Bad Watch.

The current classifier is heuristic and has not been validated on representative match play.
Therefore, a classifier event is a **detected hit**, not a confirmed shuttle contact, and its
stroke label is provisional. This wording remains mandatory until the validation gates in
[Validation before stronger claims](#validation-before-stronger-claims) are met.

## The measurement vocabulary

Every user-facing value belongs to one of these evidence tiers. Product copy should name the
tier when a reasonable player could otherwise mistake an estimate for a direct measurement.

| Tier | Meaning | Examples | Allowed language |
| --- | --- | --- | --- |
| **Measured** | A sensor or clock supplied the value directly. It may still have device error or missing coverage. | elapsed time, an optical heart-rate observation, raw angular velocity | “recorded duration”, “heart rate” |
| **Detected** | An algorithm found an event in measured signals. False positives and missed events are possible. | detected hit, detected exchange, future jump event | “detected”, never “confirmed” |
| **Estimated** | A model derives an approximate quantity that is not directly observable. | estimated active span, racket-head-speed range, future directional bias | “estimated”, with confidence or an error range |
| **Reported** | The player or coach supplied the value. | session type, score correction, RPE, soreness, competition level | “you reported”, or a clearly editable field |
| **Inferred** | Several signals support an interpretation. This needs validation and should usually be compared with the same player. | play-profile trend, possible late-session change | state the evidence and uncertainty |

An attractive presentation does not justify moving a value up this ladder. A chart of an
estimate remains an estimate.

### Canonical terms

**Detected hit**
: A motion window on the racket wrist that crossed the detector threshold. It is not proof
  that racket and shuttle made contact. It can include a practice swing or another fast arm
  motion, and it can miss compact or slow strokes. Until calibrated, `ShotEvent.confidence`
  is an internal ranking score, **not** a probability that the label is correct.

**Provisional stroke label**
: The heuristic label attached to a detected hit. It may help inspect the data-collection
  pipeline, but it must not drive technique advice, level classification, load warnings,
  achievements, or match outcomes. When a validated model exists, the product should fall
  back from a specific stroke to a broad family, then to “hit”, as confidence falls.

**Rally burst / detected exchange**
: A cluster of at least two detected hits on the wearer’s racket wrist. In the current
  implementation, consecutive hits remain in one cluster when their gap is no more than
  four seconds. “Detected exchange” is preferred in explanatory copy; “rally burst” is a
  compact label when space is constrained.

  This is **not a complete rally**. One watch does not observe the opponent’s contacts and,
  in doubles, does not observe the partner’s contacts. A service fault or other one-contact
  rally is currently discarded with isolated detector noise.

**Detected hits per exchange**
: The wearer’s detected hits in a rally burst. Never label this simply “rally shots,” because
  that implies the total number of contacts by all players.

**Estimated active span**
: The time from the first to the last detected hit in each kept rally burst, summed across
  the session. It excludes play before the wearer’s first detected hit, after their last hit,
  and every missed detection at a boundary. It is useful for personal trends but is not true
  effective playing time.

**Estimated quiet span**
: Gaps between kept rally bursts plus the time from the last detected hit to session stop.
  The current calculation does not include time from session start to the first kept burst.
  A displayed active:quiet ratio must therefore say **estimated** and expose manual session
  trimming or corrections before it is treated as a training target.

**Heart-rate coverage**
: The approximate share of elapsed seconds for which the optical sensor supplied a distinct
  reading. Recorded average and peak BPM may still be shown with their sample count and
  coverage; reserve, drift, and recovery interpretations must be withheld when coverage is
  insufficient for the relevant window. Repeating one optical reading across 100 Hz motion
  samples does not create 100 heart-rate observations.

**Heart-rate profile provenance**
: Resting and maximum heart rate each carry an explicit source. Legacy numeric defaults are
  compatibility placeholders, not player configuration. Zones require a configured maximum;
  reserve-based metrics require both configured resting and maximum values. With no configured
  profile, Bad Watch keeps the measured BPM trace, average, and peak but withholds personalized
  physiology. An optional adult age (18–100) supplies the population estimate
  `208 − 0.7 × age` from [Tanaka et al.](https://pubmed.ncbi.nlm.nih.gov/11153730/); it is labelled
  estimated, not measured, and an exact entered maximum overrides it.

**Cardiovascular load**
: The currently transparent internal-load proxy: session minutes multiplied by mean
  heart-rate reserve. It requires explicitly sourced resting and maximum heart rate and is
  withheld below 60% optical-signal coverage rather than extrapolated from a sparse reading.
  It is not calories, training effect, readiness, tissue load, or injury risk. Its inputs and
  missing-data state must remain visible.

**Legacy recovery, fatigue, and effort scores**
: `TrainingSummary.recoveryScore`, `fatigueScore`, and `effortScore` remain in schema 1 only
  so stored sessions continue to decode. They never had validated definitions and must not be
  interpreted, displayed, or used as model inputs. Current live and synthetic producers write
  zero. Measured HR reserve and player-reported RPE remain separate, explicitly named signals.

## What one racket-wrist watch can and cannot know

### Supported observations

With reliable timestamps and declared coverage, one watch can support:

- recording duration and start/end time;
- angular and linear motion at the wearer’s racket wrist;
- optical heart-rate samples and within-session heart-rate trends;
- candidate swing/hit events and their motion intensity;
- time gaps between those events;
- player-entered context, score, effort, soreness, notes, and corrections;
- within-player trends across comparable sessions.

These observations are enough for a useful session diary: volume, timing, detected-play
structure, cardiovascular response, context, perceived exertion, and progress against the
player’s own history.

### Unsupported from one wrist alone

A racket-wrist watch cannot directly observe:

- shuttle contact, trajectory, speed, landing position, or whether a shot was in;
- the opponent’s or partner’s contacts;
- rally winner, unforced error, serve order, score, or match outcome without player input;
- grip shape or whether the fingers tightened at impact;
- knee-to-foot alignment, lunge depth, exact foot placement, or return-to-base position;
- split-step timing relative to the opponent’s impact without an independently validated
  opponent-contact signal;
- court position or six-corner coverage as ground truth;
- tactical intent, decision quality, pressure, fatigue, pain, or injury;
- a player’s competitive level from one session.

Some items may become research estimates through microphone, video, or additional models.
They stay unsupported in product copy until independently validated on the exact sensor,
wear location, session context, and population used by Bad Watch.

## Corrections and confidence

Trust comes from making uncertainty inspectable and errors easy to repair.

1. **Keep raw provenance.** A correction must not overwrite raw sensor data or the original
   model output. Store the original value, corrected value, actor, and timestamp.
2. **Separate confidence types.** Signal quality, event-detection confidence, stroke-family
   confidence, and insight confidence are different values. Do not average them into one
   reassuring percentage.
3. **Do not present uncalibrated scores as probability.** A UI label such as “82% smash” is
   only valid after probability calibration is measured on held-out, player-independent data.
4. **Degrade gracefully.** Specific stroke → stroke family → detected hit → raw motion-only
   session. Missing heart rate removes cardiac interpretations but never invalidates the
   recording.
5. **Make high-impact facts editable.** Session context, start/end trim, score, point winner,
   false hit, missed-hit count, RPE, soreness, and notes need correction paths. Match scoring
   should use explicit taps or gestures as ground truth whenever automatic evidence is
   ambiguous.
6. **Learn only with consent.** A correction may personalize the local model. It becomes
   shared training data only through a separate, explicit opt-in with clear retention terms.
7. **Show evidence or stay silent.** An insight names the observation it rests on, requires
   enough data, and may return no conclusion. It never manufactures a coaching narrative.

Confidence also decays outside the conditions represented in validation: a new watch model,
loose strap, different wrist, unusual grip, junior player, para-badminton movement pattern,
multi-shuttle drill, or crowded sports hall can all shift the signal.

## Session context comes before comparison

The same numbers mean different things in a singles match, doubles match, multi-shuttle drill,
and warm-up. Bad Watch should request a lightweight context at start and let the player fix it
afterward.

| Context | Required fields | Comparison rule |
| --- | --- | --- |
| Singles match | singles, match, optional opponent/score | compare with singles matches |
| Doubles match | doubles, match, optional partner/opponents/score | compare with doubles matches; never infer team contacts from one watch |
| Conditioned game | singles/doubles, constraint note | separate from ordinary matches unless the player opts in |
| Stroke or multi-shuttle drill | drill family, work/rest structure, optional fed-shuttle count | compare with the same drill family |
| Shadow footwork | shadow, routine/difficulty | no hit or rally claims; use timing and player-confirmed repetitions |
| Warm-up / free play | free play | volume diary only by default |
| Conditioning | conditioning | physiology and RPE; suppress stroke-quality claims |

Additional context is optional but valuable: racket, string/tension, shuttle, hall, temperature,
session goal, pain/soreness before and after, and whether the recording was complete. Context
fields are reports, not sensor discoveries.

Population match averages are useful as research fixtures, not targets. A 2025 systematic
review reports clear differences between disciplines and study protocols; comparing an
untrimmed club session directly with an elite televised match would be false precision.
[The review](https://doi.org/10.3389/fspor.2025.1466778) should inform synthetic tests and
plausibility checks only, while the product compares a player primarily with their own
like-for-like history.

## A play profile, not a guessed level

“Beginner”, “intermediate”, and “advanced” are not sensor measurements. They also vary by
country, club, age group, discipline, and whether the reference is technical skill,
competitive result, or conditioning.

Bad Watch should represent level in two separate ways:

1. **Reported playing context:** self- or coach-reported experience, club/ranking band, main
   discipline, and training frequency. It stays editable and is never silently replaced.
2. **Observed play profile:** several dimensions calculated from repeated, comparable
   sessions, each with its own coverage and confidence.

Recommended profile dimensions are:

- **Detected-play volume:** hits and kept exchanges, with detector coverage caveats;
- **Exchange persistence:** median and upper-quartile detected hits per exchange;
- **Tempo:** detected-hit rate inside estimated active spans;
- **Cardiovascular response:** HR reserve and between-burst recovery only with configured
  physiological inputs, adequate HR coverage, and comparable session structure;
- **Repeatability:** session-to-session variability within one context;
- **Late-session change:** change in observed tempo or motion intensity, described without
  claiming the cause is fatigue;
- **Validated stroke-family breadth:** only after the family classifier passes the gates
  below.

The first profile should require several qualifying sessions rather than rewarding one noisy
recording. A sensible product gate is at least five sessions across at least three days in the
same context, with the minimum data shown beside every dimension. This is a product starting
point to validate, not a scientific threshold. Sparse dimensions stay “building baseline.”

The profile should use neutral, descriptive bands such as **building baseline**, **typical
range**, and **recent change**. It should not award a global skill tier. A future categorical
estimator would need an explicit ground truth (coach assessment or recognized ranking),
representative players, protocol-matched sessions, player-independent validation, subgroup
error reporting, and prospective replication before it can affect coaching.

Research shows why restraint matters. Rally duration, acceleration, and rest features
predicted coach-defined stages above chance in a study of 40 players, which supports further
research but not a consumer level badge
([PubMed record](https://pubmed.ncbi.nlm.nih.gov/32546052/)). A newer novice-versus-experienced
study found whole-body differences using a **17-IMU system** and analysed seven body segments, so
its result cannot be transferred to one wrist
([study](https://doi.org/10.1186/s13102-025-01163-w)). Video-based
footwork research likewise does not validate wrist-only court-position claims
([study](https://doi.org/10.3389/fspor.2026.1753118)).

## Technique themes: education, not diagnosis

The [BWF Coach Education Level 1 materials](https://development.bwfbadminton.com/coaches/level-1)
provide a sound vocabulary for practice. The
[Level 1 manual](https://badminton.lv/faili/bwf_coach_education_coaches_manual_l1-2nd-edition-midres.pdf)
repeatedly emphasizes the following themes:

- organize movement as **start → approach → hit → recover**;
- time the split step around the opponent’s impact;
- keep lunge movement balanced, with controlled knee/foot alignment;
- use a relaxed, adaptable grip and tighten through impact;
- for overhead strokes, prepare side-on, load the rear leg, rotate, and contact above and in
  front where appropriate.

Bad Watch may use these as drill instructions or general practice cards: for example,
“Practice returning to base after each fed shuttle.” It may not turn them into personal
diagnoses such as “your knee collapsed inward” or “your contact was behind you,” because one
wrist does not observe the required body landmarks or shuttle.

The distinction must be visible:

- **Practice cue:** sourced general instruction chosen by the player or coach.
- **Observation:** a measured or detected change, such as slower detected-hit tempo late in
  the session.
- **Diagnosis:** a claim about technique cause, injury, or pathology. Not supported.

Video could eventually support pose-based review, but it must remain an optional, separately
consented source and use its own validation contract.

## Physiology, exertion, and load boundaries

The IOC consensus recommends considering both external load (the work performed) and internal
response, including heart rate and perceived exertion, alongside wellbeing and symptoms
([consensus statement](https://pubmed.ncbi.nlm.nih.gov/27535989/)). Bad Watch should therefore
store complementary signals instead of collapsing them into a magic readiness score:

- **External diary:** duration, estimated active span, detected-hit volume, drill repetitions,
  and manually entered match/drill structure;
- **Internal response:** measured heart-rate trace, plus transparent HR-reserve load only when
  the physiological profile is explicitly configured and coverage allows;
- **Player report:** post-session RPE on a 0–10 scale, soreness/pain location and severity,
  sleep/wellbeing if entered, and a note;
- **Context:** discipline, session mode, completion, and equipment/environment changes.

Session-RPE load may be displayed transparently as `duration in minutes × reported RPE`.
It remains a monitoring aid, not an injury forecast. Trends should say what changed—“your
reported effort is higher than your recent singles sessions”—without prescribing safety.

Badminton injury studies report heterogeneous methods and wide ranges. A 2025 systematic
review found that lower-limb and overuse injuries are important but concluded the evidence is
too heterogeneous for a precise personal-risk model
([review](https://bmjopensem.bmj.com/content/11/1/e002127)). Accordingly:

- do not claim that a shoulder, knee, Achilles, or back is “safe”, “ready”, or “overloaded”;
- do not infer tissue load from provisional stroke labels;
- do not diagnose fatigue from heart-rate drift or motion decline;
- do not turn soreness reports into a medical diagnosis;
- advise stopping and seeking appropriate professional care for concerning symptoms, without
  trying to triage them algorithmically.

Acute:chronic workload ratio must not be presented with a universal “sweet spot”, danger band,
or causal injury alarm. The method has substantial conceptual and causal limitations
([critique](https://pubmed.ncbi.nlm.nih.gov/32502973/)). A rolling volume comparison may still
describe training history, but its window, units, missing days, and lack of causal meaning
must be explicit.

Energy expenditure also needs restraint. Wrist wearables can have large errors for calories;
one systematic review found energy-expenditure error frequently exceeded 30%
([review](https://pubmed.ncbi.nlm.nih.gov/35060915/)). Bad Watch should not use calorie estimates
as a precision outcome or as evidence of badminton performance.

## Validation before stronger claims

### Detection and classification

A new hit or stroke-family model must be evaluated on labelled real play, not only isolated
fed strokes. The release report should include:

- a fixed, versioned protocol and ground-truth annotation guide;
- multiple players, handedness, skill bands, genders/ages where practical, watch models, strap
  fits, halls, drills, singles and doubles;
- player-independent splits so the same person never leaks across train and test;
- hit-event precision, recall, and timing tolerance;
- per-family precision/recall and confusion matrix, including “not detected” and non-badminton
  motion;
- probability calibration if a percentage is shown;
- subgroup and context results, confidence intervals, and known failure modes;
- an on-device regression corpus and versioned model card.

Controlled studies establish feasibility, not Bad Watch accuracy. A wrist-only study reported
95.09% across six stroke classes, but used six trained players and a controlled protocol
([paper](https://doi.org/10.1177/17543371211048328)). Separate work highlights the
cross-player generalization problem and value of personalization
([paper](https://doi.org/10.1109/BigData55660.2022.10020984)). BadminSense reported 91.43%
user-independent accuracy across four stroke types and promising quality/impact estimates,
but its study used 12 amateur participants and its own Galaxy Watch IMU/audio protocol
([paper](https://taizhouchen.github.io/docs/badminsense.pdf),
[DOI](https://doi.org/10.1145/3772318.3790998)). None of those numbers may be copied into Bad
Watch marketing or used as a substitute for local validation.

### Rally and match claims

Validate detected exchanges against synchronized video or a trusted manual event log. Report
boundary precision, wearer-hit count error, singleton behavior, missed-event propagation, and
results separately for singles, doubles, drills, and match play. Exact total rally shots,
serve order, score, point winner, and errors require explicit player/coach input unless a later
multimodal system proves otherwise.

### Public datasets

The [BadminSense dataset](https://github.com/taizhouchen/BadminSense_Dataset) is a useful
benchmark/import target with wrist IMU and audio. Its repository describes 100 Hz IMU,
16 kHz audio, about 14 GB of data, and a CC BY-NC-ND 4.0 license. Do not bundle it, redistribute
derivatives, or ship a model trained from it without a deliberate license review. The
[MultiSense badminton dataset](https://www.nature.com/articles/s41597-024-03144-z) is another
useful reference, but protocol and sensor-placement differences must be documented before
comparing results.

## Product-language checklist

Before shipping a metric or insight, answer all of these:

1. Is it measured, detected, estimated, reported, or inferred?
2. Does the label make that tier obvious?
3. What sensor, model version, time window, and session context produced it?
4. What coverage or confidence is required, and what happens when it is missing?
5. Can the player inspect and correct the high-impact inputs?
6. Is the comparison like-for-like and primarily against the same player?
7. Could the copy be mistaken for score truth, technique diagnosis, medical advice, or injury
   prediction?
8. Is there a validation artifact supporting the strength of the claim?

If any answer is unclear, simplify the claim or withhold it. A smaller truthful metric is a
better product than a precise-looking fiction.

## Evidence index

| Topic | Source | What it supports | What it does not support |
| --- | --- | --- | --- |
| Coaching fundamentals | [BWF Level 1](https://development.bwfbadminton.com/coaches/level-1) and [manual](https://badminton.lv/faili/bwf_coach_education_coaches_manual_l1-2nd-edition-midres.pdf) | General practice vocabulary | Personal diagnosis from wrist data |
| Match temporal demands | [2025 systematic review](https://doi.org/10.3389/fspor.2025.1466778) | Discipline-specific research context | Universal club targets or exact watch rally truth |
| Wrist stroke feasibility | [J. Sports Engineering and Technology](https://doi.org/10.1177/17543371211048328) | Controlled wrist classification can work | Accuracy of Bad Watch in open play |
| Cross-user generalization | [IEEE Big Data](https://doi.org/10.1109/BigData55660.2022.10020984) | Personalization/generalization deserve testing | Permission to overfit one player |
| Wrist IMU + audio | [BadminSense](https://doi.org/10.1145/3772318.3790998) | Multimodal on-watch approach is promising | Transfer of reported accuracy to this app |
| Level-related features | [study](https://pubmed.ncbi.nlm.nih.gov/32546052/) | Research signal for multidimensional profiles | A one-session consumer skill badge |
| Whole-body skill differences | [multi-segment 17-IMU study](https://doi.org/10.1186/s13102-025-01163-w) | Skill groups can differ kinematically | One-wrist equivalence |
| Video footwork | [Frontiers study](https://doi.org/10.3389/fspor.2026.1753118) | Footwork is measurable with suitable observation | Wrist-only court geometry |
| Training-load monitoring | [IOC consensus](https://pubmed.ncbi.nlm.nih.gov/27535989/) | Combine work, response, reports, and context | A single readiness truth |
| Badminton injuries | [2025 systematic review](https://bmjopensem.bmj.com/content/11/1/e002127) | Injury patterns and evidence uncertainty | Personal injury prediction |
| ACWR limits | [conceptual critique](https://pubmed.ncbi.nlm.nih.gov/32502973/) | Avoid causal zones and alarms | Universal safe workload bands |
| Wearable calories | [systematic review](https://pubmed.ncbi.nlm.nih.gov/35060915/) | Energy estimates have material error | Precision calorie claims |
| Age-estimated maximum HR | [Tanaka et al.](https://pubmed.ncbi.nlm.nih.gov/11153730/) | Adult population estimate `208 − 0.7 × age` | An individual's measured maximum |
