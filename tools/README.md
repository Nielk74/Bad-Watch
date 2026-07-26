# Training pipeline

Turns labelled swings captured on the watch into a trained stroke classifier.

This exists because the shipped classifier is hand-tuned thresholds that have never been
checked against real play. Replacing it needs a dataset, and a dataset needs three things
that now exist end to end: a way to record labelled swings (the watch's **Collect data**
drill), a way to get them off the watch (dashboard sync, or `adb pull`), and this.

## The loop

```bash
# 1. Record drills on the watch: Collect data → pick a stroke → hit twenty → Save drill.
#    Repeat per stroke type and participant. Raw windows remain local unless the player
#    explicitly enables Share detection drills in Settings.

# 2. Get the captures. Either from the dashboard server…
./tools/ingest.py --server http://localhost:8080 --output dataset.csv

#    Include the shared token when BADWATCH_TOKEN is configured on the server:
./tools/ingest.py --server https://badwatch.example.com \
  --token "$BADWATCH_TOKEN" --output dataset.csv

#    …or, on a debuggable local build only, straight from the app sandbox:
adb exec-out run-as com.badwatch.badwatch tar c files/captures > captures.tar
tar xf captures.tar && ./tools/ingest.py --input files/captures --output dataset.csv

# 3. Train and evaluate a baseline.
python3 -m venv .venv && .venv/bin/pip install scikit-learn pandas
.venv/bin/python tools/train.py --dataset dataset.csv \
  --output-dir build/model-candidate
```

`ingest.py` is standard-library only, so it runs anywhere. Only `train.py` needs
scikit-learn. `adb run-as` is not a release export path; normal owners use the authenticated
dashboard archive, whose consent filter excludes local-only raw captures by design.

## What the scripts do

**`ingest.py`** flattens each labelled swing window into scalar features: peak and mean
angular velocity, the vertical/horizontal energy split, a pronation proxy at two points in
the stroke, burst shape (rise/fall time, symmetry), and linear acceleration. It refuses
windows under five samples and skips anything the player marked as discarded.

It preserves the pseudonymous participant id, collection-protocol version, watch model,
and recording-time data-use choice. Legacy files remain usable for local pipeline smoke
tests, but they are never assigned an invented participant.

It also warns about the two things that most often invalidate results: thin classes and
fewer than two identified participants. Both can produce scores that look excellent and
mean nothing.

**`train.py`** fits a random forest and evaluates with **stratified group folds by participant**,
so a contributor never appears in both train and test. This matters more than the model
choice: a random split leaks swing-level similarity between folds and can report an
impressive score for a classifier that fails on a new player. Device ids are explicitly
not treated as people. Missing or single-participant data is refused by default; the
`--allow-device-groups-smoke` escape hatch exists only to verify that a legacy pipeline
runs and labels its output as non-validating.

Classical models first, deliberately. They are interpretable, train in seconds, work with a
few hundred examples per class, and on windowed IMU features they are often competitive
with deep models. If a random forest cannot beat the current heuristics, the problem is the
data, not the model capacity.

Every run writes two reviewable artifacts:

- `random-forest.joblib`, the fitted **offline candidate** (never loaded by the watch app);
- `evaluation.json`, containing the exact feature/label order, dataset SHA-256, class and
  participant counts, grouped-fold definition, confusion matrix, feature importance,
  dependency versions, and every acceptance check.

The checks are pre-registered in `tools/model_acceptance.json`. Passing them only means the
isolated-drill candidate earned a separate field study. `deploymentReady` deliberately
remains `false` because pre-segmented drill windows cannot establish real-play hit-event
precision or recall. This prevents a good classifier-on-known-windows score from silently
becoming a claim that the watch can find strokes in a match.

## Verifying the pipeline without a watch

The pipeline may be exercised on synthetic captures generated from idealised per-stroke
signatures.

**A synthetic score is not an accuracy claim and must not be quoted as one.** Synthetic
data generated from the same axis and pronation biases the features measure only checks
the plumbing. Real strokes overlap, vary by player and fatigue, and include mishits.

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
- ≥ 5 consented participants, both handednesses represented.
- A pre-registered per-class recall target under participant-grouped validation.
- A final held-out participant cohort never seen during tuning.
- Field captures in realistic drills and play, not only isolated repetitions.

Only then consider a TFLite candidate behind the existing detector. Until all gates pass,
the rule-based classifier stays the default and stroke labels remain explicitly provisional.

The current numeric offline criteria are versioned rather than tuned after seeing results:
five participants, 300 usable windows per retained class, macro F1 of 0.80, and recall of
0.75 for every class. These are promotion criteria, not evidence that the current detector
has achieved them. Field-event criteria and subgroup checks must be defined from an
independently labelled protocol before any candidate can ship.
