# Practice and shadow training

The Practice hub has two deliberately different kinds of content. The UI keeps the
distinction visible because choosing a useful coaching cue is not the same as measuring a
player's technique.

## Watch-guided six-corner shadow

The shadow routine is an interaction tool, not a footwork detector:

1. The watch chooses one of six racket-relative corners.
2. It shows and vibrates the cue.
3. The player moves, shadows a stroke, and recovers.
4. The player taps **I'm back at base** to confirm and receive the next cue.

Every block of six cues contains every corner once, and adjacent blocks never repeat a corner
at the boundary. Forehand and backhand are relative to the racket side, so the routine does not
silently assume right-handed court coordinates.

The watch records cue and confirmation timestamps. It does **not** detect corner arrival,
return to base, movement speed, split steps, lunge shape, balance, or footwork quality. Summary
timings are therefore labelled **cue-to-tap delay**, never reaction time or a Footwork Index.

### Haptic language

Each cue has a learnable rhythm:

- one opening pulse: forehand side;
- two opening pulses: backhand side;
- short final pulse: front court;
- medium final pulse: mid court;
- long final pulse: rear court.

The large visual cue remains the source of truth; haptics are an additional prompt, not an
accessibility-exclusive encoding.

### Restore, pause and finish

`ShadowRoutineController` is application-scoped and serializes every command. It atomically
checkpoints `ShadowRoutineDocument` at `files/training/active-shadow.json` after start,
confirmation, pause, resume, and early finish.

If Wear OS recreates the process while a cue is active, the routine returns **paused**. Time
between the last durable checkpoint and restore is shifted out of the cue clock, so unobserved
process downtime cannot make a confirmation look artificially slow. A player can then resume
the same cue or finish early while retaining confirmed repetitions. Completed state remains
restorable until **Done** clears it.

## BWF practice library

The starter cards in `BwfPracticeLibrary` adapt themes from
[BWF Coach Education Level 1](https://development.bwfbadminton.com/coaches/level-1):

- split-step rhythm;
- balanced lunge patterns;
- overhead preparation;
- relaxed grip changes;
- six-corner start, approach, hit and recover sequencing.

These are player-selected **general practice cues**. Each detail page shows the source and an
explicit measurement note. A single racket-wrist watch cannot assess opponent-contact timing,
knee alignment, grip shape, shuttle-contact position, balance, pain, or technique quality.

## Verification

Pure sequence/reducer and library-source tests:

```bash
./gradlew :core:test \
  --tests com.badwatch.core.training.ShadowTrainerTest \
  --tests com.badwatch.core.training.PracticeDrillTest
```

Durability, restart-gap handling, command ordering, corruption and haptic-pattern tests:

```bash
./gradlew :app:testDebugUnitTest \
  --tests com.badwatch.app.data.ShadowRoutineStoreTest \
  --tests com.badwatch.app.domain.ShadowRoutineControllerTest \
  --tests com.badwatch.app.domain.ShadowCueHapticsTest
```

The feature also remains covered by the strict app gate:

```bash
./gradlew :app:lintDebug :app:assembleDebug
```
