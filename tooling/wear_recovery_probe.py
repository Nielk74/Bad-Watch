#!/usr/bin/env python3
"""Prove one Bad Watch process-death recovery on a connected physical Wear device."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from wear_session_probe import Device, ProbeError, device_metadata, resolve_serial, wait_until


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--settle-seconds", type=float, default=15.0)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/wear-recovery-probe"),
    )
    return parser.parse_args()


def checkpoint(journal: dict[str, Any], key: str) -> Any:
    try:
        return journal["checkpoint"][key]
    except (KeyError, TypeError) as error:
        raise ProbeError(f"active journal has no checkpoint.{key}") from error


def main() -> int:
    args = parse_args()
    if args.settle_seconds < 12:
        raise ProbeError("--settle-seconds must be at least 12 to cross a journal checkpoint")

    serial = resolve_serial(args.serial)
    device = Device(serial)
    if device.is_session_service_running():
        raise ProbeError("a Bad Watch session is already running")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = args.output / stamp
    output.mkdir(parents=True, exist_ok=False)
    before_files = device.session_files()
    metadata = device_metadata(device)

    device.start_session()
    wait_until(device.is_session_service_running, 15, "the initial foreground service")
    time.sleep(args.settle_seconds)
    before = device.read_active_journal()
    if before is None:
        raise ProbeError("initial active-session journal is missing or invalid")
    device.screenshot(output / "before-process-death.png")

    killed_at = int(time.time() * 1000)
    device.shell("am", "force-stop", "com.badwatch.badwatch")
    wait_until(
        lambda: not device.is_session_service_running(),
        15,
        "the forced process and service to stop",
    )
    time.sleep(3)
    stopped = device.read_active_journal()
    if stopped is None:
        raise ProbeError("active-session journal disappeared while the process was stopped")
    stopped_checkpoint_unchanged = (
        checkpoint(stopped, "sessionId") == checkpoint(before, "sessionId")
        and checkpoint(stopped, "aggregator").get("startedAtMillis")
        == checkpoint(before, "aggregator").get("startedAtMillis")
        and checkpoint(stopped, "samplesProcessed") == checkpoint(before, "samplesProcessed")
    )

    restart_requested_at = int(time.time() * 1000)
    device.start_session()
    wait_until(device.is_session_service_running, 15, "the recovered foreground service")
    time.sleep(args.settle_seconds)
    after = device.read_active_journal()
    if after is None:
        raise ProbeError("recovered active-session journal is missing or invalid")
    device.screenshot(output / "after-recovery.png")

    device.stop_session_through_ui()
    wait_until(lambda: not device.is_session_service_running(), 20, "the recovered session to save")
    time.sleep(1)
    device.screenshot(output / "recap.png")

    new_files = sorted(device.session_files() - before_files)
    if len(new_files) != 1:
        raise ProbeError(f"expected exactly one recovered session, found {new_files}")
    export = device.read_session(new_files[0])
    session = export.get("session", {})
    summary = session.get("summary", {})

    same_id = checkpoint(before, "sessionId") == checkpoint(after, "sessionId") == session.get("id")
    same_start = (
        checkpoint(before, "aggregator").get("startedAtMillis")
        == checkpoint(after, "aggregator").get("startedAtMillis")
        == session.get("startedAtMillis")
    )
    samples_advanced = int(checkpoint(after, "samplesProcessed")) > int(
        checkpoint(before, "samplesProcessed")
    )
    recovery_count = int(after.get("recoveryCount", -1))
    partial = export.get("context", {}).get("recordingQuality") == "Partial"
    saved_duration = int(summary.get("durationMillis", -1))
    elapsed_span = int(session.get("endedAtMillis", -1)) - int(session.get("startedAtMillis", -1))
    elapsed_span_includes_gap = (
        elapsed_span >= 0
        and saved_duration == elapsed_span
        and int(session.get("startedAtMillis", -1)) < killed_at
        and int(session.get("endedAtMillis", -1)) > restart_requested_at
    )
    passed = (
        same_id
        and same_start
        and stopped_checkpoint_unchanged
        and samples_advanced
        and recovery_count >= 1
        and partial
        and elapsed_span_includes_gap
    )

    report = {
        "schemaVersion": 1,
        "result": "pass" if passed else "fail",
        "deviceTransportHash": hashlib.sha256(serial.encode()).hexdigest()[:12],
        "device": metadata,
        "sessionFile": new_files[0],
        "sessionId": session.get("id"),
        "stableSessionId": same_id,
        "stableStartedAtMillis": same_start,
        "samplesBeforeDeath": checkpoint(before, "samplesProcessed"),
        "samplesWhileStopped": checkpoint(stopped, "samplesProcessed"),
        "stoppedCheckpointUnchanged": stopped_checkpoint_unchanged,
        "samplesAfterRecovery": checkpoint(after, "samplesProcessed"),
        "samplesAdvancedAfterRecovery": samples_advanced,
        "recoveryCount": recovery_count,
        "recordingQuality": export.get("context", {}).get("recordingQuality"),
        "forcedStopAtEpochMillis": killed_at,
        "restartRequestedAtEpochMillis": restart_requested_at,
        "observedForcedStopGapMillis": restart_requested_at - killed_at,
        "savedDurationMillis": saved_duration,
        "savedElapsedSpanMillis": elapsed_span,
        "savedElapsedSpanIncludesForcedStopGap": elapsed_span_includes_gap,
        "gapSemantics": (
            "Elapsed session duration spans the interruption; the frozen sample checkpoint proves "
            "that no sensor samples were invented while the process was stopped."
        ),
    }
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if not passed:
        raise ProbeError(
            "stable identity, frozen stopped checkpoint, resumed samples, elapsed-span semantics, "
            "recovery count, or Partial gate failed"
        )
    print(f"Evidence written to {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ProbeError as error:
        print(f"wear-recovery-probe: {error}", file=sys.stderr)
        raise SystemExit(2)
