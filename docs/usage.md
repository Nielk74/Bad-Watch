# Bad Watch Usage Guide

## Before you start

- **Wear the watch on your racket hand.** This is a hard requirement, not a preference.
  Bad Watch detects shots from the swing, so on the other wrist there is nothing to detect.
  The app asks you to confirm this the first time it opens.
- Set left- or right-handed in onboarding. The backhand signature mirrors between hands.
- Wear the watch snugly. A loose strap ruins both heart rate and swing detection.
- Grant heart-rate and notifications when prompted. Both are optional — recording works
  without either — but you lose heart rate and the ongoing session notification.

## Recording a session

1. Open **Bad Watch** and tap **Start session**.
2. Play. You can put your wrist down; recording continues with the screen off, via a
   foreground service. A notification shows the running detected-hit count.
3. Glance at your wrist any time for the live screen:
   - Detected-hit count (the large number) and elapsed time
   - Current heart rate, detected exchange count, and estimated active:quiet ratio
   - Last detected hit with its provisional stroke label
   - Detected-play summary: average hits, longest burst, and estimated active span
4. Tap **Stop & save** to end and store the session, or **Discard** to throw it away.

You can also stop from the notification without opening the app.

## Reading the numbers

**Detected-play structure is the headline.** A single racket-wrist watch sees the wearer's
candidate hits, not the opponent, partner, shuttle or point outcome. It groups two or more
nearby detections into a *rally burst*. This is useful for personal trends, but is not a
complete rally or exact effective playing time.

- **Estimated active:quiet** — first-to-last detected-hit spans against the gaps between
  them, shown as `1:N`. Missed boundary hits and one-hit points change this estimate.
- **Estimated active %** — the share represented by those detected spans, not court truth.
- **Average / longest burst** — the wearer's detected hits, not all rally contacts.
- **Heart rate** — average and peak over distinct optical readings, with signal coverage.

**All hit and stroke detection is provisional.** The classifier is currently rule-based and
has not been validated against representative match play. Treat both counts and labels as
detector output that may include false positives and missed hits; the specific stroke names
are the least trustworthy layer.

## The dashboard

Sessions stay on the watch and are pushed to your dashboard server whenever the watch has
network. Nothing is lost if the server is unreachable or you never set one up.

The dashboard adds what does not fit on a watch: detected-hit volume over time, inferred
exchange length, provisional stroke mix, an estimated-active-time trend, and heart-rate-
reserve load only for sessions that actually measured optical heart rate. It does not
estimate tissue injury risk or declare a workload "safe".

See [`dashboard.md`](dashboard.md) for running and configuring the server.

### Getting the raw data out

Every session is a JSON file on the watch:

```bash
adb shell run-as com.badwatch.badwatch ls files/sessions
adb exec-out run-as com.badwatch.badwatch cat files/sessions/<file>.json > session.json
```

The file format is exactly the sync payload — the same `SessionExport` type the server
receives — so anything that reads one reads the other.

## Troubleshooting

**No hits detected.** Confirm the watch is on your racket hand and the strap is snug. Give
the classifier a few full swings; gentle practice motions may fall below threshold. Very
short or very slow strokes are the most likely to be missed.

**Recording stopped when the screen turned off.** It should not — that is what the
foreground service is for. Check that the session notification is present. If the watch is
in battery saver, Wear OS may still restrict background work.

**No heart rate.** Re-grant the heart-rate permission, clean the sensor window on the
back of the watch, and tighten the strap.

**Sessions not appearing on the dashboard.** The watch queues them until the server is
reachable — nothing is lost. Confirm the watch is on the same network, that the URL is
reachable from the watch (not just your laptop), and that the token matches. History shows
"On watch only" until a session is acknowledged by the server.

**Crashes.** `adb logcat | grep -i badwatch` and file an issue with the stack trace.
