# Bad Watch Usage Guide

## Before you start

- **Wear the watch on your racket hand.** This is a hard requirement, not a preference.
  Bad Watch detects shots from the swing, so on the other wrist there is nothing to detect.
  The app asks you to confirm this the first time it opens.
- Set left- or right-handed in onboarding. The backhand signature mirrors between hands.
- Wear the watch snugly. A loose strap ruins both heart rate and swing detection.
- Grant body sensors and notifications when prompted. Both are optional — recording works
  without either — but you lose heart rate and the ongoing session notification.

## Recording a session

1. Open **Bad Watch** and tap **Start session**.
2. Play. You can put your wrist down; recording continues with the screen off, via a
   foreground service. A notification shows the running shot count.
3. Glance at your wrist any time for the live screen:
   - Shot count (the large number) and elapsed time
   - Current heart rate, rally count, and work:rest ratio
   - Last detected shot with its confidence
   - Rally summary: average shots, longest rally, share of the session actually playing
4. Tap **Stop & save** to end and store the session, or **Discard** to throw it away.

You can also stop from the notification without opening the app.

## Reading the numbers

**Rally structure is the headline.** Badminton is an interval sport: a 60-minute session
usually contains only 20-25 minutes of actual play. Total duration on its own tells you
almost nothing.

- **Work:rest** — playing time against resting time, shown as `1:N`. Singles typically sits
  near 1:2, doubles nearer 1:1.5. Drifting toward 1:4 means you are resting far more than
  you think, which is usually the most surprising number in a recap.
- **Playing time %** — the share of the session spent in rallies.
- **Average / longest rally** — in shots. Long rallies are where fitness and error rates
  show up.
- **Heart rate** — average and peak across the session.

**Shot types are provisional.** The classifier is currently rule-based and has not been
calibrated against real play. Treat stroke labels as indicative; shot *counts* and *rally
structure* are far more trustworthy than the specific stroke names. This is the main focus
of the next phase of work.

## The dashboard

Sessions stay on the watch and are pushed to your dashboard server whenever the watch has
network. Nothing is lost if the server is unreachable or you never set one up.

The dashboard adds what does not fit on a watch: training volume over time, rally length
distribution, shot mix, and a shoulder-load trend with an acute:chronic workload ratio.

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

**No shots detected.** Confirm the watch is on your racket hand and the strap is snug. Give
the classifier a few full swings; gentle practice motions may fall below threshold. Very
short or very slow strokes are the most likely to be missed.

**Recording stopped when the screen turned off.** It should not — that is what the
foreground service is for. Check that the session notification is present. If the watch is
in battery saver, Wear OS may still restrict background work.

**No heart rate.** Re-grant the body sensors permission, clean the sensor window on the
back of the watch, and tighten the strap.

**Sessions not appearing on the dashboard.** The watch queues them until the server is
reachable — nothing is lost. Confirm the watch is on the same network, that the URL is
reachable from the watch (not just your laptop), and that the token matches. History shows
"On watch only" until a session is acknowledged by the server.

**Crashes.** `adb logcat | grep -i badwatch` and file an issue with the stack trace.
