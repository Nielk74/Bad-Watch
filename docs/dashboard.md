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
| `BADWATCH_TOKEN` | *(unset)* | Shared bearer token. When unset, uploads are unauthenticated and the server says so at startup. |

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

This writes six weeks of synthetic sessions, including a deliberate training-load spike so
the acute:chronic chart has something to show. Synthetic sessions are only ever written by
this explicit command — nothing generates them at runtime.

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

## Security

The threat model is "a server you run for yourself or your club", so the controls are
deliberately light:

- `BADWATCH_TOKEN` is a single shared bearer token guarding uploads and deletes. Reads are
  open, because the dashboard itself is an unauthenticated page.
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
| `GET` | `/api/v1/captures/summary` | Dataset progress: swings per stroke, contributing devices |

A schema-version mismatch is rejected with HTTP 400 rather than being parsed optimistically —
a silently misread session is worse than an upload the watch retries after an app update.

## What the dashboard shows

- **Shots hit / time on court / average rally / shoulder load** as headline tiles. Time on
  court distinguishes elapsed time from time actually spent in rallies, which for badminton
  is usually around a third.
- **Shots per session** over time — training volume.
- **Rally length distribution** — how long your points really last.
- **Shot mix** — detected stroke distribution.
- **Shoulder load trend** — 7-day acute load against the 28-day chronic baseline. The ratio
  is the standard acute:chronic workload ratio; above ~1.5 is the elevated-risk band and
  below ~0.8 suggests detraining. This is training-load information, not medical advice.
- **Session table** — every session, and the accessible fallback for every chart above.

Shoulder load weights overhead strokes by the cube of swing intensity: fifty gentle clears
and fifty full smashes are not the same session for a rotator cuff, and a linear count
treats them as identical.
