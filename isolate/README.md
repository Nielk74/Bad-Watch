# Wear OS agent tooling

`wearos_tool.py` is a small JSON-speaking CLI for driving a Wear OS AVD without Android Studio.
It wraps the Android command-line tools so scripts and agents can reproduce navigation, input,
screenshots, UI-tree inspection, and log collection.

## Prerequisites

- Python 3.9 or newer;
- Android SDK `adb` and `emulator` on `PATH`;
- a configured Wear OS AVD and working host acceleration.

The app compiles and targets API 36. Use an emulator image appropriate to the scenario, while
treating the 480×480 Pixel Watch 4 on Android 16 / API 36 as the physical release target.

## Quick start

Build at the repository root, then run the helper from this directory:

```bash
./gradlew :app:assembleDebug
cd isolate
./wearos_tool.py start-emulator --avd Pixel_Watch_API_36 --gpu swiftshader_indirect --wait
./wearos_tool.py install-apk --apk ../app/build/outputs/apk/debug/app-debug.apk
./wearos_tool.py launch-activity \
  --component com.badwatch.badwatch/com.badwatch.app.MainActivity
./wearos_tool.py screenshot --output artifacts/screenshots/home.png
./wearos_tool.py dump-ui --parse
```

Replace `Pixel_Watch_API_36` with an AVD reported by `avdmanager list avd`. With multiple devices,
place the global serial selector before the subcommand:

```bash
./wearos_tool.py --serial emulator-5554 screenshot
./wearos_tool.py --serial emulator-5554 logcat --output artifacts/logs/app.txt
./wearos_tool.py stop-emulator --serial emulator-5554
```

## Available operations

- AVD lifecycle: `start-emulator`, `wait-for-boot`, `stop-emulator`
- App lifecycle: `install-apk`, `launch-activity`
- Input: `tap`, `swipe`, `input-text`, `keyevent`
- Inspection: `screenshot`, `dump-ui --parse`, `logcat`
- Escape hatch: `adb`
- Local artifact browser: `serve-artifacts`

Run `./wearos_tool.py --help` for the complete interface. Default logs and screenshots land under
`isolate/artifacts/`; no release screenshot matrix is currently checked in. Retain and name any
files used as evidence. Emulator output does not satisfy the physical-device, ambient, accessibility,
Health Services, or 180-minute battery gates in
[`docs/device-validation.md`](../docs/device-validation.md).
