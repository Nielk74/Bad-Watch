# Bad Watch interface design record

**Status:** delivered in v0.3. This is the acceptance record for the redesign, not a future
roadmap. Sensor-derived feature decisions are governed by [SPORT_MODEL.md](SPORT_MODEL.md).

## Design intent

Bad Watch is used with elevated heart rate, a moving wrist, a circular 240–480 px display, and
often only a one-second glance. The interface therefore prioritizes recognition and recovery over
density:

- one dominant action or fact per screen;
- large stable numerals during play;
- secondary explanation below the fold;
- short, reversible flows for score and practice;
- explicit confirmation before destructive loss;
- evidence vocabulary visible where an estimate might otherwise look exact.

The brand direction is **court at night**: OLED black, off-white primary text, cool-grey
secondary text, mint court-line accents, blue secondary action, and fixed semantic colors for HR
zones, provisional stroke families, warnings, and errors. Dynamic watch-face color can influence
general surfaces, but never recolor semantic evidence.

## Delivered screen hierarchy

```text
Onboarding
  racket-hand requirement → handedness

Home
  Start session
  Match score / Practice
  latest session + 7-day activity
  History / Progress / Settings
  Detection Lab (deliberately secondary)

Live session
  glance face: detected hits + elapsed + optional HR
  detail page: inferred bursts + estimated activity + last provisional event
  stop/save or confirmed discard

Post-session
  optional five-step diary
  evidence recap
  detection correction / diary edit

History
  delivery state + compact evidence
  detail → edit / correct / confirmed delete

Training utilities
  manual match scoreboard
  shadow routine / sourced practice cards
```

## Interaction decisions

### Home and start

Starting the main recorder is the largest edge action. Match and Practice are visually separate
because they are tools, not automatic modes inferred by the recorder. History, Progress, and
Settings use consistent icon actions. Detection Lab is labelled optional and placed last so a
research workflow cannot masquerade as the everyday product.

The Tile exposes the same primary start path. It does not create a competing session type.

### Live play

The primary number is **detected hits**, not “shots” or “score.” Elapsed time remains available
without scrolling. Heart rate appears only when Health Services supplies a current sample; zones
appear only with a sourced maximum. A second page contains inferred exchange structure so detail
never competes with the glance target.

Ambient mode removes animation and controls, dims the palette, and keeps stable count/time content.
Optional per-detection haptics are off by default because some players find in-rally feedback
distracting. Score and shadow haptics encode explicit player actions or cues, not algorithmic
certainty.

### Review and correction

The post-session diary asks one short question at a time and is skippable. Sensor coverage and
whether the player completed their plan are separate questions. Soreness is explicitly reported,
not diagnosed.

The recap leads with corrected detected count, inferred bursts, and reviewed duration. Cards then
show the report, evidenced insights, detected-play structure, optional HR, provisional stroke mix,
and the append-only review trail. Reported missed hits never inflate the detected headline.

### Match and practice

The scoreboard gives each side a large point target, keeps server/court state visible, and makes
undo reachable. Interval and change-of-ends prompts interrupt deliberately, then return to the
same durable match. Nothing is auto-scored.

The shadow screen pairs a very large racket-relative corner with a learnable haptic cue. The
player confirms return to base; “cue-to-tap” is the only timing claim. Practice cards show a BWF
source and a clear statement of what the watch cannot assess.

## Motion and haptics

- Navigation uses short crossfades keyed by screen kind, so 100 Hz state updates do not restart
  an animation.
- The live count uses a brief spring/weight morph for a new detection, disabled in ambient mode.
- There are no infinite decorative animations in the recording hot path.
- Destructive actions use a confirmation dialog; deletion never hides behind an accidental tap.
- Haptic patterns are documented, redundant with text/shape, and never the sole carrier of
  meaning.

## Accessibility and localization

- English and French cover all release surfaces.
- Quantities use Android plural resources where grammar changes; formatted evidence keeps stable
  argument order across locales.
- Compound rows merge semantics, icon-only controls have a content description, and decorative
  icons do not repeat labels.
- Contrast is designed for outdoor/bright-hall glances as well as OLED black.
- Large-text and round-screen hardware inspection are part of the device gate.
- Color never carries detection state, winner, sync failure, or HR meaning alone.

See [accessibility-localization.md](accessibility-localization.md) for the resource and validation
contract.

## Ideas removed during the redesign

The old UI roadmap proposed fatigue cards, recovery scores, shot-quality focus areas, technique
heatmaps, and haptic confirmation on every state. Those ideas were removed because the underlying
signals were absent or because compulsory feedback would worsen play. Likewise, a recap carousel
was replaced by one vertically scannable story: it is easier to navigate on a round watch and
makes the evidence hierarchy inspectable.

ML remains research infrastructure, not a visual promise. Until real-player validation passes,
specific labels stay provisional and cannot become coaching cards, achievements, or progress
grades.

## Acceptance checks

- Every primary journey completes without a dashboard or network.
- Starting, stopping, scoring, undoing, pausing, resuming, saving, correcting, and deleting have
  distinct accessible actions.
- Optional data disappears cleanly instead of showing a fake default.
- Ambient mode is readable and contains no destructive action.
- English/French resources pass strict lint; no user-facing release copy is hard-coded.
- Pixel Watch 4 screenshots cover home, live, recap, match, interval, practice, shadow, settings,
  and failure/permission states at normal and enlarged font scale.
