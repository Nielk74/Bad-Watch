#!/usr/bin/env python3
"""Run and evidence a real Bad Watch recording on a connected Wear OS device.

The probe starts a session through the same exported Activity path as the Tile, samples
battery/service state without keeping the display awake, stops through the visible Wear UI,
and validates that exactly one new session JSON was persisted. It writes a compact JSON
report plus start/end screenshots; it never clears app data or deletes a session.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


PACKAGE = "com.badwatch.badwatch"
ACTIVITY = f"{PACKAGE}/com.badwatch.app.MainActivity"
SERVICE_NAME = "com.badwatch.app.service.SessionService"
SESSION_FILE = re.compile(r"^[0-9]+-[A-Za-z0-9-]+\.json$")


class ProbeError(RuntimeError):
    pass


@dataclass(frozen=True)
class BatteryReading:
    captured_at_epoch_millis: int
    level_percent: int | None
    temperature_celsius: float | None
    voltage_millivolts: int | None
    powered: bool | None
    status: int | None
    wakefulness: str | None
    service_running: bool
    service_foreground: bool | None
    foreground_service_type: str | None
    journal_session_id: str | None
    journal_samples_processed: int | None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="adb serial; auto-selects when exactly one device is online")
    parser.add_argument("--duration-minutes", type=float, default=5.0)
    parser.add_argument("--sample-seconds", type=float, default=60.0)
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("build/wear-session-probe"),
        help="report directory (default: build/wear-session-probe)",
    )
    return parser.parse_args()


def run(command: list[str], *, binary: bool = False, check: bool = True) -> str | bytes:
    completed = subprocess.run(command, capture_output=True, check=False)
    if check and completed.returncode != 0:
        stderr = completed.stderr.decode(errors="replace").strip()
        raise ProbeError(f"command failed ({completed.returncode}): {' '.join(command)}\n{stderr}")
    return completed.stdout if binary else completed.stdout.decode(errors="replace")


def resolve_serial(requested: str | None) -> str:
    if requested:
        state = run(["adb", "-s", requested, "get-state"]).strip()
        if state != "device":
            raise ProbeError(f"adb device {requested!r} is {state!r}, not online")
        return requested
    lines = run(["adb", "devices"]).splitlines()[1:]
    online = [line.split()[0] for line in lines if line.strip().endswith("\tdevice")]
    if len(online) != 1:
        raise ProbeError(f"expected exactly one online adb device, found {len(online)}; pass --serial")
    return online[0]


class Device:
    def __init__(self, serial: str) -> None:
        self.serial = serial

    def adb(self, *args: str, binary: bool = False, check: bool = True) -> str | bytes:
        return run(["adb", "-s", self.serial, *args], binary=binary, check=check)

    def shell(self, *args: str, check: bool = True) -> str:
        return self.adb("shell", *args, check=check)  # type: ignore[return-value]

    def is_session_service_running(self) -> bool:
        return self.service_state()[0]

    def service_state(self) -> tuple[bool, bool | None, str | None]:
        output = self.shell("dumpsys", "activity", "services", PACKAGE)
        running = SERVICE_NAME in output
        if not running:
            return False, False, None
        foreground_match = re.search(r"\bisForeground=(true|false)\b", output)
        type_match = re.search(
            r"\b(?:foregroundServiceType|types)=(0x[0-9a-fA-F]+)\b",
            output,
        )
        return (
            True,
            foreground_match.group(1) == "true" if foreground_match else None,
            type_match.group(1) if type_match else None,
        )

    def wakefulness(self) -> str | None:
        output = self.shell("dumpsys", "power")
        match = re.search(r"^\s*mWakefulness=([^\s]+)\s*$", output, re.MULTILINE)
        return match.group(1) if match else None

    def session_files(self) -> set[str]:
        output = self.shell("run-as", PACKAGE, "ls", "files/sessions", check=False)
        return {line.strip() for line in output.splitlines() if SESSION_FILE.fullmatch(line.strip())}

    def battery(self) -> BatteryReading:
        output = self.shell("dumpsys", "battery")
        values: dict[str, int] = {}
        power_sources: list[bool] = []
        for line in output.splitlines():
            match = re.match(r"\s*([A-Za-z ]+):\s*(-?[0-9]+)\s*$", line)
            if match:
                values[match.group(1).strip().lower()] = int(match.group(2))
            power = re.match(r"\s*(?:AC|USB|Wireless|Dock) powered:\s*(true|false)\s*$", line)
            if power:
                power_sources.append(power.group(1) == "true")
        temperature = values.get("temperature")
        service_running, service_foreground, service_type = self.service_state()
        journal_session_id, journal_samples_processed = self.active_journal_state()
        return BatteryReading(
            captured_at_epoch_millis=int(time.time() * 1000),
            level_percent=values.get("level"),
            temperature_celsius=temperature / 10.0 if temperature is not None else None,
            voltage_millivolts=values.get("voltage"),
            powered=any(power_sources) if power_sources else None,
            status=values.get("status"),
            wakefulness=self.wakefulness(),
            service_running=service_running,
            service_foreground=service_foreground,
            foreground_service_type=service_type,
            journal_session_id=journal_session_id,
            journal_samples_processed=journal_samples_processed,
        )

    def active_journal_state(self) -> tuple[str | None, int | None]:
        journal = self.read_active_journal()
        if journal is None:
            return None, None
        try:
            checkpoint = journal["checkpoint"]
            return checkpoint.get("sessionId"), int(checkpoint["samplesProcessed"])
        except (KeyError, TypeError, ValueError):
            return None, None

    def read_active_journal(self) -> dict[str, Any] | None:
        payload = self.shell(
            "run-as",
            PACKAGE,
            "cat",
            "files/active-session/journal.json",
            check=False,
        )
        try:
            decoded = json.loads(payload)
            return decoded if isinstance(decoded, dict) else None
        except json.JSONDecodeError:
            return None

    def screenshot(self, destination: Path) -> None:
        destination.write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))  # type: ignore[arg-type]

    def start_session(self) -> None:
        self.shell(
            # CLEAR_TOP guarantees an existing Home/recap Activity receives this intent;
            # merely bringing an existing task forward does not call onNewIntent on Wear.
            "am", "start", "-f", "0x04000000", "-n", ACTIVITY,
            "--ez", "autostart_session", "true",
        )

    def sleep_display(self) -> None:
        # Explicitly exercise the screen-off/doze lifecycle even when a development device is
        # charging with stay-awake enabled. This changes display state, never battery telemetry.
        self.shell("input", "keyevent", "223")  # KEYCODE_SLEEP

    def stop_session_through_ui(self) -> None:
        self.shell("input", "keyevent", "224", check=False)  # KEYCODE_WAKEUP
        self.shell("wm", "dismiss-keyguard", check=False)
        self.shell("am", "start", "-n", ACTIVITY)
        time.sleep(1.5)
        remote = "/sdcard/badwatch-probe-window.xml"
        self.shell("uiautomator", "dump", remote)
        xml_text = self.shell("cat", remote)
        try:
            root = ET.fromstring(xml_text)
        except ET.ParseError as error:
            raise ProbeError(f"could not parse Wear UI tree: {error}") from error
        target = next((node for node in root.iter("node") if node.attrib.get("text") == "Stop & save"), None)
        if target is None:
            raise ProbeError("live UI did not expose the expected 'Stop & save' action")
        bounds = target.attrib.get("bounds", "")
        match = re.fullmatch(r"\[([0-9]+),([0-9]+)]\[([0-9]+),([0-9]+)]", bounds)
        if match is None:
            raise ProbeError(f"unexpected Stop bounds: {bounds!r}")
        left, top, right, bottom = map(int, match.groups())
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def read_session(self, filename: str) -> dict[str, Any]:
        if SESSION_FILE.fullmatch(filename) is None:
            raise ProbeError(f"refusing unexpected session filename {filename!r}")
        payload = self.shell("run-as", PACKAGE, "cat", f"files/sessions/{filename}")
        try:
            return json.loads(payload)
        except json.JSONDecodeError as error:
            raise ProbeError(f"persisted session is not valid JSON: {error}") from error


def wait_until(predicate: Any, timeout_seconds: float, description: str) -> None:
    deadline = time.monotonic() + timeout_seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.25)
    raise ProbeError(f"timed out waiting for {description}")


def device_metadata(device: Device) -> dict[str, str]:
    props = {
        "manufacturer": "ro.product.manufacturer",
        "model": "ro.product.model",
        "device": "ro.product.device",
        "android_release": "ro.build.version.release",
        "api_level": "ro.build.version.sdk",
        "build_fingerprint": "ro.build.fingerprint",
    }
    metadata = {key: device.shell("getprop", prop).strip() for key, prop in props.items()}
    package = device.shell("dumpsys", "package", PACKAGE)
    version = re.search(r"versionName=([^\s]+)", package)
    updated = re.search(r"lastUpdateTime=([^\n]+)", package)
    metadata["app_version"] = version.group(1) if version else "unknown"
    metadata["app_last_update"] = updated.group(1).strip() if updated else "unknown"
    return metadata


def main() -> int:
    args = parse_args()
    if args.duration_minutes <= 0:
        raise ProbeError("--duration-minutes must be positive")
    if args.sample_seconds < 5:
        raise ProbeError("--sample-seconds must be at least 5")

    serial = resolve_serial(args.serial)
    device = Device(serial)
    if device.is_session_service_running():
        raise ProbeError("a Bad Watch session is already running; stop it before starting a probe")

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = args.output / stamp
    output.mkdir(parents=True, exist_ok=False)
    before_files = device.session_files()
    metadata = device_metadata(device)
    start_wall_millis = int(time.time() * 1000)

    device.start_session()
    wait_until(device.is_session_service_running, 15, "the foreground session service")
    time.sleep(2)
    device.screenshot(output / "start.png")

    device.sleep_display()
    wait_until(
        lambda: device.wakefulness() in {"Asleep", "Dozing"},
        15,
        "the display to enter sleep or doze",
    )

    readings = [device.battery()]
    print(
        "probe 0m: "
        f"service={readings[0].service_running}/{readings[0].service_foreground} "
        f"wake={readings[0].wakefulness} samples={readings[0].journal_samples_processed}",
        flush=True,
    )
    duration_seconds = args.duration_minutes * 60.0
    deadline = time.monotonic() + duration_seconds
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(args.sample_seconds, remaining))
        reading = device.battery()
        readings.append(reading)
        elapsed_minutes = (reading.captured_at_epoch_millis - start_wall_millis) / 60_000
        print(
            f"probe {elapsed_minutes:.1f}m: "
            f"service={reading.service_running}/{reading.service_foreground} "
            f"wake={reading.wakefulness} samples={reading.journal_samples_processed}",
            flush=True,
        )
        if not reading.service_running:
            raise ProbeError("foreground service disappeared before the requested duration elapsed")
        if reading.service_foreground is not True:
            raise ProbeError("session service remained present but was not foreground")

    device.stop_session_through_ui()
    wait_until(lambda: not device.is_session_service_running(), 20, "the session service to stop")
    end_wall_millis = int(time.time() * 1000)
    time.sleep(1)
    device.screenshot(output / "recap.png")

    after_files = device.session_files()
    new_files = sorted(after_files - before_files)
    if len(new_files) != 1:
        raise ProbeError(f"expected exactly one new session export, found {new_files}")
    export = device.read_session(new_files[0])
    session = export.get("session", {})
    summary = session.get("summary", {})
    saved_duration = int(summary.get("durationMillis", -1))
    requested_millis = int(duration_seconds * 1000)
    wall_duration = end_wall_millis - start_wall_millis
    # Setup and stop UI add a few seconds around the requested monitoring window. The saved
    # clock should match the real wall interval for which the recorder existed, not merely
    # the sleep duration between probes.
    duration_error = abs(saved_duration - wall_duration)

    start_battery = readings[0].level_percent
    end_battery = readings[-1].level_percent
    battery_measurement_valid = all(reading.powered is False for reading in readings)
    display_sleep_observed = any(
        reading.wakefulness in {"Asleep", "Dozing"} for reading in readings
    )
    foreground_observed = all(
        reading.service_running and reading.service_foreground is True for reading in readings
    )
    health_type_observed = all(
        reading.foreground_service_type is not None
        and int(reading.foreground_service_type, 16) & 0x100 != 0
        for reading in readings
    )
    journal_samples = [
        reading.journal_samples_processed
        for reading in readings
        if reading.journal_samples_processed is not None
    ]
    journal_session_ids = {
        reading.journal_session_id
        for reading in readings
        if reading.journal_session_id is not None
    }
    sensor_progress_observed = (
        len(journal_samples) == len(readings)
        and len(journal_session_ids) == 1
        and journal_session_ids == {session.get("id")}
        and all(later >= earlier for earlier, later in zip(journal_samples, journal_samples[1:]))
        and journal_samples[-1] > journal_samples[0]
    )
    lifecycle_pass = (
        duration_error <= 5_000
        and display_sleep_observed
        and foreground_observed
        and health_type_observed
        and sensor_progress_observed
    )
    report = {
        "schemaVersion": 2,
        "result": "pass" if lifecycle_pass else "fail",
        # ADB mDNS serials can identify a local transport. The build fingerprint and model are
        # sufficient release evidence; retain only a short one-way correlation token.
        "deviceTransportHash": hashlib.sha256(serial.encode()).hexdigest()[:12],
        "device": metadata,
        "requestedDurationMillis": requested_millis,
        "wallDurationMillis": wall_duration,
        "savedDurationMillis": saved_duration,
        "savedVsWallDurationErrorMillis": duration_error,
        "sessionFile": new_files[0],
        "sessionId": session.get("id"),
        "heartRateSampleCount": summary.get("heartRateSampleCount", 0),
        "heartRateCoverage": summary.get("heartRateCoverage", 0),
        "detectedHits": summary.get("totalShots", 0),
        "batteryMeasurementValid": battery_measurement_valid,
        "batteryDeltaPercent": (
            start_battery - end_battery
            if battery_measurement_valid and start_battery is not None and end_battery is not None
            else None
        ),
        "warnings": (
            [] if battery_measurement_valid
            else ["Battery delta withheld because the watch was powered or charger state was unknown."]
        ),
        "batteryReadings": [asdict(reading) for reading in readings],
        "displaySleepObserved": display_sleep_observed,
        "foregroundServiceObservedThroughout": foreground_observed,
        "healthForegroundTypeObservedThroughout": health_type_observed,
        "sensorJournalProgressObservedThroughout": sensor_progress_observed,
        "startedAtEpochMillis": start_wall_millis,
        "endedAtEpochMillis": end_wall_millis,
    }
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    if report["result"] != "pass":
        raise ProbeError(
            "duration, display-sleep, health-foreground, or sensor-journal lifecycle gate failed"
        )
    print(f"Evidence written to {output}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except ProbeError as error:
        print(f"wear-session-probe: {error}", file=sys.stderr)
        raise SystemExit(2)
