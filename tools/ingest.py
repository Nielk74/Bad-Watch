#!/usr/bin/env python3
"""Turn Bad Watch capture files into a flat training dataset.

Reads the JSON written by the watch (or pulled from the dashboard server) and emits one
row per labelled swing, with features derived from the raw sample window.

    ./tools/ingest.py --input badwatch-data/captures --output dataset.csv
    ./tools/ingest.py --server http://localhost:8080 --output dataset.csv

Deliberately standard-library only: this has to run on a coach's laptop without a Python
environment set up. `train.py` is where scikit-learn becomes a dependency.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
import urllib.request
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class SwingRow:
    """One labelled swing, flattened to scalar features.

    These mirror the features the on-watch rule-based classifier uses, plus several it does
    not, so a trained model can be compared against the heuristics on equal footing and we
    can see which of the extra features actually earn their place.
    """

    label: str
    participant_id: str
    device_id: str
    data_use: str
    protocol_name: str
    protocol_version: int
    capture_context: str
    watch_manufacturer: str
    watch_model: str
    watch_sdk_int: int
    handedness: str
    sample_count: int
    duration_ms: int
    peak_angular_velocity: float
    mean_angular_velocity: float
    std_angular_velocity: float
    # Axis energy split: which plane the rotation happened in.
    vertical_ratio: float
    horizontal_ratio: float
    # Pronation proxy (x - y), the backhand discriminator.
    pronation_mean: float
    pronation_at_peak: float
    # Shape of the burst: how sharp the stroke was.
    rise_time_ms: int
    fall_time_ms: int
    peak_symmetry: float
    # Accelerometer, when present.
    peak_linear_accel: float
    mean_linear_accel: float
    heart_rate_bpm: float


def magnitude(vec: dict) -> float:
    return math.sqrt(vec["x"] ** 2 + vec["y"] ** 2 + vec["z"] ** 2)


def swing_to_row(swing: dict, export: dict) -> SwingRow | None:
    samples = swing.get("samples") or []
    if len(samples) < 5:
        # Too short to describe a stroke; almost certainly a segmentation artefact.
        return None

    gyros = [s["gyro"] for s in samples]
    mags = [magnitude(g) for g in gyros]
    times = [s["timestampMillis"] for s in samples]
    accels = [magnitude(s.get("accel", {"x": 0, "y": 0, "z": 0})) for s in samples]

    peak_index = mags.index(max(mags))
    peak = mags[peak_index]
    mean = sum(mags) / len(mags)
    variance = sum((m - mean) ** 2 for m in mags) / len(mags)

    vertical = sum(abs(g["z"]) for g in gyros)
    horizontal = sum(abs(g["x"]) + abs(g["y"]) for g in gyros)
    total_axis = vertical + horizontal

    rise = times[peak_index] - times[0]
    fall = times[-1] - times[peak_index]

    heart_rates = [
        s["heartRateBpm"]
        for s in samples
        if s.get("heartRateBpm") is not None and s["heartRateBpm"] == s["heartRateBpm"]
    ]

    protocol = export.get("protocol") or {}
    watch = export.get("watch") or {}

    return SwingRow(
        label=swing["label"],
        participant_id=export.get("participantId") or "",
        device_id=export["deviceId"],
        data_use=export.get("dataUse", "LocalOnly"),
        protocol_name=protocol.get("name", "legacy-unversioned"),
        protocol_version=int(protocol.get("version", 0)),
        capture_context=protocol.get("context", "Unknown"),
        watch_manufacturer=watch.get("manufacturer", ""),
        watch_model=watch.get("model", ""),
        watch_sdk_int=int(watch.get("sdkInt", 0)),
        handedness=export["profile"]["handedness"],
        sample_count=len(samples),
        duration_ms=times[-1] - times[0],
        peak_angular_velocity=peak,
        mean_angular_velocity=mean,
        std_angular_velocity=math.sqrt(variance),
        vertical_ratio=vertical / total_axis if total_axis else 0.0,
        horizontal_ratio=horizontal / total_axis if total_axis else 0.0,
        pronation_mean=sum(g["x"] - g["y"] for g in gyros) / len(gyros),
        pronation_at_peak=gyros[peak_index]["x"] - gyros[peak_index]["y"],
        rise_time_ms=rise,
        fall_time_ms=fall,
        # 0.5 means a symmetric burst; lower means an abrupt strike with a long follow-through.
        peak_symmetry=rise / (rise + fall) if (rise + fall) else 0.5,
        peak_linear_accel=max(accels) if accels else 0.0,
        mean_linear_accel=sum(accels) / len(accels) if accels else 0.0,
        heart_rate_bpm=sum(heart_rates) / len(heart_rates) if heart_rates else float("nan"),
    )


def load_from_directory(path: Path) -> list[dict]:
    exports = []
    for file in sorted(path.glob("*.json")):
        try:
            exports.append(json.loads(file.read_text()))
        except json.JSONDecodeError:
            print(f"  ! skipping unreadable file: {file.name}", file=sys.stderr)
    return exports


def load_from_server(base_url: str, token: str | None) -> list[dict]:
    request = urllib.request.Request(base_url.rstrip("/") + "/api/v1/captures")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--input", type=Path, help="Directory of capture JSON files")
    source.add_argument("--server", help="Dashboard base URL to pull captures from")
    parser.add_argument("--token", help="Bearer token, if the server requires one")
    parser.add_argument("--output", type=Path, default=Path("dataset.csv"))
    args = parser.parse_args()

    if args.input:
        exports = load_from_directory(args.input)
    else:
        exports = load_from_server(args.server, args.token)

    rows: list[SwingRow] = []
    skipped = 0
    for export in exports:
        for swing in export["capture"]["swings"]:
            if swing.get("discarded"):
                continue
            row = swing_to_row(swing, export)
            if row is None:
                skipped += 1
            else:
                rows.append(row)

    if not rows:
        print("No usable swings found. Record some drills first.", file=sys.stderr)
        return 1

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(asdict(rows[0]).keys()))
        writer.writeheader()
        for row in rows:
            writer.writerow(asdict(row))

    per_label: dict[str, int] = {}
    for row in rows:
        per_label[row.label] = per_label.get(row.label, 0) + 1
    participants = {row.participant_id for row in rows if row.participant_id}
    devices = {row.device_id for row in rows}

    print(f"Wrote {len(rows)} swings to {args.output}")
    if skipped:
        print(f"  skipped {skipped} windows that were too short to describe a stroke")
    print(f"  {len(participants)} identified participant(s), {len(devices)} device(s)")
    for label, count in sorted(per_label.items(), key=lambda item: -item[1]):
        print(f"  {label:<16} {count}")

    thin = [label for label, count in per_label.items() if count < 100]
    if thin:
        print(f"\n  Under 100 examples for: {', '.join(sorted(thin))}.")
        print("  Expect poor recall on those classes until you collect more.")
    if len(participants) < 2:
        print("\n  Fewer than two participant IDs are present. Device IDs are not people;")
        print("  training will refuse cross-participant claims until more contributors exist.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
