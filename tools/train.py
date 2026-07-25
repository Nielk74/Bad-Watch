#!/usr/bin/env python3
"""Train and evaluate a baseline stroke classifier.

    ./tools/train.py --dataset dataset.csv

Starts with classical models on hand-engineered features rather than jumping to a neural
net. They are interpretable, they train in seconds, they work with a few hundred examples
per class, and on windowed IMU features they are frequently competitive. If a random forest
cannot beat the existing heuristics, a CNN is not the missing piece — the data is.

Requires scikit-learn:  pip install scikit-learn pandas
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

FEATURES = [
    "sample_count",
    "duration_ms",
    "peak_angular_velocity",
    "mean_angular_velocity",
    "std_angular_velocity",
    "vertical_ratio",
    "horizontal_ratio",
    "pronation_mean",
    "pronation_at_peak",
    "rise_time_ms",
    "fall_time_ms",
    "peak_symmetry",
    "peak_linear_accel",
    "mean_linear_accel",
]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path("dataset.csv"))
    parser.add_argument("--min-per-class", type=int, default=20)
    args = parser.parse_args()

    try:
        import pandas as pd
        from sklearn.ensemble import RandomForestClassifier
        from sklearn.metrics import classification_report, confusion_matrix
        from sklearn.model_selection import GroupKFold, cross_val_predict
    except ImportError:
        print("Install dependencies first:  pip install scikit-learn pandas", file=sys.stderr)
        return 1

    if not args.dataset.exists():
        print(f"No dataset at {args.dataset}. Run tools/ingest.py first.", file=sys.stderr)
        return 1

    frame = pd.read_csv(args.dataset)

    counts = frame["label"].value_counts()
    too_few = counts[counts < args.min_per_class]
    if not too_few.empty:
        print(f"Dropping classes with under {args.min_per_class} examples: {list(too_few.index)}")
        frame = frame[~frame["label"].isin(too_few.index)]

    if frame["label"].nunique() < 2:
        print("Need at least two stroke types with enough examples.", file=sys.stderr)
        return 1

    features = frame[FEATURES].fillna(0.0)
    labels = frame["label"]
    groups = frame["device_id"]

    n_devices = groups.nunique()
    if n_devices < 2:
        print()
        print("!! Only one device in the dataset. Cross-validation below is grouped by")
        print("!! device, so with a single group it degrades to ordinary k-fold and the")
        print("!! scores will be optimistic. Treat them as a smoke test, not an estimate.")
        print()
        from sklearn.model_selection import StratifiedKFold

        splitter = StratifiedKFold(n_splits=min(5, counts.min()), shuffle=True, random_state=0)
        split_args = {"cv": splitter}
    else:
        # Group by device so a player never appears in both train and test: the score we
        # care about is "does this work for someone the model has never seen", and
        # random splits leak swing-level similarity between folds badly.
        splitter = GroupKFold(n_splits=min(n_devices, 5))
        split_args = {"cv": splitter, "groups": groups}

    model = RandomForestClassifier(
        n_estimators=400,
        min_samples_leaf=2,
        class_weight="balanced",
        random_state=0,
        n_jobs=-1,
    )

    predicted = cross_val_predict(model, features, labels, **split_args)

    print(f"Dataset: {len(frame)} swings, {frame['label'].nunique()} classes, {n_devices} device(s)")
    print()
    print(classification_report(labels, predicted, digits=3, zero_division=0))

    print("Confusion matrix (rows = true, columns = predicted)")
    order = sorted(labels.unique())
    matrix = confusion_matrix(labels, predicted, labels=order)
    width = max(len(name) for name in order) + 2
    print(" " * width + "".join(f"{name[:8]:>10}" for name in order))
    for name, row in zip(order, matrix):
        print(f"{name:<{width}}" + "".join(f"{value:>10}" for value in row))

    model.fit(features, labels)
    print()
    print("Feature importance")
    ranked = sorted(zip(FEATURES, model.feature_importances_), key=lambda item: -item[1])
    for feature, importance in ranked:
        bar = "█" * int(importance * 60)
        print(f"  {feature:<24} {importance:.3f} {bar}")

    print()
    print("Next: once per-class recall clears ~0.85 across held-out players, port the")
    print("model to TFLite and wire it into :core with the heuristics as fallback.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
