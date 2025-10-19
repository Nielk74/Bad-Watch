#!/usr/bin/env python3
"""
Wear OS automation helper for agents and scripts.

This module wraps common emulator and adb interactions behind a JSON-first CLI
so higher-level tooling (LLMs, CI tasks, etc.) can drive a Wear OS app without
Android Studio.
"""

from __future__ import annotations

import argparse
import base64
import functools
import json
import os
import shlex
import subprocess
import sys
import tempfile
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional, Tuple


class WearToolError(RuntimeError):
    """Raised when an adb/emulator command fails."""


@dataclass
class CommandResult:
    stdout: str
    stderr: str
    returncode: int


ARTIFACTS_DIR = Path(__file__).resolve().parent / "artifacts"
LOG_DIR = ARTIFACTS_DIR / "logs"
SCREENSHOT_DIR = ARTIFACTS_DIR / "screenshots"


def ensure_artifact_dirs() -> None:
    for path in (ARTIFACTS_DIR, LOG_DIR, SCREENSHOT_DIR):
        path.mkdir(parents=True, exist_ok=True)


def run_command(
    cmd: Iterable[str],
    *,
    check: bool = True,
    capture_output: bool = True,
    text: bool = True,
) -> CommandResult:
    """Execute a command and return stdout/stderr."""
    process = subprocess.run(
        list(cmd),
        check=False,
        capture_output=capture_output,
        text=text,
    )
    if check and process.returncode != 0:
        raise WearToolError(
            f"Command failed ({process.returncode}): {' '.join(shlex.quote(x) for x in cmd)}\n"
            f"stdout: {process.stdout}\nstderr: {process.stderr}"
        )
    return CommandResult(
        stdout=process.stdout if process.stdout is not None else "",
        stderr=process.stderr if process.stderr is not None else "",
        returncode=process.returncode,
    )


def adb_args(serial: Optional[str]) -> List[str]:
    args: List[str] = ["adb"]
    if serial:
        args.extend(["-s", serial])
    return args


def run_adb(serial: Optional[str], *sub_cmd: str) -> CommandResult:
    cmd = adb_args(serial) + list(sub_cmd)
    return run_command(cmd)


def wait_for_device(serial: Optional[str], timeout: float = 120.0) -> None:
    deadline = time.monotonic() + timeout
    run_adb(serial, "wait-for-device")
    while time.monotonic() < deadline:
        status = run_adb(serial, "shell", "getprop", "sys.boot_completed").stdout.strip()
        if status == "1":
            # Some system images still take a beat before system UI is responsive.
            time.sleep(2.0)
            return
        time.sleep(1.0)
    raise WearToolError("Timed out waiting for sys.boot_completed")


def start_emulator(
    avd_name: str,
    *,
    port: Optional[int],
    gpu: Optional[str],
    extra: List[str],
    wait: bool,
    serial: Optional[str],
    env: Dict[str, str],
) -> Dict[str, Any]:
    cmd: List[str] = ["emulator", "-avd", avd_name, "-no-window", "-no-boot-anim", "-no-audio"]
    if gpu:
        cmd.extend(["-gpu", gpu])
    if port:
        cmd.extend(["-port", str(port)])
    cmd.extend(extra)
    # Launch detached; caller controls lifecycle via adb emu kill.
    ensure_artifact_dirs()
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    log_path = LOG_DIR / f"{avd_name}_{timestamp}.log"
    with open(log_path, "ab", buffering=0) as log_file:
        process = subprocess.Popen(
            cmd,
            stdout=log_file,
            stderr=subprocess.STDOUT,
            env={**os.environ, **env},
        )
    emulator_serial = serial
    if not emulator_serial and port:
        emulator_serial = f"emulator-{port}"

    if wait:
        wait_for_device(emulator_serial, timeout=180.0)

    return {
        "pid": process.pid,
        "command": cmd,
        "log_path": str(log_path),
        "serial": emulator_serial,
        "waited_for_boot": wait,
    }


def kill_emulator(serial: str) -> Dict[str, Any]:
    run_adb(serial, "emu", "kill")
    return {"serial": serial, "status": "terminated"}


def install_apk(serial: Optional[str], apk_path: str) -> Dict[str, Any]:
    result = run_adb(serial, "install", "-r", apk_path)
    return {"serial": serial, "output": result.stdout.strip()}


def start_activity(serial: Optional[str], component: str) -> Dict[str, Any]:
    result = run_adb(serial, "shell", "am", "start", "-n", component)
    return {"serial": serial, "output": result.stdout.strip()}


def capture_screenshot(serial: Optional[str], output: Optional[str], as_base64: bool) -> Dict[str, Any]:
    ensure_artifact_dirs()
    proc = subprocess.Popen(
        adb_args(serial) + ["exec-out", "screencap", "-p"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    data, err = proc.communicate()
    if proc.returncode != 0:
        raise WearToolError(f"screencap failed: {err.decode('utf-8', errors='replace')}")

    payload: Dict[str, Any] = {"serial": serial, "bytes": len(data)}

    if output:
        out_path = Path(output)
    else:
        timestamp = time.strftime("%Y%m%d-%H%M%S")
        device = serial or "device"
        out_path = SCREENSHOT_DIR / f"{device}_{timestamp}.png"

    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_bytes(data)
    payload["saved_to"] = str(out_path)

    if as_base64:
        payload["base64_png"] = base64.b64encode(data).decode("ascii")

    return payload


def normalize_ui_xml(raw_xml: str) -> str:
    """Strip trailing log lines such as 'UI hierchary dumped to: /dev/tty'."""
    if "<?xml" not in raw_xml:
        return raw_xml
    start = raw_xml.index("<?xml")
    end_marker = "</hierarchy>"
    end = raw_xml.find(end_marker)
    if end == -1:
        return raw_xml[start:]
    end += len(end_marker)
    return raw_xml[start:end]


def dump_ui(serial: Optional[str], parse: bool) -> Dict[str, Any]:
    try:
        result = run_command(adb_args(serial) + ["exec-out", "uiautomator", "dump", "/dev/tty"])
        xml_data = normalize_ui_xml(result.stdout)
    except WearToolError:
        temp_xml = Path(tempfile.gettempdir()) / "wear_ui_dump.xml"
        run_adb(serial, "shell", "uiautomator", "dump", f"/sdcard/{temp_xml.name}")
        run_adb(serial, "pull", f"/sdcard/{temp_xml.name}", str(temp_xml))
        xml_data = normalize_ui_xml(temp_xml.read_text(encoding="utf-8", errors="replace"))

    payload: Dict[str, Any] = {"serial": serial, "xml": xml_data}

    if parse:
        payload["nodes"] = parse_ui_xml(xml_data)

    return payload


def parse_bounds(bounds: str) -> Tuple[int, int, int, int]:
    # Bounds follow the pattern [x1,y1][x2,y2]; pull digits to avoid locale issues.
    import re  # Local import keeps module import list tidy.

    parts = [int(p) for p in re.findall(r"-?\d+", bounds)]
    if len(parts) != 4:
        raise ValueError(f"Unrecognised bounds: {bounds}")
    return parts[0], parts[1], parts[2], parts[3]


def parse_ui_xml(xml_data: str) -> List[Dict[str, Any]]:
    nodes: List[Dict[str, Any]] = []
    root = ET.fromstring(xml_data)
    for node in root.iter():
        bounds = node.attrib.get("bounds")
        if not bounds:
            continue
        try:
            x1, y1, x2, y2 = parse_bounds(bounds)
        except ValueError:
            continue
        nodes.append(
            {
                "class": node.attrib.get("class", ""),
                "resource_id": node.attrib.get("resource-id", ""),
                "content_desc": node.attrib.get("content-desc", ""),
                "text": node.attrib.get("text", ""),
                "bounds": {"x1": x1, "y1": y1, "x2": x2, "y2": y2},
                "clickable": node.attrib.get("clickable", "false") == "true",
                "enabled": node.attrib.get("enabled", "false") == "true",
                "focusable": node.attrib.get("focusable", "false") == "true",
            }
        )
    return nodes


def tap(serial: Optional[str], x: int, y: int) -> Dict[str, Any]:
    run_adb(serial, "shell", "input", "tap", str(x), str(y))
    return {"serial": serial, "tap": {"x": x, "y": y}}


def swipe(serial: Optional[str], x1: int, y1: int, x2: int, y2: int, duration_ms: int) -> Dict[str, Any]:
    run_adb(
        serial,
        "shell",
        "input",
        "swipe",
        str(x1),
        str(y1),
        str(x2),
        str(y2),
        str(duration_ms),
    )
    return {
        "serial": serial,
        "swipe": {"from": {"x": x1, "y": y1}, "to": {"x": x2, "y": y2}, "duration_ms": duration_ms},
    }


def input_text(serial: Optional[str], text: str) -> Dict[str, Any]:
    escaped = text.replace(" ", "%s")
    run_adb(serial, "shell", "input", "text", escaped)
    return {"serial": serial, "text": text}


def keyevent(serial: Optional[str], keycode: str) -> Dict[str, Any]:
    run_adb(serial, "shell", "input", "keyevent", keycode)
    return {"serial": serial, "keyevent": keycode}


def collect_logcat(serial: Optional[str], output: Optional[str], clear: bool) -> Dict[str, Any]:
    result = run_adb(serial, "logcat", "-d")
    logs = result.stdout
    payload: Dict[str, Any] = {"serial": serial, "bytes": len(logs)}
    if output:
        Path(output).write_text(logs, encoding="utf-8")
        payload["saved_to"] = output
    else:
        payload["logcat"] = logs

    if clear:
        run_adb(serial, "logcat", "-c")
        payload["cleared"] = True
    return payload


def raw_adb(serial: Optional[str], args: List[str]) -> Dict[str, Any]:
    result = run_command(adb_args(serial) + args)
    return {
        "serial": serial,
        "stdout": result.stdout,
        "stderr": result.stderr,
        "returncode": result.returncode,
    }


class QuietHTTPRequestHandler(SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:  # noqa: A003
        sys.stderr.write(f"[serve-artifacts] {self.address_string()} - {format % args}\n")


def serve_artifacts(directory: Path, host: str, port: int) -> None:
    ensure_artifact_dirs()
    target_dir = directory.resolve()
    handler = functools.partial(QuietHTTPRequestHandler, directory=str(target_dir))
    httpd = ThreadingHTTPServer((host, port), handler)
    print_json(
        {
            "ok": True,
            "data": {
                "serving": str(target_dir),
                "host": host,
                "port": port,
                "url": f"http://{host}:{port}/",
            },
        }
    )
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        httpd.server_close()


def print_json(data: Dict[str, Any]) -> None:
    json.dump(data, sys.stdout, indent=2)
    sys.stdout.write("\n")
    sys.stdout.flush()


def handle_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Wear OS automation helper")
    parser.add_argument("--serial", help="Target emulator/device serial (adb device id)")

    subparsers = parser.add_subparsers(dest="command", required=True)

    start_parser = subparsers.add_parser("start-emulator", help="Start a Wear OS emulator headlessly")
    start_parser.add_argument("--avd", required=True, help="Name of the AVD to boot")
    start_parser.add_argument("--port", type=int, help="TCP port for emulator (implies serial emulator-<port>)")
    start_parser.add_argument("--gpu", help="GPU backend, e.g. swiftshader_indirect")
    start_parser.add_argument(
        "--extra",
        nargs=argparse.REMAINDER,
        default=[],
        help="Extra arguments passed to the emulator binary",
    )
    start_parser.add_argument("--wait", action="store_true", help="Block until sys.boot_completed = 1")
    start_parser.add_argument(
        "--env",
        action="append",
        default=[],
        help="Extra environment variables KEY=VALUE to pass to emulator",
    )

    kill_parser = subparsers.add_parser("stop-emulator", help="Terminate an emulator via adb emu kill")
    kill_parser.add_argument("--serial", required=True, help="Serial of emulator to kill (e.g. emulator-5554)")

    install_parser = subparsers.add_parser("install-apk", help="Install an APK onto the target device")
    install_parser.add_argument("--apk", required=True, help="Path to APK")

    launch_parser = subparsers.add_parser("launch-activity", help="Start an activity by component")
    launch_parser.add_argument("--component", required=True, help="Component name pkg/.Activity")

    screen_parser = subparsers.add_parser("screenshot", help="Capture a PNG screenshot")
    screen_parser.add_argument("--output", help="Optional path to save PNG")
    screen_parser.add_argument("--base64", action="store_true", help="Embed base64 data in the JSON response")

    ui_parser = subparsers.add_parser("dump-ui", help="Dump uiautomator hierarchy")
    ui_parser.add_argument("--parse", action="store_true", help="Return parsed node list")

    tap_parser = subparsers.add_parser("tap", help="Send a tap event")
    tap_parser.add_argument("x", type=int)
    tap_parser.add_argument("y", type=int)

    swipe_parser = subparsers.add_parser("swipe", help="Send a swipe gesture")
    swipe_parser.add_argument("x1", type=int)
    swipe_parser.add_argument("y1", type=int)
    swipe_parser.add_argument("x2", type=int)
    swipe_parser.add_argument("y2", type=int)
    swipe_parser.add_argument("--duration-ms", type=int, default=300)

    text_parser = subparsers.add_parser("input-text", help="Send text input")
    text_parser.add_argument("text")

    key_parser = subparsers.add_parser("keyevent", help="Send a keyevent by numeric code or name")
    key_parser.add_argument("keycode")

    logcat_parser = subparsers.add_parser("logcat", help="Fetch logcat output")
    logcat_parser.add_argument("--output", help="Optional file to save logs")
    logcat_parser.add_argument("--clear", action="store_true", help="Clear logs after fetching")

    raw_parser = subparsers.add_parser("adb", help="Run a raw adb subcommand")
    raw_parser.add_argument("args", nargs=argparse.REMAINDER, help="Args passed to adb")

    wait_parser = subparsers.add_parser("wait-for-boot", help="Block until device reports boot completed")
    wait_parser.add_argument("--timeout", type=float, default=180.0)

    serve_parser = subparsers.add_parser("serve-artifacts", help="Expose artifacts directory via HTTP")
    serve_parser.add_argument("--directory", default=str(ARTIFACTS_DIR), help="Directory to serve")
    serve_parser.add_argument("--host", default="127.0.0.1")
    serve_parser.add_argument("--port", type=int, default=8000)

    return parser.parse_args(argv)


def main(argv: Optional[List[str]] = None) -> int:
    args = handle_args(argv)
    serial_override = getattr(args, "serial", None)
    try:
        if args.command == "start-emulator":
            env_vars = {}
            for entry in args.env:
                key, sep, value = entry.partition("=")
                if not sep:
                    raise WearToolError(f"Invalid --env entry {entry!r}; expected KEY=VALUE")
                env_vars[key] = value
            result = start_emulator(
                args.avd,
                port=args.port,
                gpu=args.gpu,
                extra=args.extra,
                wait=args.wait,
                serial=serial_override,
                env=env_vars,
            )
        elif args.command == "stop-emulator":
            result = kill_emulator(args.serial)
        elif args.command == "install-apk":
            result = install_apk(serial_override, args.apk)
        elif args.command == "launch-activity":
            result = start_activity(serial_override, args.component)
        elif args.command == "screenshot":
            result = capture_screenshot(serial_override, args.output, args.base64)
        elif args.command == "dump-ui":
            result = dump_ui(serial_override, args.parse)
        elif args.command == "tap":
            result = tap(serial_override, args.x, args.y)
        elif args.command == "swipe":
            result = swipe(serial_override, args.x1, args.y1, args.x2, args.y2, args.duration_ms)
        elif args.command == "input-text":
            result = input_text(serial_override, args.text)
        elif args.command == "keyevent":
            result = keyevent(serial_override, args.keycode)
        elif args.command == "logcat":
            result = collect_logcat(serial_override, args.output, args.clear)
        elif args.command == "adb":
            if not args.args:
                raise WearToolError("adb command requires sub-arguments")
            result = raw_adb(serial_override, args.args)
        elif args.command == "wait-for-boot":
            wait_for_device(serial_override, timeout=args.timeout)
            result = {"serial": serial_override, "boot_complete": True}
        elif args.command == "serve-artifacts":
            serve_artifacts(Path(args.directory), args.host, args.port)
            return 0
        else:
            raise WearToolError(f"Unhandled command {args.command}")

        print_json({"ok": True, "data": result})
        return 0
    except WearToolError as exc:
        print_json({"ok": False, "error": str(exc)})
        return 1
    except subprocess.CalledProcessError as exc:
        print_json(
            {
                "ok": False,
                "error": f"Command exited with {exc.returncode}",
                "stdout": exc.stdout.decode("utf-8", errors="replace") if exc.stdout else "",
                "stderr": exc.stderr.decode("utf-8", errors="replace") if exc.stderr else "",
            }
        )
        return exc.returncode
    except Exception as exc:  # noqa: BLE001
        print_json({"ok": False, "error": f"Unexpected error: {exc}"})
        return 1


if __name__ == "__main__":
    sys.exit(main())
