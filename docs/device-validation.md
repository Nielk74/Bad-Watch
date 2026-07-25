# Wear device validation

Bad Watch treats reliability and battery life as measured release properties. The checked-in
probe runs the same start and stop surfaces as a player, leaves the screen free to sleep, and
produces evidence that can be compared across releases.

## Session and battery probe

Prerequisites:

- a debug build installed on an unlocked Wear OS watch;
- `adb` connected over USB or Wi-Fi;
- no Bad Watch session already in progress;
- the watch off its charger for a meaningful battery result.

Run a five-minute smoke test:

```bash
./tooling/wear_session_probe.py --serial <adb-serial> --duration-minutes 5
```

Run the three-hour release gate:

```bash
./tooling/wear_session_probe.py \
  --serial <adb-serial> \
  --duration-minutes 180 \
  --sample-seconds 60 \
  --output build/wear-session-probe
```

The probe:

1. refuses to run over an existing session;
2. records device, OS, build and app metadata;
3. snapshots initial battery/temperature and starts via the Tile-compatible Activity extra;
4. samples battery while checking that the foreground service remains alive;
5. wakes the watch only at the end, presses the visible **Stop & save** action, and waits for
   the service to finish;
6. requires exactly one new, valid session JSON;
7. requires persisted duration to be within five seconds of the actual start-to-stop wall
   interval (the requested monitoring window excludes setup and stop-UI overhead);
8. writes `start.png`, `recap.png`, and `report.json` under a timestamped directory.

It never clears application data and never deletes the validation session. Delete that session
from History after retaining the report if it should not remain in personal history.

Battery percentage is coarse, so a short smoke test only validates lifecycle behavior. The
three-hour run is the basis for a release battery statement. Record AOD state, connection mode,
screen interactions, charger state and unusual thermal conditions alongside the report; those
conditions materially affect comparisons.

## Current hardware evidence

The repository's active development device is a 480×480 Pixel Watch 4 on API 36. For each
release candidate, retain evidence for:

- Home, live HUD, recap, History, Settings, Match, Tile, complication and ambient mode;
- the three-hour session probe;
- an airplane-mode save followed by successful retry/sync;
- Activity recreation during recording;
- a process-recovery exercise once active-session checkpointing is implemented.

Passing compilation or an emulator screenshot is not a substitute for these checks.
