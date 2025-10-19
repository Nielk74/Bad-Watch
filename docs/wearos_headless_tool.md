# Wear OS Headless Automation Tool

This document explains the helper CLI under `isolate/` that automates a Wear OS emulator without Android Studio. It also records the current host limitation discovered during verification (`/dev/kvm` missing) and how to resolve it.

## What was added
- `isolate/wearos_tool.py` — JSON-emitting command line helper to start/stop a Wear OS AVD, install APKs, launch activities, capture screenshots/UI XML, send input gestures, collect logcat, or run arbitrary `adb` subcommands.
- `isolate/README.md` — quick start guide and feature list for the tool.

These scripts depend only on the Android CLI stack (`emulator`, `adb`, `sdkmanager`, `avdmanager`) plus Python 3 and can be orchestrated by an LLM-style agent.

## Installing the emulator toolchain
1. Point `sdk.dir` in `local.properties` to the Android SDK root (already set to `/home/antoine/android-sdk`).
2. Install/update the emulator binaries:
   ```bash
   ${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager --install "emulator"
   ```
3. Install a Wear OS system image (example uses API 34 x86_64):
   ```bash
   ${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager --install \
     "system-images;android-34;android-wear;x86_64"
   ```
4. Create an AVD for the image (here we use the large round Wear profile):
   ```bash
   yes | ${SDK_ROOT}/cmdline-tools/latest/bin/avdmanager create avd \
     -n Pixel_Watch_API_34 \
     -d wearos_large_round \
     -k "system-images;android-34;android-wear;x86_64" \
     --force
   ```

## Running the helper CLI
Add `emulator` and `platform-tools` to the `PATH` so the script can find `adb` and `emulator`:
```bash
export PATH=${SDK_ROOT}/emulator:${SDK_ROOT}/platform-tools:$PATH
cd isolate
./wearos_tool.py start-emulator --avd Pixel_Watch_API_34 --gpu swiftshader_indirect --wait
```

Subcommands return JSON. For example, to capture a screenshot and dump the UI:
```bash
./wearos_tool.py screenshot --output /tmp/watch.png --base64
./wearos_tool.py dump-ui --parse > /tmp/watch_ui.json
```
If multiple devices are connected, prepend `--serial <device-id>` before the subcommand to target a specific emulator.

Artifacts captured without explicit paths are stored under `isolate/artifacts/`:
- Emulator boot logs → `isolate/artifacts/logs/<avd>_<timestamp>.log`
- Screenshots → `isolate/artifacts/screenshots/<serial>_<timestamp>.png`

## Current host limitation (`/dev/kvm` missing)
While verifying the tool the emulator failed to boot because hardware acceleration is disabled:
```
ERROR | x86_64 emulation currently requires hardware acceleration!
CPU acceleration status: /dev/kvm is not found: VT disabled in BIOS or KVM kernel module not loaded
```
(See `/tmp/Pixel_Watch_API_34_emulator.log` for the full log.)

### How to fix
1. **If running directly on bare metal Linux:**
   - Enable virtualization in BIOS/UEFI (Intel VT-x/AMD-V).
   - Install KVM modules, e.g. on Ubuntu:
     ```bash
     sudo apt install qemu-kvm libvirt-daemon-system
     sudo adduser $USER kvm
     sudo adduser $USER libvirt
     ```
   - Log out/in to refresh group membership.
2. **If running inside a VM (e.g., WSL2, cloud runner):**
   - Ensure nested virtualization is enabled on the host hypervisor.
   - For WSL2: `wsl --update` and enable nested virtualization via Hyper-V settings.
3. Reboot the host or VM, then confirm `/dev/kvm` exists:
   ```bash
   ls -l /dev/kvm
   ```

Once `/dev/kvm` is present the emulator should boot successfully when you rerun:
```bash
PATH=${SDK_ROOT}/emulator:${SDK_ROOT}/platform-tools:$PATH \
./wearos_tool.py start-emulator --avd Pixel_Watch_API_34 --gpu swiftshader_indirect --wait
```

## Verification after enabling KVM
With `/dev/kvm` available we ran the full loop:

1. Booted the headless emulator (command above) — it reported success and logged output to `/tmp/Pixel_Watch_API_34_emulator.log`.
2. Confirmed the device was visible via `adb devices` (`emulator-5554	device`).
3. Captured a screenshot:
   ```bash
   ./wearos_tool.py --serial emulator-5554 screenshot --output /tmp/watch.png --base64
   ```
   The JSON response included `bytes: 7906` and `saved_to: /tmp/watch.png`.
4. Dumped the UI hierarchy with parsing:
   ```bash
   ./wearos_tool.py --serial emulator-5554 dump-ui --parse
   ```
   The tool normalises the XML (trims `uiautomator` footer lines) and returns a `nodes` array containing bounds/text metadata for each element.
5. Stopped the emulator cleanly:
   ```bash
   ./wearos_tool.py stop-emulator --serial emulator-5554
   ```

This confirms the helper works end-to-end when hardware acceleration is enabled.

## Serving artifacts via HTTP
To browse logs or screenshots quickly, start the built-in file server (runs until you press Ctrl+C):
```bash
./wearos_tool.py serve-artifacts --host 0.0.0.0 --port 8080
```
The command prints the URL in JSON (`http://0.0.0.0:8080/`) and serves the `isolate/artifacts/` directory. Use `--directory <path>` to serve a different folder.

## Suggested verification flow (after enabling KVM)
1. Boot the emulator with `start-emulator --wait`.
2. Use `wait-for-boot` if invoking `start-emulator` without `--wait`.
3. Install and launch the app:
   ```bash
   ./wearos_tool.py install-apk --apk ../app/build/outputs/apk/debug/app-debug.apk
   ./wearos_tool.py launch-activity --component com.badwatch.badwatch/.BadWatchActivity
   ```
4. Capture state via `screenshot` and `dump-ui`.
5. After tests, terminate with:
   ```bash
   ./wearos_tool.py stop-emulator --serial emulator-5554
   ```

This workflow gives agents or scripts full control over the Wear OS app lifecycle without opening Android Studio.
