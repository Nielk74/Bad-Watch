# Wear accessibility and localization

Bad Watch ships complete English and French (`values-fr`) copy for the watch app, its Tile,
ongoing notifications, and the seven-day complication. Domain values such as stroke labels,
session context, match sides, court corners, recording quality, and body areas are mapped to
Android resources at the presentation boundary; persisted enum names and raw evidence remain
locale-neutral.

Generated session insights keep their English audit text in the core model and additionally
carry runtime-only structured measurements. The Wear UI renders those measurements through
localized templates. This keeps translated conclusions tied to exactly the same evidence and
avoids parsing or translating opaque prose.

## Interaction and screen-reader rules

- Glanceable stat rows, detail rows, and meters merge their label and value into one TalkBack
  node.
- Canvas-only charts provide a concise trend/distribution description when the graphic adds
  information; decorative drawing remains silent.
- Custom score, pause, undo, correction, goal, physiology, and text-input controls expose a
  named action or state and retain a minimum 48 dp touch target.
- Decorative icons do not repeat adjacent button labels.
- Personalized heart-rate zones are hidden until the player configures a maximum-HR source.
  Heart-rate reserve requires both configured maximum and resting endpoints. Measured BPM is
  still shown when neither is configured.
- Destructive session deletion always asks for confirmation and identifies the session date.
- A failed live recording separates the non-destructive Dismiss action from a clearly described,
  confirmed Discard action; both the consequence and confirmation are localized in English and
  French.
- Detection Lab distinguishes **Cancel capture** from **Retry save** so a storage failure is not
  phrased like a sensor restart; both recovery actions are localized and describe what survives.

## Platform target and foreground recording

The app compiles and targets API 36. Android requires a `health` foreground service targeting
API 34 or newer to hold `FOREGROUND_SERVICE_HEALTH` and at least one qualifying sensor/activity
permission. Bad Watch declares the manifest-only `HIGH_SAMPLING_RATE_SENSORS` permission because
its core function continuously samples wrist IMU data. Heart-rate access remains a separate,
optional runtime permission, so denying it must not prevent motion-only recording.

References:

- [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [Health foreground-service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#health)
- [Android 16 behavior changes](https://developer.android.com/about/versions/16/behavior-changes-16)

## Validation

Run the resource, unit, lint, and package gates from the repository root:

```sh
xmllint --noout app/src/main/res/values/*.xml app/src/main/res/values-fr/*.xml
./gradlew :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --no-daemon
```

Before release, verify on a physical Wear device at API 36 or newer that a normal session starts and continues after
`READ_HEART_RATE` is denied, the foreground notification remains present, motion samples advance,
and the service exits cleanly when the session is discarded or saved.

### Target-SDK-36 heart-rate permission evidence (2026-07-26)

The initial target-SDK-36 permission candidate at source revision
`99b5f627917d27465dd6494ea0b329ecc670a596` was exercised on a Pixel Watch 4
(`kenari_btwifi`, 480 x 480, Android 17 / API 37). Its SHA-256 was
`dd3754cce11a3fb5627375509690e43a93aa06f4a131247fd33391cbf61dfee1`; package inspection
reported app version 0.3.0 and `targetSdkVersion=36`. This proves permission behavior on a
platform newer than the Android 16 change that introduced granular heart-rate access; it is not
mislabeled as an API 36 device run.

The granted path completed an English recording, save, optional review, and recap as session
`661b9eac-26e3-4186-979f-e8498bd873d3`. Its checkpoint reached 1,185 motion samples. Because the
watch was off wrist, the live face truthfully displayed `-- bpm`; this is evidence that a granted
session works, not evidence of an acquired optical reading.

The denied path used session `9c9404e8-523a-4472-8ed9-965ce53166dc`. Package state reported
`HIGH_SAMPLING_RATE_SENSORS: granted=true` and `READ_HEART_RATE: granted=false`. The service
remained foreground with type `0x00000100` (`health`), the UI showed `-- bpm`, and the durable
checkpoint held 1,187 motion samples with no heart-rate points. Changing the app locale from
English to French recreated the Activity while retaining the same session ID, start time, and
recording service process. The checkpoint then advanced to 4,787 samples while the heart-rate
trace remained empty. The session saved and completed its French review flow, and heart-rate
permission was restored after the check.

The native 480 x 480 permission, live, review, and recap captures—and the exact device build
fingerprint—are retained in the
[v0.3 Pixel Watch 4 evidence ledger](evidence/v0.3-pixel-watch-4/README.md#heart-rate-denial-and-activity-recreation).
This directly verifies that optional-heart-rate denial does not prevent motion acquisition and
that an Activity recreation does not replace the active recording.

### Platform-surface and text evidence (2026-07-26)

The frozen app checkpoint is `6f6f6cd9531040072bc153b365c0c515b0a40781`; its installed debug
APK SHA-256 is `28076fc03319ca15df92760a8d20b993af2f8ae8e129ffe61e021876eed0169d`.
The earlier `008a4f4` pass adds platform-level evidence beyond ordinary Activity screenshots,
while the final matrix runs on frozen `6f6f6cd`:

- the ongoing-session indicator announced “Badminton session in progress,” and one tap returned
  to the same active session;
- the system-rendered Tile exposed readable last-session/seven-day text and a labelled Start
  action; on the frozen APK, the exact existing-task path retained one task, ActivityRecord, and
  process, delivered one `onNewIntent` command, and created one recording;
- the system watch face rendered the complication's short `7D HITS` label and value, and its tap
  action opened Bad Watch;
- the real ambient lifecycle replaced the active controls with a high-contrast static HUD and
  restored the interactive UI on exit;
- normal-size Home, History, Progress, Detection Lab, Settings, match interval, Tile, and live
  screens were visually inspected on the 480 x 480 round display; the zero-swing lab kept
  disabled actions disabled and visually distinct.

The English destination pass complements the retained French live, review, and recap sequence.
This verifies resource-backed localization across both recording states and ordinary navigation;
it is not a claim that a fluent reviewer has linguistically signed off every sentence.

At Android font scale `1.30`, the completed matrix covers Home, History, Progress, Settings top and
bottom, heart-rate permission, live recording, review, recap, safe-first session failure, match
setup/live/interval, practice, English shadow, and French longest-cue/paused/finish-dialog states.
The initial Progress, live-action, failure-action-order, match, and shadow captures exposed real
quality defects; each was fixed and recaptured before the final pass. The final report records no
critical text truncation or action overlap, and all required actions remain reachable.

The complete evidence set is indexed in the
[physical ledger](evidence/v0.3-pixel-watch-4/README.md#final-visual-inventory-and-enlarged-text)
and [device release matrix](device-validation.md#physical-watch-release-matrix).

The Compose semantics and 48 dp rules remain covered by source/unit/lint inspection. A full
physical TalkBack traversal has not been claimed; it is a separate assistive-technology study,
not a hidden implication of screenshot evidence.

Source revision `402000c` adds the release rule behind the recapture: any group of three or more
metrics stacks vertically at font scale `1.20` or greater. A JVM regression covers the threshold;
the final hardware matrix proves the actual round-screen result at `1.30`.

Source revision `ea08bf2` applies the same threshold to the active HUD: it reserves a 48 dp action
lane and uses compact visible **Finish** / **Finir** text while keeping the complete localized
Stop-and-save semantic name. Both English and French 1.30 UI checks passed in the implementation
gate, and the corrected screenshot 47 passes on hardware.

Source revisions `e6fea0a` and `6f6f6cd` complete the large-text behavior. Recovery screens put
the safe/recoverable action before a destructive confirmed action, and match/interval plus shadow
switch to compact layouts at font scale `1.20`. JVM threshold regressions and the physical 1.30
states pass.

The [machine-readable report](evidence/v0.3-pixel-watch-4/accessibility/report.json) names the
frozen source/APK, all 26 native 480 x 480 captures, every checked state, and cleanup. Font scale
was restored to `1.0`, app locale to system English, heart-rate permission to granted, and no
active checkpoint or foreground service remained. A fluent French review and physical TalkBack
traversal are explicitly not claimed.
