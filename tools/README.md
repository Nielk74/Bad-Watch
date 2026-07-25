# Training pipeline

Turns labelled swings captured on the watch into a trained stroke classifier.

This exists because the shipped classifier is hand-tuned thresholds that have never been
checked against real play. Replacing it needs a dataset, and a dataset needs three things
that now exist end to end: a way to record labelled swings (the watch's **Collect data**
drill), a way to get them off the watch (dashboard sync, or `adb pull`), and this.

## The loop

```bash
# 1. Record drills on the watch: Collect data → pick a stroke → hit twenty → Save drill.
#    Repeat per stroke type, ideally with several different players.

# 2. Get the captures. Either from the dashboard server…
./tools/ingest.py --server http://localhost:8080 --output dataset.csv

#    …or straight off the watch:
adb exec-out run-as com.badwatch.badwatch tar c files/captures > captures.tar
tar xf captures.tar && ./tools/ingest.py --input files/captures --output dataset.csv

# 3. Train and evaluate a baseline.
python3 -m venv .venv && .venv/bin/pip install scikit-learn pandas
.venv/bin/python tools/train.py --dataset dataset.csv
```

`ingest.py` is standard-library only, so it runs anywhere. Only `train.py` needs
scikit-learn.

## What the scripts do

**`ingest.py`** flattens each labelled swing window into scalar features: peak and mean
angular velocity, the vertical/horizontal energy split, a pronation proxy at two points in
the stroke, burst shape (rise/fall time, symmetry), and linear acceleration. It refuses
windows under five samples and skips anything the player marked as discarded.

It also warns about the two things that most often invalidate results: under 100 examples
for a class, and data from only one device. Both are printed loudly because both produce
scores that look excellent and mean nothing.

**`train.py`** fits a random forest and evaluates with **`GroupKFold` grouped by device**, so
a player never appears in both train and test. This matters more than the model choice: a
random split leaks swing-level similarity between folds and will happily report >0.95 on a
classifier that fails completely on a new player. If there is only one device in the
dataset the script says so and downgrades to stratified k-fold with an explicit warning
that the numbers are a smoke test, not an estimate.

Classical models first, deliberately. They are interpretable, train in seconds, work with a
few hundred examples per class, and on windowed IMU features they are often competitive
with deep models. If a random forest cannot beat the current heuristics, the problem is the
data, not the model capacity.

## Verifying the pipeline without a watch

The pipeline has been exercised on synthetic captures generated from idealised per-stroke
signatures. That run reports ~0.99 macro F1.

**That number is not an accuracy claim and must not be quoted as one.** The synthetic data
was generated from the very axis and pronation biases the features measure, so a high score
only demonstrates that ingest, feature extraction, grouping and reporting are wired up
correctly. Real strokes overlap far more, vary by player and fatigue level, and include
mishits. Expect real first-pass numbers to be dramatically lower.

## The baseline to beat

Before training anything, score what already ships:

```bash
./gradlew :server:evaluateClassifier -PcaptureDir=badwatch-data/captures
```

This runs the rule-based classifier over the same labelled swings and prints per-class
recall and a confusion matrix. A trained model that cannot beat it is not worth deploying,
and knowing *which* strokes the heuristics miss tells you which drills to prioritise.

## Target before shipping a model

- ≥ 300 clean swings per stroke type.
- ≥ 5 players, both handednesses represented.
- Per-class recall ≥ 0.85 under device-grouped cross-validation.
- A held-out player never seen during any tuning.

Only then port to TFLite and wire into `:core` behind the existing heuristics as fallback.
Until all four hold, the rule-based classifier stays the default and stroke labels remain
documented as provisional.
