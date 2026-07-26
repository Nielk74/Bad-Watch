#!/usr/bin/env python3
"""Prove one Bad Watch process-death recovery on a connected physical Wear device."""

from __future__ import annotations

import argparse
import hashlib
import json
import sys
import time
from dataclasses import dataclass
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


@dataclass(frozen=True)
class ProcessAbsenceInterval:
    started_at_millis: int
    ended_at_millis: int

    def as_report(self) -> dict[str, int]:
        return {
            "startedAtMillis": self.started_at_millis,
            "endedAtMillis": self.ended_at_millis,
        }


@dataclass(frozen=True)
class ProcessAbsenceEvidence:
    session_intervals: tuple[ProcessAbsenceInterval, ...]
    checkpoint_intervals: tuple[ProcessAbsenceInterval, ...]
    validation_errors: tuple[str, ...]
    checkpoint_exact_match: bool
    covers_journal_to_restart: bool
    overlaps_forced_stop: bool
    matched_interval: ProcessAbsenceInterval | None

    @property
    def passed(self) -> bool:
        return (
            not self.validation_errors
            and bool(self.session_intervals)
            and self.checkpoint_exact_match
            and self.covers_journal_to_restart
            and self.overlaps_forced_stop
        )

    def as_report(self) -> dict[str, Any]:
        return {
            "processAbsenceGaps": [gap.as_report() for gap in self.session_intervals],
            "afterRecoveryCheckpointProcessAbsenceGaps": [
                gap.as_report() for gap in self.checkpoint_intervals
            ],
            "processAbsenceGapCount": len(self.session_intervals),
            "processAbsenceGapTotalMillis": sum(
                gap.ended_at_millis - gap.started_at_millis
                for gap in self.session_intervals
            ),
            "processAbsenceIntervalsValid": not self.validation_errors,
            "processAbsenceCheckpointExactMatch": self.checkpoint_exact_match,
            "processAbsenceCoversJournalToRestart": self.covers_journal_to_restart,
            "processAbsenceOverlapsForcedStop": self.overlaps_forced_stop,
            "matchedProcessAbsenceGap": (
                self.matched_interval.as_report() if self.matched_interval else None
            ),
            "processAbsenceValidationErrors": list(self.validation_errors),
            "processAbsenceEvidenceValid": self.passed,
        }


def _parse_process_absence_intervals(
    raw: Any,
    source: str,
) -> tuple[tuple[ProcessAbsenceInterval, ...], list[str]]:
    errors: list[str] = []
    if raw is None:
        return (), [f"{source} is missing"]
    if not isinstance(raw, list):
        return (), [f"{source} must be a list"]
    if not raw:
        return (), [f"{source} must contain at least one interval"]

    intervals: list[ProcessAbsenceInterval] = []
    for index, item in enumerate(raw):
        location = f"{source}[{index}]"
        if not isinstance(item, dict):
            errors.append(f"{location} must be an object")
            continue
        started_at = item.get("startedAtMillis")
        ended_at = item.get("endedAtMillis")
        if not isinstance(started_at, int) or isinstance(started_at, bool):
            errors.append(f"{location}.startedAtMillis must be an integer")
            continue
        if not isinstance(ended_at, int) or isinstance(ended_at, bool):
            errors.append(f"{location}.endedAtMillis must be an integer")
            continue
        if started_at < 0:
            errors.append(f"{location}.startedAtMillis must not be negative")
            continue
        if ended_at <= started_at:
            errors.append(f"{location}.endedAtMillis must be after its start")
            continue
        intervals.append(ProcessAbsenceInterval(started_at, ended_at))

    for previous, current in zip(intervals, intervals[1:]):
        if current.started_at_millis < previous.ended_at_millis:
            errors.append(f"{source} must be ordered and non-overlapping")
            break
    return tuple(intervals), errors


def evaluate_process_absence_evidence(
    session_gaps: Any,
    checkpoint_gaps: Any,
    journal_updated_at_millis: Any,
    forced_stop_at_millis: int,
    restart_requested_at_millis: int,
) -> ProcessAbsenceEvidence:
    """Validate durable gap provenance without requiring an Android device.

    The journal timestamp is the last durable observation before recovery. The controller records
    from that conservative boundary until its restart timestamp, which occurs no earlier than the
    host's restart request. The same interval sequence must then survive both the recovered
    checkpoint and final session export.
    """
    session_intervals, session_errors = _parse_process_absence_intervals(
        session_gaps,
        "session.processAbsenceGaps",
    )
    checkpoint_intervals, checkpoint_errors = _parse_process_absence_intervals(
        checkpoint_gaps,
        "after checkpoint aggregator.processAbsenceGaps",
    )
    errors = session_errors + checkpoint_errors
    if not isinstance(journal_updated_at_millis, int) or isinstance(
        journal_updated_at_millis,
        bool,
    ):
        errors.append("stopped journal updatedAtMillis must be an integer")
        journal_updated_at_millis = -1
    if forced_stop_at_millis > restart_requested_at_millis:
        errors.append("forced-stop timestamp must not follow the restart request")
    if journal_updated_at_millis > restart_requested_at_millis:
        errors.append("journal timestamp must not follow the restart request")

    matched = next(
        (
            gap
            for gap in session_intervals
            if gap.started_at_millis <= journal_updated_at_millis
            and gap.ended_at_millis >= restart_requested_at_millis
        ),
        None,
    )
    overlaps_forced_stop = any(
        gap.started_at_millis < restart_requested_at_millis
        and gap.ended_at_millis > forced_stop_at_millis
        for gap in session_intervals
    )
    return ProcessAbsenceEvidence(
        session_intervals=session_intervals,
        checkpoint_intervals=checkpoint_intervals,
        validation_errors=tuple(errors),
        checkpoint_exact_match=(
            not session_errors
            and not checkpoint_errors
            and bool(session_intervals)
            and session_intervals == checkpoint_intervals
        ),
        covers_journal_to_restart=matched is not None,
        overlaps_forced_stop=overlaps_forced_stop,
        matched_interval=matched,
    )


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
    after_aggregator = checkpoint(after, "aggregator")
    checkpoint_gaps = (
        after_aggregator.get("processAbsenceGaps")
        if isinstance(after_aggregator, dict)
        else None
    )
    gap_evidence = evaluate_process_absence_evidence(
        session_gaps=session.get("processAbsenceGaps"),
        checkpoint_gaps=checkpoint_gaps,
        journal_updated_at_millis=stopped.get("updatedAtMillis"),
        forced_stop_at_millis=killed_at,
        restart_requested_at_millis=restart_requested_at,
    )
    passed = (
        same_id
        and same_start
        and stopped_checkpoint_unchanged
        and samples_advanced
        and recovery_count >= 1
        and partial
        and elapsed_span_includes_gap
        and gap_evidence.passed
    )

    report = {
        "schemaVersion": 2,
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
        "stoppedJournalUpdatedAtEpochMillis": stopped.get("updatedAtMillis"),
        "savedDurationMillis": saved_duration,
        "savedElapsedSpanMillis": elapsed_span,
        "savedElapsedSpanIncludesForcedStopGap": elapsed_span_includes_gap,
        "gapSemantics": (
            "Elapsed session duration spans the interruption; immutable process-absence intervals "
            "mark the unobserved boundary and exactly match the recovered checkpoint."
        ),
        **gap_evidence.as_report(),
    }
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if not passed:
        raise ProbeError(
            "stable identity, frozen stopped checkpoint, resumed samples, elapsed-span semantics, "
            "immutable process-absence provenance, recovery count, or Partial gate failed"
        )
    print(f"Evidence written to {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ProbeError as error:
        print(f"wear-recovery-probe: {error}", file=sys.stderr)
        raise SystemExit(2)
