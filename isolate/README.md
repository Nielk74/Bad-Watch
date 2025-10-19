# Wear OS Agent Tooling

This folder contains a minimal, script-first toolkit for driving a Wear OS AVD without Android Studio. It wraps the Android command line tools in a JSON-speaking CLI so an LLM agent or shell script can launch an emulator, control the app, and capture visual/UI state.

## Prerequisites
- Android SDK with `platform-tools`, `emulator`, and a Wear OS system image installed
- `adb` and `emulator` binaries on your `PATH`
- Python 3.9+ (standard library only)
- A Wear OS AVD created via `avdmanager` or the Device Manager (e.g. `Pixel_Watch_API_34`)

## Quick start
```bash
cd isolate
./wearos_tool.py start-emulator --avd Pixel_Watch_API_34 --gpu swiftshader_indirect --wait
./wearos_tool.py install-apk --apk ../app/build/outputs/apk/debug/app-debug.apk
./wearos_tool.py launch-activity --component com.badwatch.badwatch/.BadWatchActivity
./wearos_tool.py screenshot --output latest.png --base64
./wearos_tool.py dump-ui --parse
```

All commands emit JSON so downstream automation can parse structured results. See `./wearos_tool.py --help` for the full command list.

## Features
- Headless emulator control (`start-emulator`, `stop-emulator`, `wait-for-boot`)
- APK install and activity launch helpers
- Input primitives (`tap`, `swipe`, `input-text`, `keyevent`)
- UI inspection (`screenshot`, `dump-ui --parse`)
- Log retrieval (`logcat --clear`)
- Escape hatch for arbitrary adb sub-commands (`adb <args>`).

## Tips
- `start-emulator` pipes emulator stdout/stderr to a log file in the OS temp directory for debugging boot issues.
- Pass `--serial` to target a specific emulator/device (default is the first `adb` device).
- Combine with OCR by feeding the base64 PNG into your vision model or an external `tesseract` invocation.
- To script the full loop in CI, call `start-emulator --wait`, run your scenario with the other sub-commands, then finish with `stop-emulator`.
