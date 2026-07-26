# Bad Watch v0.3 physical-device evidence

This directory is the retained physical-device ledger for the v0.3 release candidate. A row is
marked passed only when the referenced artifact and the observations recorded below support that
exact claim. Automated tests are useful corroboration, but are not substituted for device
evidence here. Evidence captured on an earlier candidate remains useful only for code paths that
did not change afterward; each section names that scope instead of pretending every image came
from one APK.

## Candidate and device identity

| Field | Recorded value |
| --- | --- |
| Evidence date | 2026-07-26 UTC |
| Final app/APK source revision | `0bdbe985756d962e73b47dfc5ca098a709a9a86f` |
| Final recovery/screenshot retention checkpoint | `909bef8` (documentation/endurance follow) |
| Browser journey gate checkpoint | `836f116` (test/workflow only; APK unchanged) |
| Session probe implementation | `e9719a57d6a44463ff554e596de55ee95de41ef6` |
| Recovery probe implementation | `5318deb910ea3aae0f77d41e8c5e227ae77f39f8` |
| Tested artifact | `app-debug.apk` |
| Final debug APK SHA-256 | `5d637e2d0c7fad57f15503c140d9cb11e25f4b94df1732256e88f2ca2a0046fb` |
| Package | `com.badwatch.badwatch` |
| Version | `0.3.0` (`versionCode=300`) |
| SDKs | minimum 30, target 36, compile 36 |
| Signature | APK Signature Scheme v2, one Android debug signer |
| Watch | Google Pixel Watch 4, `kenari_btwifi`, 480 x 480 |
| OS | Android 17 / API 37 |
| Build fingerprint | `google/kenari_btwifi/kenari_btwifi:17/CP2A.260603.001.S1/15396605:user/release-keys` |
| App last update reported by device | `2026-07-26 14:08:34` |
| Device connection | Wireless ADB TLS transport |
| Charger state | AC powered; no battery-drain claim |
| Font / ambient matrix | Scoped active/ambient and broad normal/`1.30` evidence plus final-APK Summary/History/recovered-Summary retests |

Ten traceable debug app checkpoints contribute to this ledger:

| Scope | Source | APK SHA-256 |
| --- | --- | --- |
| Initial permission, recreation, recovery, and sync evidence | `99b5f627917d27465dd6494ea0b329ecc670a596` | `dd3754cce11a3fb5627375509690e43a93aa06f4a131247fd33391cbf61dfee1` |
| Ongoing Activity, match, shadow, Tile rendering, and complication evidence | `c5a1a67aa8b1b85a665b74ddf735ee9138308e0c` | `d5daeaf26d3e9b93b4b88d307b845f0ce049fd7248eadc324eb26b54e96dc39a` |
| Tile launch fix, ambient callback, and first normal/enlarged visual pass | `008a4f46cb899c2dba1e96f80abe9ac9a23c60d7` | `6aa03ab9564394a671ab5a52af958efcf10447c9c75a54285050c7c4c6811eb9` |
| Enlarged-Progress reflow | `90fb65afffb082a6fb587406659f27db75b6281d` | `333392944eab961249c4fcbbb7652d5cf3271c3fd5aa588ca5dfc39b38182611` |
| Confirmed failed-session discard, before final lifecycle audit | `a94a8695f0711c9a3b0ca2e4b90592049eecfdb2` | `8fdee0dd61ff335247effe8653825846c9a21ba4cfb3b70abde2e3a4ec9c587e` |
| Service-owned terminal cleanup | `8c8520a2fdea19f09e9ca1b35bb25d5649086a5a` | `bc97ab8c79d02ff8240fb00e6782d0a6c86165490914c522d6f1b4064df7d85e` |
| Large-text dense-metric stacking | `402000cc870bff8b1922dff423bbb1b835cb3201` | `a22d24f46054c439f8abb6cfe1b92afc3a0e890e4849e80d3db28085f4ef0ec0` |
| Large-text live action lane | `ea08bf21b79f73b16285378f310784f5af01abd4` | `a429ec043bf1d8ec033ac1e3415eaf4958ad4dbab99b98f816b1f2635f15c4e2` |
| Safe-first recovery plus compact match/shadow and the pre-final endurance run | `6f6f6cd9531040072bc153b365c0c515b0a40781` | `28076fc03319ca15df92760a8d20b993af2f8ae8e129ffe61e021876eed0169d` |
| Long-duration review, retry-safe sync, immutable recovery coverage, and frozen final APK | `0bdbe985756d962e73b47dfc5ca098a709a9a86f` | `5d637e2d0c7fad57f15503c140d9cb11e25f4b94df1732256e88f2ca2a0046fb` |

The `008a4f4` app change is deliberately narrow: it makes `MainActivity` `singleTop`, consumes the
Tile's one-shot start command through `onNewIntent`, and adds stable ambient lifecycle diagnostics.
Its regression test proves that the command is consumed before session startup and cannot replay.
`90fb65a` only reflows the shared progress meter at large font scales. The `a94a869` change
adds the explicit confirmed-discard path for a failed recording and a throwing sensor-flow
regression. Checkpoint `8c8520a` closes the audited terminal cleanup races for failed
session/capture flows, including a storage-retry branch that preserves the pending capture ID.
Frozen checkpoint `402000c` stacks dense groups of three or more metrics at font scale `1.20` or
above and adds a JVM threshold regression.
Frozen checkpoint `ea08bf2` reserves a 48 dp live-action lane at the same threshold, uses compact
visible **Finish** / **Finir** text, and retains the complete Stop-and-save accessibility name.
`e6fea0a` then places the safe/recoverable failure action before destructive Discard. Checkpoint
`6f6f6cd` gives match/interval and shadow the same `1.20` responsive threshold and JVM regressions.
It is the source of the retained pre-final endurance run, not the final APK.

Checkpoint `0bdbe98` incorporates the subsequent hour-plus responsive review, bounded probe
cleanup and settled-recap verification, retry-safe incomplete sync acknowledgements, immutable
process-absence provenance, inference gating, exact Home/History/Tile recovery markers,
observed-minute Progress records, dashboard filters and gap audit, CSV recovery columns, and the
three-way Detected/No play/Unobserved activity composition. Earlier evidence remains representative
only for the explicitly scoped behavior named in its section. Final-APK device retests cover
start/recover/save, hour-plus Summary and History at normal/`1.30` text, and the recovered Summary
notice/composition. Final-build Home, Progress, Tile rendering/gap marker, recovered-History
marker, and Live details were not physically recaptured; those paths have automated regression
coverage only. The definitive endurance retest is `FINAL_ENDURANCE_PENDING`.

The installed `base.apk` matched the final debug APK hash byte-for-byte and verified with APK
Signature Scheme v2. The same source produced unsigned release APK SHA-256
`f46405c1a9ee9d420d61b8e997da27ca75090665801279549eb734c4777fa6d8` and unsigned release
AAB SHA-256 `70d3c1816612565187d27f299ff69ca7060e978f717fd5698bcb5ae812fe58dc`.
Those release artifacts were assembled and hashed, not installed on this debug-evidence watch.
At the pre-`836f116` frozen app checkpoint `0bdbe98`, the clean software gate passed all 31 Python
tests, `py_compile`, resource XML and browser JavaScript syntax validation, and Gradle
clean/test/lint/debug/release-APK/release-bundle. Its historical result was 132 tasks: 129 executed
and three up-to-date; no later aggregate task total is inferred from it.

At test-only repository checkpoint `836f116`, `:server:test` depends on
`:server:dashboardBrowserTest` and requires Node 22. It executes six journeys against JavaScript
extracted from shipped `index.html`: stale-token prompt/retry; URL-filter
hydrate/apply/error/reset; deep-linked reviewed detail; successful revisioned diary save and
aggregate refresh; HTTP 409 reload/conflict replacement; and archive-restore success/error. This
proves client state/request contracts, not responsive layout or real-browser rendering.

For a powered endurance run, current probe tooling snapshots, suppresses, reports, and restores
plugged-in stay-awake, Theater Mode, and DND so charging UI/notifications cannot create a false
awake interval. It also separately classifies the optional post-stop diary and a settled saved
Summary. Those controls do not change battery telemetry, and powered evidence remains ineligible
for a drain claim.

All retained PNGs in this directory are native 480 x 480 captures. The watch moved between
English and French during the recreation check and used both normal and `1.30` font scale during
the visual passes. Screen-off acquisition was observed with the system in `Dozing`; the retained
pre-final 180-minute run was powered, the frozen-final-APK run is `FINAL_ENDURANCE_PENDING`, and no
battery-drain conclusion is drawn from either lifecycle gate.

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

The final schema-2 physical recovery run passed on checkpoint `0bdbe98`. Session
`5fde6181-2421-4919-ac3b-6a16d0360b59` kept one ID and start time across a forced process stop. Its
checkpoint stayed frozen at 1,185 samples, advanced to 2,375 after restoration, incremented
`recoveryCount` to 1, and saved exactly one `Partial` session whose duration and elapsed span are
both 42,607 ms.

The host observed 3,626 ms between force-stop and restart request. The app conservatively retained
the larger immutable interval `1785068100751..1785068112485` (11,734 ms), from its last durable
journal boundary through controller restoration. It is valid and ordered, exactly matches the
recovered checkpoint and final session, covers journal-to-restart, overlaps the forced stop, and
passes `processAbsenceEvidenceValid`. Elapsed time includes it, but no sensor evidence is invented,
detected exchanges cannot bridge it, and it is neither detected activity nor inferred quiet time.

- [final machine-readable passing report](recovery-final/20260726T121444Z/report.json)
- [before final process death](recovery-final/20260726T121444Z/before-process-death.png)
- [after final recovery](recovery-final/20260726T121444Z/after-recovery.png)
- [immediate optional diary after stop](recovery-final/20260726T121444Z/recap.png)
- [settled Summary with exact known-unobserved notice](screenshots/65-final-recovery-gap-recap.png)
- [three-way activity/quiet/unobserved composition](screenshots/66-final-recovery-gap-composition.png)

The earlier [schema-1 recovery report](recovery/20260726T020131Z/report.json) remains historical
evidence for stable identity, a frozen checkpoint, and resumed sampling. It did not persist or
validate `processAbsenceGaps` and is not used to prove the final provenance contract.

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

### Ongoing session surface and one-tap return

Motion-only session `ab48f1cb-1c71-4416-a814-591e0b403f9e` stayed in the health foreground
service while heart-rate access was denied. Its durable motion checkpoint advanced from about
1,187 to 52,794 samples across the screen-off and ongoing-surface observations, then saved with a
duration of 9:06. The system notification carried the ongoing-workout extension, health
foreground-service type `0x00000100`, public/local-only visibility, and ongoing/no-clear flags.

Wear OS showed Bad Watch's ongoing indicator on the watch face with the accessibility label
“Badminton session in progress.” Tapping it returned directly to the same live session rather
than starting another recording. The player then used the visible stop, review, and complete
recap flow.

- [machine-readable passing report](ongoing/report.json)
- [final motion-only live HUD](screenshots/16-final-live-en-hr-denied.png)
- [system ongoing indicator](screenshots/19-final-ongoing-watchface.png)
- [one-tap return to the active session](screenshots/20-final-ongoing-one-tap-return.png)
- [session review](screenshots/21-final-session-review.png)
- [completed recap](screenshots/22-final-session-recap-complete.png)

### Manual match recovery

The active singles match retained ID `a5afcc6b-feea-404b-ba61-4cb3dfa87706`, score `2–1`, and all
three actions after `am force-stop`. The active checkpoint was byte-identical before and after
restart, and the app restored the match automatically. Abandon was then confirmed through the
visible UI and the active file disappeared.

- [machine-readable passing report](match/report.json)
- [match setup](screenshots/24-final-match-setup.png)
- [score before process death](screenshots/25-final-match-before-restart.png)
- [same settled score after restart](screenshots/27-final-match-after-restart-settled.png)

### Shadow-practice recovery

An 18-repetition shadow routine retained its random seed, cue index `2`, and one confirmed
repetition across process death. It reopened paused, shifted the current cue timestamp so
unobserved downtime would not count, and displayed “Restored safely. Unobserved restart time will
not count.” Finishing early through the UI removed the active checkpoint.

- [machine-readable passing report](shadow/report.json)
- [practice library](screenshots/28-final-practice-list.png)
- [active cue before process death](screenshots/29-final-shadow-before-restart.png)
- [safely paused cue after restart](screenshots/30-final-shadow-after-restart.png)
- [completed routine summary](screenshots/31-final-shadow-complete.png)

### Tile rendering and start contract

The system-rendered Tile showed literal last-session and seven-day values and a round-safe Start
action. An initial tap exposed a real lifecycle defect: when the existing Activity task was
already present, Wear brought it forward without delivering the start extra. Source revision
`008a4f4` fixed that contract with `singleTop`/`onNewIntent` delivery and one-shot consumption.
The first retained post-fix tap created one live recording with the health foreground service,
but later command review showed that its setup used invalid component shorthand
(`/.MainActivity`). Screenshot 34 therefore remains scoped to cold-start evidence.

The exact path was repeated on frozen APK `6f6f6cd` with fully qualified component
`com.badwatch.badwatch/com.badwatch.app.MainActivity`. Before the Tile covered it, task `309`,
ActivityRecord `122959028`, and process `31944` already existed. After tapping Start, all three
identities were unchanged, the task still held one Activity, exactly one active checkpoint was
created for session `fd38eee0-825e-49a3-a7bb-25b6d72d12fd`, samples advanced from 0 to 3,578,
and the health FGS ran with type `0x00000100`. Visible Finish, system Back to skip review, and
visible Done produced exactly one 49.2-second saved session and left no checkpoint or service.

- [system-rendered Tile](screenshots/32-final-tile-en.png)
- [live session opened from the Tile cold-start path](screenshots/34-final-tile-start-live.png)
- [machine-readable existing-task passing report](tile/report.json)
- [same-Activity live session from existing-task Start](screenshots/60-final-tile-existing-task-live.png)

### Watch-face complication

The Pixel Watch's system watch-face editor was temporarily switched from its original `linear`
layout to `default_arcs` so an enabled slot could host Bad Watch. The system bound
`WeeklyHitsComplicationDataSourceService` as a short-text data source and rendered `7D HITS 0` in
the selected slot. Tapping the complication opened Bad Watch. The slot's original Google
Assistant provider and the original `linear` watch-face layout were restored after the check.

- [machine-readable passing report](complication/report.json)
- [live seven-day complication in the system watch face](screenshots/33-final-complication-live.png)

This verifies provider binding, live data, and tap action. It does not claim ownership of a
player's watch-face configuration after validation.

### Ambient HUD

On the `008a4f4` APK, the active session entered the real Wear ambient lifecycle. Logcat recorded the
stable `BadWatchAmbient: entered` callback, the Activity remained the active Bad Watch surface,
and the simplified black HUD showed a frozen clock, detected-hit count, and unavailable HR/timer
placeholders without interactive controls or burn-in-sensitive animation. Waking the watch
produced `BadWatchAmbient: exited`. Temporary always-on and battery-service test settings were
restored afterward.

- [machine-readable passing report](ambient/report.json)
- [final ambient session HUD](screenshots/35-final-ambient-hud.png)

This is callback and rendering evidence, separate from the longer screen-off sensor-lifecycle
probe and from any battery measurement.

### Final visual inventory and enlarged text

“Final visual inventory” is the retained release-ledger grouping, not a claim that every image was
captured from final APK checkpoint `0bdbe98`. The broad navigation and language matrix below is
source-scoped to the earlier checkpoints named in this ledger.

The broad normal-size English pass on the scoped pre-final checkpoints retained the Tile, Home,
History, Progress, an empty Detection Lab, Settings, match interval, and the existing-task
Tile-to-live transition. The zero-swing lab keeps destructive/disabled actions visually distinct
and does not allow an empty drill to be saved.

- [Home at normal font scale](screenshots/36-final-home-current.png)
- [History at normal font scale](screenshots/37-final-history-normal.png)
- [Progress at normal font scale](screenshots/38-final-progress-normal.png)
- [zero-swing Detection Lab](screenshots/39-final-detection-lab-zero.png)
- [Settings at normal font scale](screenshots/40-final-settings-normal.png)
- [match interval at normal font scale](screenshots/56-final-match-interval-normal.png)

A scoped `1.30` pass covers navigation, permissions, recording/review/recap, safe failure recovery,
match/interval, practice, and shadow. The first sweep exposed tight Progress metrics, a clipped
live action, destructive-first failure order, and overfull match/shadow states. Those findings
drove revisions `402000c`, `ea08bf2`, `e6fea0a`, and `6f6f6cd`; the images below are the corrected
captures. Home/History/Settings scroll, dense metrics stack, the live action has a dedicated lane,
safe Dismiss precedes Discard, and match/shadow use compact large-text layouts.

- [machine-readable visual/accessibility report](accessibility/report.json)
- [Home at 1.30 font scale](screenshots/41-final-home-font-130.png)
- [History at 1.30 font scale](screenshots/42-final-history-font-130.png)
- [Progress at 1.30 font scale](screenshots/43-final-progress-font-130.png)
- [Settings top at 1.30 font scale](screenshots/44-final-settings-font-130.png)
- [Settings bottom and Back action at 1.30 font scale](screenshots/45-final-settings-bottom-font-130.png)
- [heart-rate permission at 1.30 font scale](screenshots/46-final-heart-rate-permission-font-130.png)
- [live recorder at 1.30 font scale](screenshots/47-final-live-font-130.png)
- [session review at 1.30 font scale](screenshots/48-final-review-font-130.png)
- [session recap at 1.30 font scale](screenshots/49-final-recap-font-130.png)
- [safe-first failed-session recovery](screenshots/50-final-session-failure-font-130.png)
- [match setup at 1.30 font scale](screenshots/51-final-match-setup-font-130.png)
- [live match at 1.30 font scale](screenshots/52-final-match-live-font-130.png)
- [match interval at 1.30 font scale](screenshots/53-final-match-interval-font-130.png)
- [practice library at 1.30 font scale](screenshots/54-final-practice-font-130.png)
- [English shadow cue at 1.30 font scale](screenshots/55-final-shadow-font-130.png)
- [French longest shadow cue at 1.30 font scale](screenshots/57-final-shadow-fr-font-130.png)
- [French paused shadow at 1.30 font scale](screenshots/58-final-shadow-paused-fr-font-130.png)
- [French finish dialog at 1.30 font scale](screenshots/59-final-shadow-finish-fr-font-130.png)

The accessibility report validates its 26 referenced PNGs as native 480 x 480 captures, records no
critical truncation or overlap, and confirms cleanup: font scale `1.0`, system English locale,
granted HR, no active checkpoint, and no foreground service. It explicitly does not claim a
fluent French review or a physical TalkBack traversal.

### Final frozen-APK long session and recovery coverage

Checkpoint `0bdbe98` was then installed and byte-matched for a focused final subset: saved Summary,
hour-plus History, and recovered Summary/composition. At normal font scale and `1.30`, the saved
Summary renders the exact three-hour value `3:00:12` without clipping: normal text gives the
hour-plus duration its own full-width row, while enlarged text uses the established accessibility
stack. History retains the same complete value and remains readable/reachable at both scales.

- [three-hour Summary at normal font scale](screenshots/61-final-long-duration-recap.png)
- [three-hour Summary at 1.30](screenshots/62-final-long-duration-recap-font-130.png)
- [three-hour History row at normal font scale](screenshots/63-final-long-duration-history.png)
- [three-hour History row at 1.30](screenshots/64-final-long-duration-history-font-130.png)

The final recovered-session Summary shows `0:11` of known unobserved time, explains that elapsed
time includes it, and explicitly says it is excluded from inferred quiet time and personal
comparisons. The composition card uses three distinct states—Detected, No play, and Unobserved—so
its active share uses reviewed wall time without relabelling the gap as activity or rest.

- [final known-unobserved Summary notice](screenshots/65-final-recovery-gap-recap.png)
- [final three-way session composition](screenshots/66-final-recovery-gap-composition.png)

All six frozen-APK captures are native 480 x 480 images. They complement rather than relabel the
earlier full navigation/language matrix. Final-build Home, Progress, Tile rendering/gap marker,
recovered-History marker, and Live details were not physically recaptured; those paths have
automated regression coverage only.

## Final release-gate status

| Gate | Status at this ledger revision |
| --- | --- |
| Physical match and shadow process-restoration checks | **Passed:** retained reports prove state identity, safe timing, and visible cleanup. |
| Ongoing Activity, Tile rendering/cold start, complication, and ambient surfaces | **Passed on explicitly scoped earlier candidates:** system-rendered/tapped surfaces and a real ambient lifecycle are retained above; they were not repeated on final `0bdbe98`. |
| Existing-task Tile Start | **Passed:** the scoped `6f6f6cd` report proves same-task/same-Activity/same-process delivery, one session, advancing samples, health FGS, and complete visible cleanup; final-source regressions retain the launch contract. |
| Session process-restoration provenance | **Passed:** the frozen-APK schema-2 report proves stable identity, frozen/resumed samples, exact checkpoint/session intervals, `Partial` quality, and visible gap semantics. |
| Normal-size screen inventory and English/French spot checks | **Passed as combined scoped evidence:** the broad navigation/language matrix is from earlier candidates; final `0bdbe98` adds only saved Summary, hour-plus History, and recovered Summary/composition. Final Home, Progress, Tile, recovered History, and Live were not recaptured. |
| Enlarged-text inspection | **Passed as combined scoped evidence:** the earlier 26-image matrix covers the broad responsive inventory; final `0bdbe98` physically retests hour-plus Summary/History at `1.30`. Its recovered Summary/composition captures are separate final evidence, not part of the enlarged matrix. Other final-build visual paths have automated coverage only. |
| 180-minute powered screen-off lifecycle probe | **Pending active final gate:** `FINAL_ENDURANCE_PENDING`; the older passing run below is pre-final. |
| 180-minute unpowered battery measurement | **Not claimed** |

<!-- ENDURANCE_RESULT_START -->
The historical powered 180-minute run passed on 2026-07-26 UTC against pre-final app checkpoint
`6f6f6cd` using probe tooling checkpoint `3f28ec3`. Its retained
[report](endurance/20260726T071128Z/report.json),
[start image](endurance/20260726T071128Z/start.png), and
[Summary image](endurance/20260726T071128Z/recap.png) remain useful regression evidence. The probe
requested 10,800,000 ms, observed 10,814,298 ms wall time, and saved exactly one 10,812,561 ms session
(`6210124d-db32-4ccf-bfe3-28752bf21b58`) to
`1785049889518-6210124d-db32-4ccf-bfe3-28752bf21b58.json`, a 1,737 ms saved-vs-wall error.

All 178 retained readings reported `Dozing`, a running foreground service, health foreground type
`0x100`, the same journal ID, and a strictly advancing processed-sample checkpoint from 0 to
1,075,257. The report's four lifecycle booleans all pass. Temperatures remained between 30.3 C
and 33.3 C. System-wake suppression records plugged-in stay-awake, Theater Mode, and zen/DND as
original `7/0/0`, active `0/1/2`, and restored `7/0/0`; both images are native 480 x 480.

Every reading was powered. Accordingly, `batteryMeasurementValid` is false,
`batteryDeltaPercent` is null, and this passing lifecycle result does not substantiate battery
drain. Because production and probe behavior changed afterward, this result does not close the
frozen-final-APK release gate.

Definitive frozen-final-APK endurance result: `FINAL_ENDURANCE_PENDING`.

The final retained report must supply its exact path/source/tooling identity, duration figures,
one-session identity, all-Dozing/health-FGS/sample-progress booleans, wake-control
original/active/restored values, and `postStopReviewCaptured` / `savedSessionRecapVerified` before
the pending row is marked Passed. The unpowered battery-measurement row remains deliberately
unclaimed and is not a v0.3 release blocker.
<!-- ENDURANCE_RESULT_END -->

Later artifacts are added rather than overwriting this record. The definitive matching result must
also be linked from the release matrix in [`docs/device-validation.md`](../../device-validation.md).
