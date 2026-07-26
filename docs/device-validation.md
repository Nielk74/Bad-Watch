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

The release regression for a failed recording uses a sensor flow that emits one real sample and
then throws. It proves that the failure state retains the exact journal, Dismiss is
non-destructive, confirmed Discard clears the journal without creating a session export, and the
next start receives a fresh session ID. The EN/FR failure screen routes that destructive choice
through `SessionService`, so recorder, optical HR, foreground state, and checkpoint use one
cleanup command rather than independent UI mutations.

Detection Lab has separate terminal regressions. A collection failure cancels sensors and the
health foreground service directly; a completed-capture storage failure exposes Retry save,
preserves the pending capture ID, writes exactly once, and enqueues sync only after durable
success. Dismiss/back and retry actions are serialized service commands, closing races between UI
acknowledgement and sensor/service cleanup.

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

The active v0.3 ledger is
[`evidence/v0.3-pixel-watch-4/README.md`](evidence/v0.3-pixel-watch-4/README.md). It records the
2026-07-26 UTC run of app version 0.3.0 on
`google/kenari_btwifi/kenari_btwifi:17/CP2A.260603.001.S1/15396605:user/release-keys`. The current
frozen app checkpoint is `6f6f6cd9531040072bc153b365c0c515b0a40781`; its installed debug
APK SHA-256 is `28076fc03319ca15df92760a8d20b993af2f8ae8e129ffe61e021876eed0169d`.
The device's installed `base.apk` matched byte-for-byte and its v2 signature verified. The endurance
probe uses tooling checkpoint `6a6c568e0e156fd551ae1388b9502d261b61b796`, which follows the
full localized Stop-and-save accessibility name behind the compact Finish/Finir label. Earlier retained
permission, recovery, sync, ongoing, match, shadow, Tile, and complication evidence names its own
source/APK scope in the ledger rather than being relabelled as final-build evidence.

The frozen build also produced unsigned release APK
`e23febe396dba1c3b8f34fcda4243ae5a598187ec4b9d8cb7d2b902243da5642` and unsigned AAB
`f3335a55b28cddb4ed202fc3d929ef72524b44d3507d56f192836a3fb796557f`.
Those are reproducible assembly artifacts, not installed/signed release-distribution evidence.
The frozen app checkpoint's clean gate passed five Python tests, `py_compile`, `xmllint`, and
Gradle clean/test/lint/debug/release APK/release bundle with all 132 Gradle tasks executed. Probe
tooling revision `6a6c568` adds three selector regressions, so the current Python suite passes all
eight tests. JVM layout regressions lock in the `1.20` threshold for dense metrics, the 48 dp
live-action lane, and compact match/shadow layouts; the live action keeps the full Stop-and-save
semantic name behind visible **Finish** / **Finir** text.

| Gate | Evidence to retain | v0.3 release-candidate status |
| --- | --- | --- |
| Debug RC install and first-run permissions | APK hash/version/signature, package inspection, first-run images, permission notes | **Passed (2026-07-26 UTC):** [candidate identity and first-run evidence](evidence/v0.3-pixel-watch-4/README.md#install-onboarding-and-optional-permissions). |
| Start, live HUD, stop, and save with HR granted and denied | Start/live/review/recap images plus session IDs and checkpoints | **Passed (2026-07-26 UTC):** [granted and denied session evidence](evidence/v0.3-pixel-watch-4/README.md#session-recording-with-heart-rate-access-granted). The granted watch was off wrist, so this does not claim optical BPM acquisition. |
| Activity recreation during recording | Stable session ID/start-time and advancing-checkpoint comparison | **Passed (2026-07-26 UTC):** [English-to-French recreation evidence](evidence/v0.3-pixel-watch-4/README.md#heart-rate-denial-and-activity-recreation). |
| Process death and journal recovery | Before/after export, stable identity, `Partial` quality, missing-interval note | **Passed (2026-07-26 UTC):** [report and screenshots](evidence/v0.3-pixel-watch-4/README.md#process-death-and-journal-recovery). |
| Detection Lab screen-off acquisition | Before/after images plus stable process/FGS and advancing Android sensor-client duration | **Passed (2026-07-26 UTC):** [doze acquisition evidence](evidence/v0.3-pixel-watch-4/README.md#detection-lab-through-screen-off). No stationary example was misrepresented as a saved swing. |
| Offline save then retry/sync | Session ID and before/after state against an authenticated debug server over `adb reverse`; automated release cleartext-block assertion | **Passed (2026-07-26 UTC):** [offline failure, authenticated retry, exact markers, and server acceptance](evidence/v0.3-pixel-watch-4/README.md#offline-save-and-authenticated-retry). |
| Ongoing Activity and one-tap return | Watch-face indicator, notification/service state, stable session identity/checkpoint, and return image | **Passed (2026-07-26 UTC):** [motion-only ongoing evidence](evidence/v0.3-pixel-watch-4/README.md#ongoing-session-surface-and-one-tap-return). |
| Match and shadow process recovery | Before/after state, action/cue identity, pause timing, and cleanup evidence | **Passed (2026-07-26 UTC):** [match](evidence/v0.3-pixel-watch-4/README.md#manual-match-recovery) and [shadow](evidence/v0.3-pixel-watch-4/README.md#shadow-practice-recovery) reports retain exact identity/timing and visible cleanup. |
| Tile rendering and cold-start Start | System Tile, one-shot launch into live recording, health FGS, and visible cleanup | **Passed (2026-07-26 UTC):** [system render and cold-start recording](evidence/v0.3-pixel-watch-4/README.md#tile-rendering-and-start-contract). |
| Tile Start while `MainActivity` already owns the task | Exact component launch, one-shot `onNewIntent` delivery, one recording, and visible cleanup | **Passed (2026-07-26 UTC):** [the frozen-APK report](evidence/v0.3-pixel-watch-4/tile/report.json) proves the same task, ActivityRecord, and process received one start; samples advanced, and visible cleanup left one saved session with no checkpoint/service. |
| Watch-face complication | System provider binding, live value, tap action, and restoration of the user's slot/layout | **Passed (2026-07-26 UTC):** [system-rendered seven-day value](evidence/v0.3-pixel-watch-4/README.md#watch-face-complication). |
| Active ambient HUD | Ambient callback/exit diagnostics plus 480×480 simplified live HUD | **Passed (2026-07-26 UTC):** [real ambient lifecycle evidence](evidence/v0.3-pixel-watch-4/README.md#ambient-hud). This is not a battery claim. |
| Core normal-size screens | Named 480×480 Home, live, recap/review, History, Progress, Training, Match/interval, Detection Lab, Settings, and permission images | **Passed (2026-07-26 UTC):** [frozen-APK inventory and report](evidence/v0.3-pixel-watch-4/README.md#final-visual-inventory-and-enlarged-text). |
| English/French spot checks | Screenshot matrix plus recreation/semantics notes | **Passed (2026-07-26 UTC):** final English navigation/active states, French live/review/recap recreation, and French longest-cue/paused/finish-dialog shadow states are retained. No fluent-review claim is made. |
| Enlarged text | `1.30` Home, History, Progress, Settings, live, review/recap, safe failure, match/interval, practice/shadow, and permission matrix with reflow, scroll reachability, and no collision/clipping defects | **Passed (2026-07-26 UTC):** [machine-readable final matrix](evidence/v0.3-pixel-watch-4/accessibility/report.json) and 26 validated 480×480 captures. |
| 180-minute powered screen-off lifecycle | Probe `report.json`, `start.png`, `recap.png`, charger state and lifecycle result | **Pending active gate:** only a completed frozen-APK report can pass this row; no partial run is counted. |
| 180-minute unpowered battery measurement | Probe report with every reading unpowered and measured battery delta | **Not claimed:** no qualifying run is documented. |

The physical ledger covers the normal/enlarged destinations, platform surfaces, and all three
durable recovery paths (session, match, and shadow). The only remaining release gate is the
powered 180-minute probe on the same final app checkpoint. An emulator screenshot does not prove
touch comfort, ambient behavior, OEM Health Services behavior, or endurance on the Pixel Watch 4;
those rows use the retained native hardware evidence above. A powered probe also cannot prove
battery drain, regardless of its duration.
