# Bad Watch v0.3 physical-device evidence

This directory is the retained physical-device ledger for the v0.3 release candidate. A row is
marked passed only when the referenced artifact and the observations recorded below support that
exact claim. Automated tests are useful corroboration, but are not substituted for device
evidence here.

## Candidate and device identity

| Field | Recorded value |
| --- | --- |
| Evidence date | 2026-07-26 UTC |
| Source revision | `99b5f627917d27465dd6494ea0b329ecc670a596` |
| Tested artifact | `app-debug.apk` |
| APK SHA-256 | `dd3754cce11a3fb5627375509690e43a93aa06f4a131247fd33391cbf61dfee1` |
| Package | `com.badwatch.badwatch` |
| Version | `0.3.0` (`versionCode=300`) |
| SDKs | minimum 30, target 36, compile 36 |
| Signature | APK Signature Scheme v2, one Android debug signer |
| Watch | Google Pixel Watch 4, `kenari_btwifi`, 480 x 480 |
| OS | Android 17 / API 37 |
| Build fingerprint | `google/kenari_btwifi/kenari_btwifi:17/CP2A.260603.001.S1/15396605:user/release-keys` |
| App last update reported by device | `2026-07-26 03:58:11` |
| Device connection | Wireless ADB TLS transport |
| Charger state | AC powered; no battery-drain claim |
| Font / ambient matrix | Not yet recorded; explicitly pending below |

The separately assembled unsigned release APK had SHA-256
`032029b2265466d051f9d2cc032f27c6a73fece446173f9e169dbfed6445a50d`. It was verified by the
software gate, not installed as part of this debug-only physical run.

All retained PNGs in this directory are native 480 x 480 captures. The watch moved between
English and French during the recreation check. Screen-off acquisition was observed with the
system in `Dozing`; no battery-drain conclusion is drawn from this evidence.

## Passed observations

### Install, onboarding, and optional permissions

The tested APK opened at first-run onboarding, accepted racket-hand setup, requested optional
heart-rate access, requested notification access, and reached the English home screen:

- [English onboarding](screenshots/00-onboarding-en.png)
- [English home](screenshots/01-home-en-normal.png)
- [heart-rate permission](screenshots/02-heart-rate-permission.png)
- [notification permission](screenshots/03-notification-permission.png)

Package inspection on the installed build reported `targetSdkVersion=36`, the health foreground
service permission, `HIGH_SAMPLING_RATE_SENSORS`, and optional `READ_HEART_RATE`.

### Session recording with heart-rate access granted

Session `661b9eac-26e3-4186-979f-e8498bd873d3` retained start time `1785031124563`, reached an
active checkpoint of 1,185 motion samples, stopped through the visible save flow, and produced one
session file named
`1785031124563-661b9eac-26e3-4186-979f-e8498bd873d3.json`. It was reviewed as Free play, RPE 4,
no soreness, complete, and full recording quality.

- [live session with permission granted](screenshots/04-live-en-hr-granted.png)
- [English review](screenshots/05-review-en.png)
- [reviewed recap](screenshots/06-recap-en-reviewed.png)

The watch was off wrist, so the live face correctly showed `-- bpm`. This run proves that the
authorized path records and saves motion; it does not claim that an optical BPM value was acquired.

### Heart-rate denial and Activity recreation

For session `9c9404e8-523a-4472-8ed9-965ce53166dc`, `READ_HEART_RATE` was denied while the required
high-rate sensor permission remained granted. The health foreground service stayed foreground
with type `0x00000100`; the live face showed `-- bpm`. Before recreation, the durable checkpoint
held 1,187 motion samples and zero heart-rate points.

Changing the app locale from English to French recreated the Activity without replacing the
recording service process. The session ID and start time `1785031208945` remained unchanged, the
checkpoint advanced to 4,787 motion samples, and the heart-rate trace remained empty. The session
then stopped, saved, and completed the French review flow. Heart-rate permission was restored
after the check.

- [denial choice](screenshots/07-heart-rate-denied-choice.png)
- [motion-only English live face](screenshots/08-live-en-hr-denied.png)
- [same recording after French Activity recreation](screenshots/09-live-fr-after-activity-recreation.png)
- [French review](screenshots/10-review-fr.png)
- [French reviewed recap](screenshots/11-recap-fr-reviewed.png)

### Process death and journal recovery

The automated physical recovery run passed. Session
`9ecee2f3-a6fa-4c13-abb6-28d6b9e9e568` kept one ID and start time across a forced process stop.
Its checkpoint stayed frozen at 1,189 samples while the process was absent, advanced to 2,369
after restoration, incremented `recoveryCount` to 1, and saved exactly one `Partial` session.

The forced-stop gap was 3,644 ms. The saved duration and elapsed span were both 44,087 ms and
intentionally include that interruption; the frozen sample count proves the app did not invent
sensor coverage during it.

- [machine-readable passing report](recovery/20260726T020131Z/report.json)
- [before process death](recovery/20260726T020131Z/before-process-death.png)
- [after recovery](recovery/20260726T020131Z/after-recovery.png)
- [saved recap](recovery/20260726T020131Z/recap.png)

### Detection Lab through screen-off

A Smash Detection Lab run remained alive through display sleep. Before sleep, process 16137 held
the health foreground service (`0x00000100`) and Android's sensor service reported active linear
acceleration and gyroscope clients with sensor access enabled. After 30 seconds with
`mWakefulness=Dozing`, the same process and foreground service remained present. Active duration
for both sensors advanced from 5 to 46 seconds while suspended duration remained 0.

- [Detection Lab before screen-off](screenshots/12-detection-lab-before-screen-off.png)
- [same active capture after screen-off](screenshots/13-detection-lab-after-screen-off.png)

The stationary watch produced no genuine labelled swing, so the run was cancelled visibly and no
capture file was created. This is evidence for screen-off acquisition continuity, not for a saved
labelled example or classifier accuracy.

### Offline save and authenticated retry

The watch was first configured for the debug-only loopback dashboard while no server or ADB
reverse bridge existed. WorkManager received a real `ConnectException`, returned `RETRY`, and
left all three existing session payloads without sync markers. Session
`a4b5c9dc-45d7-4fbe-a455-3aac92a2641c` was then recorded and saved while that destination was
still unavailable.

An authenticated self-hosted server was subsequently started behind
`adb reverse tcp:8080 tcp:8080`, and the same worker was re-enqueued. It returned success and the
watch atomically created `.synced` markers for all four exact payloads. An unauthenticated server
list request returned HTTP 401; an authenticated request returned HTTP 200 and all four IDs,
including the offline session. The ephemeral token value is intentionally not retained.

- [machine-readable sync report](sync/report.json)
- [reviewed offline-session recap](screenshots/14-sync-recap-after-review.png)
- [History showing durable Synced state](screenshots/15-history-synced.png)

This proves retry, bearer authentication, exact server acceptance, and watch-visible durable
status over the debug bridge. It is not presented as release cleartext approval: the release
network-security policy rejects cleartext endpoints and that boundary is covered by automated
tests.

## Explicitly pending or not claimed

| Gate | Status at this ledger revision |
| --- | --- |
| Physical match and shadow process-restoration checks | **Pending** |
| Full screen inventory, including History, Progress, Training, Match, Settings, ambient mode, Tile, and complication | **Pending** |
| Enlarged-text inspection and complete English/French spot-check matrix | **Pending**; normal-size English and a French session/review subset are retained above |
| 180-minute powered screen-off lifecycle probe | **Pending** |
| 180-minute unpowered battery measurement | **Not claimed** |

Later artifacts must be added rather than overwriting this record, and the release matrix in
[`docs/device-validation.md`](../../device-validation.md) must link them before those rows pass.
