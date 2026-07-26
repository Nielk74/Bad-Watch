# Wear device validation

Bad Watch treats reliability, usability, and battery life as measured release properties. JVM
tests and emulator automation are the software gate; retained evidence from a physical watch is a
separate release gate.

The active validation target is a 480 x 480 Pixel Watch 4 running Android 17 / API 37; the APK
itself targets SDK 36. Record the
exact model, build fingerprint, app version, APK SHA-256, locale, font size, AOD state, connection
mode, and charger state with every release-candidate run.

## Software gate

Run from the repository root:

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 -m py_compile tools/*.py tooling/*.py isolate/wearos_tool.py
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace --no-daemon
```

These checks cover deterministic core behavior, durable stores and recovery, Health Services
decisions, sync/server contracts, and Python tooling. They do not prove OEM lifecycle behavior,
ambient legibility, accessibility, release-network policy, or battery drain.

## Session and battery probe

The probe needs an unlocked watch reachable by `adb`, no active Bad Watch session, and a debug APK.
It reads app-private session files through `run-as`, so it cannot inspect a release APK.

Five-minute lifecycle smoke:

```bash
./tooling/wear_session_probe.py --serial <adb-serial> --duration-minutes 5
```

Three-hour release gate:

```bash
./tooling/wear_session_probe.py \
  --serial <adb-serial> \
  --duration-minutes 180 \
  --sample-seconds 60 \
  --output build/wear-session-probe
```

The probe refuses to overlap an active session, captures device/app metadata, starts through the
Tile-compatible Activity extra, samples service and battery state with the display free to sleep,
then stops through the visible **Stop & save** action. It requires exactly one new valid session
and a saved duration within five seconds of the recorder's start-to-stop wall interval. Every
monitored reading must remain asleep/dozing, foreground with the health service type, and show a
strictly advancing sensor checkpoint. Each run
writes `start.png`, `recap.png`, and `report.json` in a timestamped directory without clearing app
data or deleting the resulting session.

A short run is smoke evidence. A powered 180-minute run can satisfy the screen-off lifecycle and
duration gate, but not a battery-drain claim. `batteryDeltaPercent` is valid only when every
reading reports the watch unpowered; battery endurance remains unmeasured until a separate full
run is performed off charger under recorded, repeatable conditions. Release APK installation,
version/signature inspection, and dashboard setup must be checked separately.

Process-death recovery probe:

```bash
./tooling/wear_recovery_probe.py \
  --serial <adb-serial> \
  --output build/wear-recovery-probe
```

This probe requires one stable session identity and start time across a forced process stop, a
frozen checkpoint while the process is absent, advancing motion samples after restoration, an
incremented recovery count, one saved `Partial` session, and retained before/after/recap
screenshots. The saved elapsed span intentionally includes the interruption, while the frozen
sample count proves that the app does not invent sensor coverage during it; the report quantifies
both semantics.

## Physical-watch release matrix

Use one retained artifact directory per release candidate. Do not replace **Pending** with
**Passed** without adding its path, UTC date, app version, and device build fingerprint.

| Gate | Evidence to retain | v0.3 release-candidate status |
| --- | --- | --- |
| Release install and first-run permissions | APK hash/version/signature, install log, permission notes | **Pending:** add final RC evidence path. |
| Start, live HUD, stop, save with HR granted and denied | Start/live/recap images plus session/report IDs | **Pending:** add final RC evidence path. |
| Activity recreation during recording | Log and stable session ID/start-time comparison | **Pending:** add final RC evidence path. |
| Process death and journal recovery | Before/after export, stable identity, `Partial` quality, missing-interval note | **Pending:** add final RC evidence path. |
| Offline save then retry/sync | Session ID and before/after sync state against authenticated HTTPS server | **Pending:** add final RC evidence path. |
| Home, live, recap, review/corrections, History, Progress, Training, Match, Settings, Tile, complication, ambient | Named 480×480 screenshot set | **Pending:** add final RC evidence path. |
| Normal/enlarged text and English/French spot checks | Screenshot matrix plus clipping/accessibility notes | **Pending:** add final RC evidence path. |
| 180-minute powered screen-off lifecycle | Probe `report.json`, `start.png`, `recap.png`, charger state and lifecycle result | **Pending:** no qualifying run is documented yet. |
| 180-minute unpowered battery measurement | Probe report with every reading unpowered and measured battery delta | **Not claimed:** no qualifying run is documented. |

Active-session checkpoint recovery, match action-log recovery, and shadow-routine recovery are
implemented and covered by automated tests. The physical process-death rows above remain required:
code coverage is not device evidence. Likewise, an emulator screenshot does not prove touch
comfort, ambient behavior, OEM Health Services behavior, or endurance on the Pixel Watch 4.
