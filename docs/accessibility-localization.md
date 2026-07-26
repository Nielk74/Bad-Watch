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

### Target-SDK-36 heart-rate-denial evidence (2026-07-26)

The target-36 debug APK was exercised on a Pixel Watch 4 (`kenari_btwifi`, 480 x 480,
Android 17 / API 37). This proves the denied-permission behavior on a platform newer than the
Android 16 change that introduced granular heart-rate access; it is not mislabeled as an API 36
device run.
After installation, the following commands revoked only optional optical-heart-rate access and
started the same Activity-extra path used by the Tile:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm revoke com.badwatch.badwatch android.permission.health.READ_HEART_RATE
adb logcat -c
adb shell am force-stop com.badwatch.badwatch
adb shell am start -n com.badwatch.badwatch/com.badwatch.app.MainActivity \
  --ez autostart_session true
```

After choosing **Don't allow** in the system permission sheet, evidence was collected with:

```sh
adb shell dumpsys activity services com.badwatch.badwatch
adb shell dumpsys package com.badwatch.badwatch
adb shell uiautomator dump /sdcard/bw.xml
adb shell cat /sdcard/bw.xml
adb shell pidof com.badwatch.badwatch
adb shell run-as com.badwatch.badwatch cat files/active-session/journal.json
adb logcat -d -v brief -s AndroidRuntime:E ActivityManager:E
```

Observed result: the package reported `targetSdkVersion=36`,
`HIGH_SAMPLING_RATE_SENSORS: granted=true`, and `READ_HEART_RATE: granted=false`. The service
remained `isForeground=true` with foreground type `0x00000100` (`health`); the live UI truthfully
showed `-- bpm`, its elapsed time advanced from `1:35` to `1:55`, and the application process
remained alive with no AndroidRuntime failure. After the 12-second checkpoint interval, the
private recovery journal reported `samplesProcessed=1175`, an empty heart-rate trace, null
per-sample heart rate, and a current accelerometer/gyroscope sample. This directly verifies that
the denied-HR path continued collecting motion rather than advancing only the UI clock. The test
session was then discarded through its visible confirmation UI, the journal was cleared, the
service disappeared from `dumpsys`, and the prior heart-rate grant and permission flags were
restored:

```sh
adb shell pm grant com.badwatch.badwatch android.permission.health.READ_HEART_RATE
```
