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
import hashlib
import json
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


def evaluate_candidate_gate(
    class_counts: dict[str, int],
    participant_count: int,
    report: dict,
    grouping_name: str,
    criteria: dict,
) -> dict:
    """Evaluate pre-registered offline gates without implying field-event validation."""
    class_recalls = {
        label: float(report.get(label, {}).get("recall", 0.0))
        for label in sorted(class_counts)
    }
    checks = {
        "participant_grouping": grouping_name == "participant",
        "minimum_participants": participant_count >= criteria["minimumParticipants"],
        "minimum_examples_per_class": bool(class_counts)
        and min(class_counts.values()) >= criteria["minimumExamplesPerClass"],
        "minimum_macro_f1": float(report.get("macro avg", {}).get("f1-score", 0.0))
        >= criteria["minimumMacroF1"],
        "minimum_per_class_recall": bool(class_recalls)
        and min(class_recalls.values()) >= criteria["minimumPerClassRecall"],
    }
    return {
        "criteriaVersion": criteria["criteriaVersion"],
        "checks": checks,
        "offlineGatePassed": all(checks.values()),
        # Pre-segmented drill windows cannot measure real-play event precision/recall.
        "fieldEventValidationPassed": False,
        "deploymentReady": False,
        "classRecall": class_recalls,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", type=Path, default=Path("dataset.csv"))
    parser.add_argument("--min-per-class", type=int, default=20)
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=Path("build/model-candidate"),
        help="directory for the fitted offline model and deterministic evaluation manifest",
    )
    parser.add_argument(
        "--acceptance",
        type=Path,
        default=Path(__file__).with_name("model_acceptance.json"),
        help="pre-registered offline candidate criteria",
    )
    parser.add_argument(
        "--allow-device-groups-smoke",
        action="store_true",
        help="allow legacy/device-grouped smoke tests; never report them as player validation",
    )
    args = parser.parse_args()

    try:
        import joblib
        import pandas as pd
        import sklearn
        from sklearn.ensemble import RandomForestClassifier
        from sklearn.metrics import classification_report, confusion_matrix
        from sklearn.model_selection import StratifiedGroupKFold, cross_val_predict
    except ImportError:
        print("Install dependencies first:  pip install scikit-learn pandas", file=sys.stderr)
        return 1

    if not args.dataset.exists():
        print(f"No dataset at {args.dataset}. Run tools/ingest.py first.", file=sys.stderr)
        return 1

    if not args.acceptance.exists():
        print(f"No acceptance criteria at {args.acceptance}.", file=sys.stderr)
        return 1

    try:
        criteria = json.loads(args.acceptance.read_text())
    except (OSError, json.JSONDecodeError) as error:
        print(f"Could not read acceptance criteria: {error}", file=sys.stderr)
        return 1

    frame = pd.read_csv(args.dataset)

    counts = frame["label"].value_counts()
    too_few = counts[counts < args.min_per_class]
    if not too_few.empty:
        print(f"Dropping classes with under {args.min_per_class} examples: {list(too_few.index)}")
        frame = frame[~frame["label"].isin(too_few.index)]

    counts = frame["label"].value_counts()

    if frame["label"].nunique() < 2:
        print("Need at least two stroke types with enough examples.", file=sys.stderr)
        return 1

    missing_features = [feature for feature in FEATURES if feature not in frame.columns]
    if missing_features:
        print(f"Dataset is missing feature columns: {missing_features}", file=sys.stderr)
        return 1

    features = frame[FEATURES].fillna(0.0)
    labels = frame["label"]

    participant_ids_present = (
        "participant_id" in frame.columns
        and frame["participant_id"].fillna("").astype(str).str.strip().ne("").all()
    )
    if participant_ids_present:
        groups = frame["participant_id"].astype(str)
        grouping_name = "participant"
    elif args.allow_device_groups_smoke and "device_id" in frame.columns:
        groups = frame["device_id"].astype(str)
        grouping_name = "device smoke-test"
        print()
        print("!! LEGACY SMOKE MODE: participant IDs are missing. Device IDs are not")
        print("!! people, so every score below is pipeline-only and says nothing about")
        print("!! generalisation to unseen players.")
        print()
    else:
        print(
            "Participant IDs are missing. Re-ingest versioned captures; device IDs are not "
            "valid player groups. Use --allow-device-groups-smoke only to test the pipeline.",
            file=sys.stderr,
        )
        return 1

    n_groups = groups.nunique()
    if n_groups < 2 and not args.allow_device_groups_smoke:
        print(
            "Need at least two participant IDs for held-out evaluation. Collect from more "
            "players, or use --allow-device-groups-smoke for a non-validating pipeline run.",
            file=sys.stderr,
        )
        return 1

    if n_groups < 2:
        print()
        print("!! ONE-GROUP SMOKE MODE: ordinary stratified folds reuse the same contributor.")
        print("!! Scores are optimistic and must not be used as model-acceptance evidence.")
        print()
        from sklearn.model_selection import StratifiedKFold

        splitter = StratifiedKFold(n_splits=min(5, counts.min()), shuffle=True, random_state=0)
        split_args = {"cv": splitter}
    else:
        # A participant never appears in both train and test. Random swing-level splits leak
        # repeated-window similarity badly and device grouping is not a player substitute.
        splitter = StratifiedGroupKFold(
            n_splits=min(n_groups, 5),
            shuffle=True,
            random_state=0,
        )
        split_args = {"cv": splitter, "groups": groups}

    model = RandomForestClassifier(
        n_estimators=400,
        min_samples_leaf=2,
        class_weight="balanced",
        random_state=0,
        n_jobs=-1,
    )

    predicted = cross_val_predict(model, features, labels, **split_args)

    print(
        f"Dataset: {len(frame)} swings, {frame['label'].nunique()} classes, "
        f"{n_groups} {grouping_name} group(s)"
    )
    print()
    report = classification_report(labels, predicted, digits=3, zero_division=0, output_dict=True)
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

    gate = evaluate_candidate_gate(
        class_counts={str(label): int(count) for label, count in counts.items()},
        participant_count=int(n_groups),
        report=report,
        grouping_name=grouping_name,
        criteria=criteria,
    )
    dataset_sha256 = hashlib.sha256(args.dataset.read_bytes()).hexdigest()
    manifest = {
        "format": "bad-watch-offline-model-candidate",
        "formatVersion": 1,
        "datasetSha256": dataset_sha256,
        "featureOrder": FEATURES,
        "labelOrder": order,
        "sampleCount": int(len(frame)),
        "classCounts": {str(label): int(count) for label, count in counts.items()},
        "grouping": grouping_name,
        "groupCount": int(n_groups),
        "crossValidation": {
            "kind": "StratifiedGroupKFold" if n_groups >= 2 else "StratifiedKFoldSmoke",
            "folds": int(min(n_groups, 5) if n_groups >= 2 else min(5, counts.min())),
            "randomSeed": 0,
        },
        "classificationReport": report,
        "confusionMatrix": matrix.tolist(),
        "featureImportance": {feature: float(value) for feature, value in ranked},
        "gate": gate,
        "toolVersions": {
            "pandas": pd.__version__,
            "scikitLearn": sklearn.__version__,
        },
        "limitations": [
            "Labels come from player-selected isolated drill context.",
            "This pipeline does not evaluate hit-event precision or recall in real play.",
            "A passing offline gate never authorizes automatic deployment.",
        ],
    }
    args.output_dir.mkdir(parents=True, exist_ok=True)
    joblib.dump(model, args.output_dir / "random-forest.joblib", compress=3)
    (args.output_dir / "evaluation.json").write_text(
        json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    )

    print()
    print(f"Candidate artifacts: {args.output_dir}")
    print(f"Offline gate: {'PASS' if gate['offlineGatePassed'] else 'NOT PASSED'}")
    print("Deployment gate: BLOCKED — real-play event validation is a separate requirement.")

    print()
    if grouping_name == "participant" and n_groups >= 2:
        print("Next: inspect the held-out-participant confusion matrix and pre-register a")
        print("field acceptance threshold before considering any on-watch model change.")
    else:
        print("Next: collect consented, versioned captures from multiple participants.")
        print("This smoke run cannot justify an on-watch model change.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
