# Bad Watch Dashboard

The dashboard is a small self-hosted server. The watch pushes finished sessions to it over
HTTPS; you open the dashboard in any browser — phone, laptop, tablet.

## Why a web dashboard rather than a phone app

- **Nothing extra to install.** No companion APK, no Play Store pairing, no Data Layer
  handshake. The Pixel Watch 3 has WiFi and LTE and posts directly.
- **Works everywhere.** The same URL opens on a phone at the club and on a desktop at home.
- **Charts are cheap in a browser** and expensive in Compose.
- **One schema.** `:server` depends on `:core`, so the server deserializes the exact Kotlin
  types the watch serialized. A wire-format mistake is a compile error, not a runtime
  surprise.

The trade-off is that you must run a server somewhere. The watch does not require it — every
feature except the dashboard works with no network at all, and sessions queue on the watch
until the server is reachable.

## Running it

```bash
./gradlew :server:run
```

Configuration is entirely environment variables, all optional:

| Variable | Default | Purpose |
| --- | --- | --- |
| `BADWATCH_PORT` | `8080` | Listen port |
| `BADWATCH_DATA_DIR` | `./badwatch-data` | Where session JSON is written |
| `BADWATCH_TOKEN` | *(unset)* | Shared bearer token for every session, capture and dashboard data request. When unset, data APIs are unauthenticated and the server says so at startup. |

```bash
BADWATCH_TOKEN=$(openssl rand -hex 24) \
BADWATCH_DATA_DIR=/var/lib/badwatch \
BADWATCH_PORT=8080 \
./gradlew :server:run
```

For a standalone distribution:

```bash
./gradlew :server:installDist
./server/build/install/server/bin/server
```

### Seeding demo data

To develop against the dashboard without a watch:

```bash
./gradlew :server:seedDemoData -PdataDir=/tmp/badwatch-demo
BADWATCH_DATA_DIR=/tmp/badwatch-demo ./gradlew :server:run
```

This writes six weeks of synthetic sessions with changing session volume so the activity
history has something to show. Synthetic sessions are only ever written by this explicit
command — nothing generates them at runtime.

## Pointing the watch at it

The watch needs the base URL (and token, if set). Typing a URL on a watch is miserable, so
debug builds accept it over adb:

```bash
adb shell am broadcast -a com.badwatch.app.SET_DASHBOARD \
  -n com.badwatch.badwatch/com.badwatch.app.debug.DashboardConfigReceiver \
  --es url "http://192.168.1.20:8080" \
  --es token "<token>"
```

The receiver is declared only in `src/debug/AndroidManifest.xml`. It must be exported for
`am broadcast` to reach it, and an exported receiver that rewrites the upload destination is
exactly the shape of an exfiltration hole — so it does not exist in release builds. A proper
in-app settings flow (or phone-side pairing) is the release path, and is still to be built.

Sync behaviour:

- Sessions are saved to the watch the instant they end. Upload is entirely separate.
- `SyncWorker` runs when the network is available, with exponential backoff.
- The server acknowledges each session id individually, so a partial failure re-uploads only
  what did not land.
- Sessions the server explicitly rejects are not retried forever.

When `BADWATCH_TOKEN` is set, opening the dashboard prompts for it after the static shell
loads. The browser keeps it in `sessionStorage`, so it lasts only for the current tab, and
sends it as an `Authorization: Bearer …` header. It is never put in the URL, browser history
or server access-log query string. A local server without a token opens directly with no
prompt.

## Security

The threat model is "a server you run for yourself or your club", so the controls are
deliberately light:

- `BADWATCH_TOKEN` is a single shared bearer token guarding all data reads, uploads and
  deletes. The static dashboard shell and `/api/v1/health` stay public; the shell cannot read
  any session or capture data until the token is supplied.
- Browser API access is same-origin. The server does not grant cross-origin reads; put a
  separate front end behind the same reverse-proxy origin if you build one.
- **Do not expose the server to the internet without a token**, and put it behind a TLS
  terminating proxy (Caddy, nginx, Cloudflare Tunnel). The server speaks plain HTTP.
- Sessions contain heart-rate data. Treat the data directory as health data.

If this ever needs real multi-user access control, that is a genuine feature, not a config
flag — see the coach-mode item in the product plan.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/` | The dashboard page |
| `GET` | `/api/v1/health` | Liveness plus the schema version this server speaks |
| `POST` | `/api/v1/sessions` | Upload a `SyncEnvelope`; returns accepted/rejected ids |
| `GET` | `/api/v1/sessions` | Every stored session, newest first |
| `GET` | `/api/v1/sessions/{id}` | One full session including every shot |
| `DELETE` | `/api/v1/sessions/{id}` | Remove a session (requires the token) |
| `GET` | `/api/v1/dashboard` | Pre-aggregated data the dashboard page renders |
| `POST` | `/api/v1/captures` | Upload labelled training drills (`CaptureEnvelope`) |
| `GET` | `/api/v1/captures` | Full labelled capture corpus, newest first |
| `GET` | `/api/v1/captures/summary` | Dataset progress: swings per stroke, contributing devices |
| `GET` | `/api/v1/captures/evaluation` | Rule-based classifier score against labelled captures |

When `BADWATCH_TOKEN` is configured, every API route in this table except
`/api/v1/health` requires `Authorization: Bearer <token>`. The `/` shell remains available
so it can collect that token without putting credentials in a link.

A schema-version mismatch is rejected with HTTP 400 rather than being parsed optimistically —
a silently misread session is worse than an upload the watch retries after an app update.

## What the dashboard shows

- **Detected hits / time on court / average detected exchange / HR load** as headline tiles.
  A detected hit is a racket-wrist contact candidate, not the match's total shot count.
- **Detected hits per session** over time — a transparent external-volume measure.
- **Detected exchange length** — bursts inferred from quiet gaps between the wearer's hits,
  not authoritative rally boundaries or the full two-player shot count.
- **Shot mix** — provisional detected stroke labels. Do not use it for coaching decisions
  until the classifier is calibrated against real play.
- **Estimated active-time volume** — a rolling seven-day sum compared with the weekly average
  from the preceding four complete weeks. Both lines use the same unit. No ratio is labelled
  a safe zone, readiness score, or injury-risk prediction.
- **Session table** — every session, and the accessible fallback for every chart above.

When optical heart rate was actually recorded, the dashboard also shows cardiovascular load
as elapsed minutes multiplied by mean heart-rate reserve (`HRR-min`), alongside signal
coverage. It is withheld below 60% signal coverage and remains separate from hits and
inferred active time because those units are not interchangeable. Old or incomplete sessions
show an em dash instead of a fabricated value.

All hit, exchange and stroke classification remains provisional. The dashboard reports the
one-wrist detector's output explicitly; it does not claim to observe the shuttle, opponent,
partner, official rally boundary or point outcome.
