# Wear device validation

Bad Watch treats reliability, usability, and battery life as measured release properties. JVM
tests and emulator automation are the software gate; retained evidence from a physical watch is a
separate release gate.

The active validation target is a 480 x 480 Pixel Watch 4 running Android 17 / API 37; the APK
itself targets SDK 36. Record the
exact model, build fingerprint, app version, APK SHA-256, locale, font size, AOD state, connection
mode, and charger state with every release-candidate run.

## Software gate

Node 22 is required locally as of browser/server repository checkpoint `836f116`, because
`:server:test` depends on `:server:dashboardBrowserTest`. Run from the repository root:

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 -m py_compile tools/*.py tooling/*.py isolate/wearos_tool.py
./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease --stacktrace --no-daemon
```

That is the APK-focused CI/release workflow. Freeze a final local candidate with the broader
artifact gate as well:

```bash
./gradlew clean test lint assembleDebug assembleRelease bundleRelease --stacktrace --no-daemon
```

At precursor Wear checkpoint `0bdbe98`, before the browser task joined `:server:test`, all 31
Python unit tests, `py_compile`, resource XML validation, browser JavaScript syntax validation,
and that Gradle artifact gate passed. Gradle reported 132 tasks: 129 executed and three up-to-date.
This is a historical count for that precursor artifact run.

At browser/server repository checkpoint `836f116`, with the Wear APK unchanged, `:server:test`
depends on `:server:dashboardBrowserTest` and requires Node 22. It executes six journeys against
JavaScript extracted from shipped `index.html`: stale-token prompt/retry; URL-filter
hydrate/apply/error/reset; deep-linked reviewed detail; successful revisioned diary save and
aggregate refresh; HTTP 409 reload/conflict replacement; and archive-restore success/error. This
proves client state/request contracts, not responsive layout or real-browser rendering.

At truthful-composition source `48d101fbd55520c983e33979f5a7733a50fd73ca`, the broader artifact
gate passed all 31 Python tests, `py_compile`, resource XML validation, browser JavaScript syntax
validation, all six Node 22 browser journeys, and 133 Gradle tasks: 130 executed and three
up-to-date. Release source `19ec9d4` adds the physically verified double-digit-minute duration
reflow and passed the same Python/browser/JVM/lint/debug/release-APK/release-bundle gate.

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
./tooling/wear_session_probe.py \
  --serial <adb-serial> \
  --duration-minutes 5 \
  --suppress-system-wakes
```

Three-hour release gate:

```bash
./tooling/wear_session_probe.py \
  --serial <adb-serial> \
  --duration-minutes 180 \
  --sample-seconds 60 \
  --suppress-system-wakes \
  --output build/wear-session-probe
```

The probe refuses to overlap an active session, captures device/app metadata, starts through the
Tile-compatible Activity extra, samples service and battery state with the display free to sleep,
then stops through the visible **Stop & save** action. It requires exactly one new valid session
and a saved duration within five seconds of the recorder's start-to-stop wall interval. Every
monitored reading must remain asleep/dozing, foreground with the health service type, and show a
strictly advancing sensor checkpoint. Each run writes `start.png`, a separately classified settled
`recap.png`, and `report.json`; when the optional diary appears it also retains
`post-stop-review.png`. The report records `postStopReviewCaptured` and
`savedSessionRecapVerified`, and the probe neither clears app data nor deletes the resulting
session.

On a powered Wear watch, charging UI and notifications can wake the display even when the app is
correct. `--suppress-system-wakes` transactionally snapshots plugged-in stay-awake, Theater Mode,
and Android zen/DND; applies `0`, `1`, and `none` during monitoring; records original/active/
restored values in the report; and restores the exact originals on success or failure. It exits
Theater Mode before the visible stop and retries wake/Activity delivery while System UI settles.
These controls prevent unrelated system wakes; they do not alter battery telemetry or turn a
powered run into battery evidence.

A short run is smoke evidence. A powered 180-minute run can satisfy the screen-off lifecycle and
duration gate, but not a battery-drain claim. `batteryDeltaPercent` is valid only when every
reading reports the watch unpowered; battery endurance remains unmeasured until a separate full
run is performed off charger under recorded, repeatable conditions. Release APK installation,
version/signature inspection, and dashboard setup must be checked separately.

For v0.3, the maintainer explicitly accepted a shorter final-source run rather than repeat a full
three hours. That decision is documented as combined evidence: a 56:50 final-source dozing smoke,
the passing pre-final 180-minute machine report, the final-source recovery report, and the complete
software gate. It does not redefine the generic three-hour probe, manufacture a final report, or
support a battery-drain claim.

Process-death recovery probe:

```bash
./tooling/wear_recovery_probe.py \
  --serial <adb-serial> \
  --output build/wear-recovery-probe
```

This probe requires one stable session identity and start time across a forced process stop, a
frozen checkpoint while the process is absent, advancing motion samples after restoration, an
incremented recovery count, one saved `Partial` session, and retained before/after/post-stop
screenshots. Its schema-2 gate also requires valid ordered process-absence intervals, exact
recovered-checkpoint/final-session equality, coverage from the last durable journal timestamp
through the restart request, and overlap with the forced stop. The saved elapsed span intentionally
includes that conservative unobserved boundary; no sensor samples are invented, detections cannot
bridge it, and its effective-window overlap is never counted as inferred quiet time. The tool's
`recap.png` is the immediate post-stop surface, so a settled Summary must be retained separately
when visual recap copy is itself an evidence claim.

## Physical-watch release matrix

Use one retained artifact directory per release candidate. Do not replace **Pending** with
**Passed** without adding its path, UTC date, app version, and device build fingerprint.

The active v0.3 ledger is
[`evidence/v0.3-pixel-watch-4/README.md`](evidence/v0.3-pixel-watch-4/README.md). It records the
2026-07-26 UTC run of app version 0.3.0 on
`google/kenari_btwifi/kenari_btwifi:17/CP2A.260603.001.S1/15396605:user/release-keys`. The final
app/APK source is `19ec9d427967c71adfe4d71efc7907b9950fec3c`; its installed debug APK
SHA-256 is `bd3b5aa0d3b5815325ed789016e573453534efe4de8617198e475836bcb10b8b`.
The device's pulled `base.apk` matched byte-for-byte, its v2 debug signature verified, and package
inspection reported `com.badwatch.badwatch`, target SDK 36, version `0.3.0` (`versionCode=300`),
and last update `2026-07-26 16:44:30`. The session and recovery probe implementations are
traceable to
`e9719a57d6a44463ff554e596de55ee95de41ef6` and
`5318deb910ea3aae0f77d41e8c5e227ae77f39f8`, respectively. Earlier retained permission, sync,
ongoing, match, shadow, Tile, complication, and broad visual evidence names its own source/APK scope
in the ledger rather than being relabelled as final-build evidence.

The final source also produced unsigned release APK
`9f96cba0c21cfc8ade7788d6065be6a45957dfda744aedb55706251c9323fa11` and unsigned AAB
`9306d3f4b36fd7cdc26542af2cb24d3beb47d69acfcce9107beb7c23768f51f6`.
Those are reproducible assembly artifacts, not installed/signed release-distribution evidence.
The final source's clean gate passed all 31 Python tests, `py_compile`, resource XML and browser
JavaScript syntax validation, all six Node 22 browser journeys, and Gradle
clean/test/lint/debug/release-APK/release-bundle. It reported 133 tasks: 130 executed and three
up-to-date. JVM layout regressions lock in the `1.20` threshold for dense metrics, the 48 dp
live-action lane, compact match/shadow layouts, and the ten-minute-plus full-width duration row; the live
action keeps the full Stop-and-save semantic name behind visible **Finish** / **Finir** text.

The authoritative final-source schema-2
[recovery report](evidence/v0.3-pixel-watch-4/recovery-final/20260726T133307Z/report.json) passes.
Session `f59594fe-5c7d-4e6b-8421-4cfcdd145d11` kept one ID/start, froze at 2,390 samples while the
process was absent, reached 4,782 after recovery, and saved one `Partial` session whose duration
and elapsed wall span both equal 73,038 ms. Its single 16,307 ms process-absence interval is
`1785072813435..1785072829742`; it is valid, exactly matches the recovered checkpoint, covers
journal-to-restart, overlaps the 3,707 ms host-observed forced-stop window, and passes every
provenance boolean.

The immediately preceding
[recovery attempt](evidence/v0.3-pixel-watch-4/recovery-final/20260726T133059Z/report.json) records
a failed release-gate result (`result=false`; the report field is `"result": "fail"`) because the
default post-restart settle window observed no sample progress. Its stable identity and schema-2
provenance checks passed, but the sample-advance gate did not. The follow-up above used
`--settle-seconds 30` and passed; the first result is a settle-window probe finding, not an app
failure. The older `0bdbe98`
[schema-2 pass](evidence/v0.3-pixel-watch-4/recovery-final/20260726T121444Z/report.json) remains a
precursor for the long-layout and recovery work, not the authoritative final-source result.

The older [powered 180-minute report](evidence/v0.3-pixel-watch-4/endurance/20260726T071128Z/report.json)
passed on app checkpoint `6f6f6cd`, but it predates the final candidate's long-duration, sync, and
process-absence changes. It is retained as explicitly pre-final long-duration evidence. The
[release lifecycle observation](evidence/v0.3-pixel-watch-4/endurance/20260726T133709Z/STOPPED_EARLY.md)
was intentionally stopped at 56:50 after 339,595 processed samples; it saved exactly one session
with no process-absence gap on `48d101f`; release `19ec9d4` then physically verified the corrected
56:50 recap. This scoped evidence plus the earlier three-hour report, final recovery probe, and
software gate is the accepted v0.3 lifecycle evidence. A release-source three-hour report is not
claimed.

Checkpoint `48d101f` device retests cover start/recover/save, exact schema-2 provenance, and the
truthful recovered Summary notice/composition; release `19ec9d4` covers current artifact identity
and the corrected 56:50 recap. Hour-plus Summary and History at
normal/`1.30` text remain physical evidence from immediate precursor `0bdbe98` and are not
relabelled as `48d101f` captures. Final-source Home, Progress, Tile rendering/gap marker,
recovered-History marker, and Live details were not physically recaptured; those paths have
automated regression coverage only. The broader rows below name the older checkpoint that supplies
their physical evidence.

| Gate | Evidence to retain | v0.3 release-candidate status |
| --- | --- | --- |
| Debug RC install and first-run permissions | APK hash/version/signature, package inspection, first-run images, permission notes | **Passed with explicit scope (2026-07-26 UTC):** release `19ec9d4` install/hash/version/signature identity is retained; [first-run evidence](evidence/v0.3-pixel-watch-4/README.md#install-onboarding-and-optional-permissions) is from earlier checkpoint `99b5f62` and was not repeated on the release APK. |
| Start, live HUD, stop, and save with HR granted and denied | Start/live/review/recap images plus session IDs and checkpoints | **Passed with explicit scope (2026-07-26 UTC):** [granted and denied session evidence](evidence/v0.3-pixel-watch-4/README.md#session-recording-with-heart-rate-access-granted) is from `99b5f62`; final `48d101f` start/recover/save is retained by the schema-2 recovery probe, but final Live details were not recaptured. The granted watch was off wrist, so this does not claim optical BPM acquisition. |
| Activity recreation during recording | Stable session ID/start-time and advancing-checkpoint comparison | **Passed for checkpoint `99b5f62` (2026-07-26 UTC):** [English-to-French recreation evidence](evidence/v0.3-pixel-watch-4/README.md#heart-rate-denial-and-activity-recreation) was not repeated on `48d101f`. |
| Process death and journal recovery | Before/after export, stable identity, `Partial` quality, exact checkpoint/session process-absence intervals, and visible boundary copy | **Passed on checkpoint `48d101f` (2026-07-26 UTC):** the [schema-2 report and screenshots](evidence/v0.3-pixel-watch-4/README.md#process-death-and-journal-recovery) prove every provenance boolean and the exact 16,307 ms boundary. Release `19ec9d4` changes only the shared duration-row breakpoint and retains the automated recovery suite. |
| Detection Lab screen-off acquisition | Before/after images plus stable process/FGS and advancing Android sensor-client duration | **Passed for checkpoint `99b5f62` (2026-07-26 UTC):** [doze acquisition evidence](evidence/v0.3-pixel-watch-4/README.md#detection-lab-through-screen-off). No stationary example was misrepresented as a saved swing; this was not repeated on `48d101f`. |
| Offline save then retry/sync | Session ID and before/after state against an authenticated debug server over `adb reverse`; automated release cleartext-block assertion | **Passed for checkpoint `99b5f62` (2026-07-26 UTC):** [offline failure and authenticated acceptance](evidence/v0.3-pixel-watch-4/README.md#offline-save-and-authenticated-retry) prove that scoped path. Final incomplete-acknowledgement behavior has automated coverage only. |
| Ongoing Activity and one-tap return | Watch-face indicator, notification/service state, stable session identity/checkpoint, and return image | **Passed for checkpoint `c5a1a67` (2026-07-26 UTC):** [motion-only ongoing evidence](evidence/v0.3-pixel-watch-4/README.md#ongoing-session-surface-and-one-tap-return); it was not physically repeated on `48d101f`. |
| Match and shadow process recovery | Before/after state, action/cue identity, pause timing, and cleanup evidence | **Passed on scoped `c5a1a67`/`6f6f6cd` candidates (2026-07-26 UTC):** [match](evidence/v0.3-pixel-watch-4/README.md#manual-match-recovery) and [shadow](evidence/v0.3-pixel-watch-4/README.md#shadow-practice-recovery) reports retain exact identity/timing and visible cleanup; these were not repeated on `48d101f`. |
| Tile rendering and cold-start Start | System Tile, one-shot launch into live recording, health FGS, and visible cleanup | **Passed for checkpoint `c5a1a67` (2026-07-26 UTC):** [system render and cold-start recording](evidence/v0.3-pixel-watch-4/README.md#tile-rendering-and-start-contract). Final Tile rendering and the exact gap marker were not physically recaptured; final-source regressions cover them. |
| Tile Start while `MainActivity` already owns the task | Exact component launch, one-shot `onNewIntent` delivery, one recording, and visible cleanup | **Passed (2026-07-26 UTC):** the [scoped `6f6f6cd` report](evidence/v0.3-pixel-watch-4/tile/report.json) proves the same task, ActivityRecord, and process received one start; samples advanced, and visible cleanup left one saved session with no checkpoint/service. Final-source regressions retain that launch contract. |
| Watch-face complication | System provider binding, live value, tap action, and restoration of the user's slot/layout | **Passed for checkpoint `c5a1a67` (2026-07-26 UTC):** [system-rendered seven-day value](evidence/v0.3-pixel-watch-4/README.md#watch-face-complication); it was not repeated on `48d101f`. |
| Active ambient HUD | Ambient callback/exit diagnostics plus 480×480 simplified live HUD | **Passed for checkpoint `008a4f4` (2026-07-26 UTC):** [real ambient lifecycle evidence](evidence/v0.3-pixel-watch-4/README.md#ambient-hud). It was not repeated on `48d101f` and is not a battery claim. |
| Core normal-size screens | Named 480×480 Home, live, recap/review, History, Progress, Training, Match/interval, Detection Lab, Settings, and permission images | **Passed only as a source-scoped combined inventory (2026-07-26 UTC):** the [earlier broad inventory and exact final subsets](evidence/v0.3-pixel-watch-4/README.md#final-visual-inventory-and-enlarged-text) are retained. `0bdbe98` captures cover hour-plus Summary/History and pre-label recovery structure; `48d101f` captures cover the truthful recovered Summary notice/composition. Final-source Home, Progress, Tile, recovered History, and Live were not captured. |
| English/French spot checks | Screenshot matrix plus recreation/semantics notes | **Passed on earlier scoped candidates (2026-07-26 UTC):** English navigation/active states, French live/review/recap recreation, and French longest-cue/paused/finish-dialog shadow states are retained. They were not repeated on final `48d101f`; no fluent-review claim is made. |
| Enlarged text | `1.30` Home, History, Progress, Settings, live, review/recap, safe failure, match/interval, practice/shadow, and permission matrix with reflow, scroll reachability, and no collision/clipping defects | **Passed as combined scoped evidence (2026-07-26 UTC):** the historical [26-image matrix](evidence/v0.3-pixel-watch-4/accessibility/report.json) covers the broader destinations; precursor `0bdbe98` physically retests hour-plus Summary/History at `1.30`. The final `48d101f` recovered Summary/composition captures are separate evidence, not part of the enlarged matrix. Final Home, Progress, Tile, recovered History, and Live have automated coverage only. |
| Hour-plus duration and recovery-coverage UI | `0bdbe98` normal/`1.30` Summary and History plus final `48d101f` gap notice/composition images | **Passed with split source scope (2026-07-26 UTC):** [retained 480×480 captures](evidence/v0.3-pixel-watch-4/README.md#final-frozen-apk-long-session-and-recovery-coverage) show precursor `3:00:12` layouts plus the final `0:16` known-unobserved notice and Detected/No detected play/Unobserved legend. Screenshots 65/66 are pre-label history; 67/68 prove the final wording. |
| Release lifecycle evidence | Lifecycle observation, `start.png`, optional post-stop diary, original/fixed recap, charger state, and saved-session identity | **Accepted with explicit scope (2026-07-26 UTC):** checkpoint `48d101f` ran dozing for 56:50, reached 339,595 processed samples, and saved exactly one gap-free session; release `19ec9d4` byte-matched its installed APK and physically fixed the complete 56:50 Summary render. The passing `6f6f6cd` 180-minute report is pre-final long-duration corroboration; a release-source three-hour report is not claimed. |
| 180-minute unpowered battery measurement | Probe report with every reading unpowered and measured battery delta | **Not claimed:** no qualifying run is documented. |

The physical ledger combines source-scoped native-watch evidence across candidates. Checkpoint
`48d101f` device retests cover start/recover/save, exact schema-2 provenance, and the truthful
recovered Summary notice/composition; release `19ec9d4` covers artifact identity and the corrected
56:50 recap. Precursor `0bdbe98` supplies the hour-plus
Summary and History captures at normal/`1.30` text. Final-source Home, Progress, Tile
rendering/gap marker, recovered-History marker, and Live details were not physically recaptured;
those paths have automated regression coverage only. The explicitly shortened combined
lifecycle observation closes the v0.3 release decision without claiming a release-source
three-hour pass or recapture of every visual surface.
An emulator screenshot does not prove touch comfort, ambient behavior, OEM Health Services
behavior, or endurance on the Pixel Watch 4. The separate unpowered battery measurement is
intentionally not claimed and is not a v0.3 release blocker; a powered probe cannot prove battery
drain, regardless of its duration.
