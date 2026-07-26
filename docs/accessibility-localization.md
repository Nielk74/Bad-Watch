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

The final debug candidate at source revision
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
that an Activity recreation does not replace the active recording. Enlarged-text, Tile,
complication, ambient, and complete screen-reader walkthroughs remain separate pending rows in
[device-validation.md](device-validation.md).
