# Wear OS headless automation

`isolate/wearos_tool.py` is a host-neutral, JSON-emitting wrapper around `emulator` and `adb`.
It can boot a Wear OS AVD without Android Studio, install and launch Bad Watch, drive input, inspect
the UI, collect logs, and retain screenshots. It is useful for repeatable UI development; it does
not replace the Pixel Watch 4 release matrix in [`device-validation.md`](device-validation.md).

## Prerequisites

- Python 3.9 or newer; the helper itself uses only the standard library.
- Android SDK `platform-tools` and `emulator` packages.
- A compatible Wear OS system image and AVD.
- Hardware virtualization available to the Android emulator.

Point the shell at the SDK explicitly instead of relying on a machine-specific `local.properties`:

```bash
export BADWATCH_ANDROID_SDK=/absolute/path/to/android-sdk
export PATH="$BADWATCH_ANDROID_SDK/cmdline-tools/latest/bin:$BADWATCH_ANDROID_SDK/emulator:$BADWATCH_ANDROID_SDK/platform-tools:$PATH"
sdkmanager --list
avdmanager list avd
```

Install a Wear image shown by `sdkmanager --list`, then create an AVD with `avdmanager` or Android
Studio's Device Manager. Bad Watch compiles and targets API 36; use a Wear image compatible with
the behavior being tested. Keep the physical Pixel Watch 4 running Android 17 / API 37 as the
release authority, and record its exact build fingerprint with every retained run.

Android Emulator acceleration uses the host's supported backend: Hypervisor Framework on macOS,
KVM on Linux, or the configured Windows hypervisor. Diagnose acceleration with
`emulator -accel-check`; a missing Linux `/dev/kvm` is a host setup issue, not a repository state.

## End-to-end loop

Run from the repository root after building the debug APK:

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

Replace `Pixel_Watch_API_36` with the name reported by `avdmanager list avd`. If more than one
device is connected, put the global selector before the subcommand:

```bash
./wearos_tool.py --serial emulator-5554 screenshot \
  --output artifacts/screenshots/home-480.png
./wearos_tool.py --serial emulator-5554 dump-ui --parse
./wearos_tool.py stop-emulator --serial emulator-5554
```

All non-server subcommands return a JSON object with either `"ok": true` and structured data or
`"ok": false` and an error. Run `./wearos_tool.py --help` and the subcommand help for the full
interface.

## Commands and artifacts

- `start-emulator`, `wait-for-boot`, and `stop-emulator` manage the AVD lifecycle.
- `install-apk` and `launch-activity` install and open a build.
- `tap`, `swipe`, `input-text`, and `keyevent` provide deterministic input primitives.
- `screenshot` and `dump-ui --parse` capture visual and accessibility-tree state.
- `logcat --clear` retrieves logs; `adb` passes an arbitrary subcommand through.
- `serve-artifacts` exposes a chosen directory over HTTP until interrupted.

Without explicit output paths, emulator logs are written to `isolate/artifacts/logs/` and
screenshots to `isolate/artifacts/screenshots/`. The repository does not currently contain a
checked-in release screenshot suite. Generated files count as release evidence only when retained
under a named release-candidate directory and referenced from the validation ledger.

The artifact server defaults to loopback:

```bash
./wearos_tool.py serve-artifacts --port 8080
```

Use `--host 0.0.0.0` only when deliberate LAN exposure is appropriate; the helper has no
authentication. Emulator evidence can catch clipping, navigation, and basic accessibility-tree
regressions, but it cannot establish real-watch touch comfort, ambient behavior, Health Services
compatibility, or battery endurance.
