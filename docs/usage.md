# Bad Watch usage guide

## Before the first session

1. Wear the watch snugly on your **racket hand**. Bad Watch reads that wrist's swing; the other
   wrist cannot produce meaningful hit detections.
2. Choose left- or right-handed during onboarding. You can change it later in Settings.
3. Heart rate and notifications are optional. If heart-rate permission is denied, Health Services
   is unavailable, or another app owns the workout, the session remains motion-only. Bad Watch does
   not interrupt the other workout.
4. In **Settings → Heart-rate profile**, optionally enter adult age or an exact maximum HR. Zones
   need a maximum. HR-reserve values additionally need a resting HR. An exact maximum overrides the
   age estimate; **Clear profile** removes the authorization.
5. Detected-hit vibrations are off by default. Enable them only if feedback during a rally is useful
   rather than distracting.

The app does not need an account, phone, dashboard, or network.

## Record a session

1. From Home, tap **Start session**. The Tile's Start action opens the same path.
2. Play normally. The health foreground service continues with the display off.
3. The glance face shows corrected/live detector output:
   - large **detected-hit** count;
   - elapsed recording time;
   - current optical BPM only while a fresh Health Services reading exists.
4. Swipe for detected-play detail: inferred exchange bursts, average detected hits, estimated
   active:quiet structure, and the latest provisional stroke label.
5. Tap **Stop & save**, or use confirmed **Discard** if no record should remain.

If Wear OS recreates the process, Bad Watch restores the same session and start time from its
checkpoint. The unobserved interval is not invented, and recording quality is marked partial so
the player can decide whether to keep it.

## Review the session

After save, a skippable five-question diary asks:

- activity type;
- reported effort (RPE 0–10);
- whether soreness was reviewed and, optionally, one quick body-area entry;
- whether the intended session was completed;
- whether the recording looks complete, partial, unusable, or unreviewed.

Completion and recording quality are different facts. Finishing a match does not prove the watch
captured all of it. RPE and soreness are player reports, not sensor conclusions or medical advice.

The recap then shows:

- corrected **detected** hits, inferred bursts, and reviewed duration;
- the player report and up to three evidence-backed observations;
- average/longest inferred burst and estimated active:quiet structure;
- measured average/peak HR and signal coverage when available;
- personalized zones/HRR-min only when the profile authorizes them;
- a provisional stroke mix for detector inspection, not coaching;
- the correction trail.

### Correct detector output

Tap **Review detection** from a recap or historical detail. You can:

- trim time from the recording start or end;
- mark recent timestamped motion events that were not hits;
- report how many hits the detector missed.

False events and edge trim change the primary detected count, reviewed duration, rebuilt exchange
bursts, insight baseline, Progress, Tile, complication, dashboard, and CSV. Reported misses stay a
separate number because they have no event time or provisional type; they never inflate a detected
headline or exchange.

Every edit appends an actor/time/reason revision. The raw events and original inferred profile are
kept for audit and backup.

## History and Progress

**History** shows each durable session, whether it is accepted by the dashboard, only on the watch,
or explicitly rejected by the server. A rejection is mapped to a stable localized category; raw
server diagnostics are not rendered as untranslated player copy. Permanent rejections stop
retrying until the record is edited. Open a session to review/correct it. Deletion requires
confirmation and removes the local record; it is not a remote-delete command.

Mark a broken recording **Unusable** rather than deleting it when the audit trail matters. Unusable
sessions remain in History but do not contribute to Home, Progress, personal baselines, Tile, or
complication totals.

**Progress** contains:

- player-chosen session and recorded-minute goals for the rolling seven days;
- personal records from usable reviewed data;
- editable self-reported playing experience;
- a like-for-like play pattern after five qualifying sessions across three days.

The play pattern reports descriptive medians for one comparable context. It never turns more hits,
heart rate, or activity into a global badminton level.

## Manual match scoreboard

Open **Match score**, choose singles/doubles and the first serving side, then tap the side that won
each rally. Bad Watch applies:

- rally scoring to 21;
- a two-point winning margin;
- 30-point cap;
- best of three;
- interval at 11;
- change-of-ends prompts and service side;
- undo.

The watch never awards a point automatically. In doubles it knows only the serving pair and
left/right court, not which partner is serving. The active score and undo history survive process
recreation. Closing a completed scoreboard clears that live utility; it is not uploaded as a
sensor session.

## Practice and shadow training

Open **Practice** for two distinct tools:

- **Watch-guided six-corner shadow:** follow the visual/haptic racket-relative corner, move and
  recover, then tap **I'm back at base**. The recorded value is cue-to-confirmation delay—not
  reaction time, corner arrival, return-to-base detection, speed, balance, or technique quality.
- **BWF practice cards:** player-selected general cues for movement rhythm, lunge, overhead
  preparation, and relaxed grip. Each card states its source and what a wrist cannot assess.

Shadow can pause, resume, finish early, and recover after process recreation without counting the
unobserved downtime.

## Detection Lab and raw-motion consent

Detection Lab is research tooling, deliberately secondary on Home. Pick the stroke you intend to
repeat, perform the drill, discard a bad final window if needed, and save.

Raw windows stay local by default. **Share detection drills** applies only to captures started after
the toggle is enabled. Consent, anonymous participant ID, protocol, profile, device, and app version
are frozen before the first sample; later settings cannot retroactively release an older capture.
The current heuristic stroke classifier remains provisional even when the lab pipeline is working.

## Configure the optional dashboard

Run the server first; see [dashboard.md](dashboard.md). On the watch:

1. Open **Settings → Dashboard**.
2. Enter the complete base URL and optional bearer token.
3. Save and run the connection check.

Plain HTTP shows a warning because token and session data are readable in transit. Use it only on a
trusted private LAN; prefer HTTPS through a reverse proxy. An unchanged record explicitly rejected
by the server is quarantined with the reason instead of retrying forever. Edit it after correcting
the cause to create a new upload candidate.

## Export, backup, and restore

The release data-ownership path is the authenticated browser dashboard:

- **Backup JSON**: deterministic, lossless sessions plus only eligible consented raw captures;
- **Export CSV**: reviewed spreadsheet view with raw/corrected audit columns;
- **Restore**: validate the complete archive, then merge without deleting records absent from it.

For development builds only, maintainers can inspect the app sandbox with `adb run-as`; that is not
a release-user export feature.

## Reading the numbers

| Label | Meaning |
| --- | --- |
| Recorded duration | Direct clock interval, with process-recovery caveat when marked partial |
| Detected hit | Racket-wrist motion event; not proof of shuttle contact |
| Provisional stroke | Unvalidated heuristic label; not coaching evidence |
| Detected exchange / rally burst | Two or more nearby wearer detections; not the full rally |
| Estimated active span | First-to-last wearer detection inside kept bursts; not exact playing time |
| Heart-rate coverage | Approximate share of elapsed seconds with distinct optical readings |
| HRR-min | Reviewed minutes × mean HR reserve, only with sourced endpoints and sufficient coverage |
| Session-RPE | Reviewed minutes × player-reported RPE; descriptive, not an injury forecast |

The detailed contract is [SPORT_MODEL.md](SPORT_MODEL.md).

## Troubleshooting

**No hits:** confirm racket-hand wear and a snug strap. Compact/slow strokes and unusual motions can
be missed; other arm movements can be false positives. Use post-session correction.

**No heart rate:** check permission, sensor contact, and whether another fitness app is already
tracking. Motion recording should continue. On Android 16 the permission is **Heart rate**
(`READ_HEART_RATE`), not the older body-sensors label.

**Recording stopped:** check the ongoing notification and History. A process restart should restore
the same session as partial. Record `adb logcat` and the device/API level for a bug report.

**Dashboard connection failed:** from the watch—not only the computer—verify the host/port, token,
network, and TLS certificate. The bundled server defaults to loopback; LAN access requires
`BADWATCH_HOST=0.0.0.0` (or a specific LAN address) and a token.

**Server rejected a record:** open History for its exact reason. Fix the invalid/incompatible field
or update the server, then edit/resave the session. Do not repeatedly delete/recreate it.
