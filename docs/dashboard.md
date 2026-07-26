# Bad Watch dashboard

The dashboard is an optional self-hosted Ktor server plus a responsive browser UI. The watch does
not need it to record, review, or retain a session. It adds authenticated detailed review, diary
editing, filters, JSON backup/restore, reviewed CSV, and consent-bound Detection Lab tooling.

The bundled server speaks plain HTTP. Release builds of the watch app keep Android's secure
cleartext default and therefore require an **HTTPS** URL, normally supplied by a TLS reverse proxy.
Debug builds alone include a network-security override for trusted-LAN HTTP development.

## Run locally

With no environment variables, the server binds only to loopback:

```bash
./gradlew :server:run
# http://127.0.0.1:8080/
```

Loopback development may run without a token because no other machine can connect. Configuration:

| Variable | Default | Purpose |
| --- | --- | --- |
| `BADWATCH_HOST` | `127.0.0.1` | Bind address. Use `0.0.0.0` or a LAN address only with a token. |
| `BADWATCH_PORT` | `8080` | Listen port, from 1 through 65535. |
| `BADWATCH_DATA_DIR` | `./badwatch-data` | Session files plus the `captures/` subdirectory. |
| `BADWATCH_TOKEN` | unset | Shared bearer token for every data API. Mandatory for a non-loopback bind. |

To make a development server reachable from a watch on a private LAN:

```bash
BADWATCH_HOST=0.0.0.0 \
BADWATCH_TOKEN="$(openssl rand -hex 24)" \
BADWATCH_DATA_DIR=/var/lib/badwatch \
BADWATCH_PORT=8080 \
./gradlew :server:run
```

The process refuses to start when a non-loopback host has no token. This is deliberate: session
records contain health and personally identifying diary fields.

For a standalone distribution:

```bash
./gradlew :server:installDist
BADWATCH_HOST=127.0.0.1 ./server/build/install/server/bin/server
```

### TLS for a release watch

Put the server behind a TLS-terminating reverse proxy such as Caddy or nginx, keep Ktor on
loopback, and configure the public `https://` base URL on the watch. The proxy must preserve the
`Authorization` header and serve the browser UI and API from the same origin.

Set `BADWATCH_TOKEN` even though Ktor itself is bound to loopback: the reverse proxy makes that
loopback service reachable. Enter the same token on the watch and in the browser prompt.

Do not expose plain Ktor directly to the internet. A single shared token is appropriate for one
owner or a small trusted deployment; it is not multi-user access control.

## Configure the watch

Release and debug builds provide **Settings → Dashboard**. Enter the base URL and shared bearer
token, then choose **Save & test**. A token is optional only for a truly loopback-only development
server, which a watch cannot reach. The setup handshake calls `GET /api/v1/status` with the bearer
token when configured, verifies the shared schema version, and reports the connection result.
Saved configuration is then used by subsequent WorkManager sync. Replacing the URL without
entering a new token retains the saved token; removing the server clears both.

An `http://` URL displays a warning. It is useful only in a debug build whose debug-only network
security configuration permits cleartext. A release build should use the reverse-proxied HTTPS
URL.

Debug builds also retain an adb shortcut:

```bash
adb shell am broadcast -a com.badwatch.app.SET_DASHBOARD \
  -n com.badwatch.badwatch/com.badwatch.app.debug.DashboardConfigReceiver \
  --es url "http://192.168.1.20:8080" \
  --es token "<token>"
```

The exported receiver exists only in `src/debug`; it is intentionally absent from release APKs.

## Sync behavior

- A completed session is atomically durable on the watch before upload is attempted.
- `SyncWorker` requires a network and uses WorkManager retry/backoff for transient failures.
- Sessions and consent-eligible captures upload independently, so one class cannot block the other.
- The server accepts or rejects each record ID separately.
- A changed diary with a higher revision merges only when its acknowledged base is the current
  server revision. Stale branches cannot leapfrog browser edits; append-only corrections extend
  only a matching prefix, and divergent branches surface as a payload-specific conflict.
- Accepted and rejected states are stored beside the watch record with a payload fingerprint.
- An unchanged explicit rejection is not retried forever and its reason is visible in History.
- Editing a diary or correction changes the payload fingerprint and makes the record pending again.
- The client refuses to follow a redirect while carrying the bearer credential.

The browser keeps a supplied token in `sessionStorage`, sends it only in the Authorization header,
and never places it in the URL or browser history.

## Reviewed analytics

Dashboard aggregates and detail charts lead with the latest reviewed projection:

- corrected timestamped detected hits after edge trim and false-hit removal;
- exchanges rebuilt from the surviving timestamped events;
- reviewed duration and optical-HR coverage;
- provisional stroke mix from surviving detected events;
- evidence-backed insights using only prior, usable, like-for-like history;
- reported context, RPE, soreness, equipment, conditions, goal, and notes.

Reported missed hits remain a separate untimed number. They do not enter detected-hit charts,
exchange timing, active-time estimates, or stroke mix. Every detail response also contains the
immutable raw `SessionExport` for audit.

The rolling volume chart is a transparent seven-day sum of estimated active time compared with
the weekly average from the preceding four complete weeks. It is not ACWR, readiness, tissue load,
or injury risk. HRR-minutes are shown only with sufficient optical coverage and explicitly sourced
resting and maximum heart rate.

## Browser backup, CSV, and restore

The authenticated header exposes three owner actions:

- **Backup JSON** downloads a lossless deterministic archive of every session and only those raw
  captures that recorded model-training consent plus participant/protocol metadata.
- **Export CSV** downloads a human-readable reviewed diary with raw and corrected totals,
  effective duration, context, report, equipment, conditions, HR coverage, and correction count.
  CSV is not a restore format.
- **Restore** validates the complete archive before the first write, then merges by stable ID.
  New records are created, stale diary snapshots cannot erase newer ones, compatible append-only
  reviews are extended, identical records remain unchanged, and absent local records are never
  deleted. Immutable-evidence collisions and divergent histories fail before any semantic write.

Archive encoding omits a generated timestamp and canonicalizes record/key order, so the same data
produces the same bytes. Validation rejects incompatible schemas, duplicate or filesystem-unsafe
IDs, invalid bounds, malformed correction provenance, and ineligible raw captures before mutation.

## Security contract

- `/` and `/api/v1/health` are public so the shell and liveness check can load.
- Every other API route requires the configured bearer token.
- Data API responses send `Cache-Control: no-store`.
- The server does not grant cross-origin browser reads.
- Non-loopback binding without a token is rejected at startup.
- TLS is the operator's reverse-proxy responsibility.
- Protect the data directory and backups as health/personal data.

## API

| Method | Path | Purpose |
| --- | --- | --- |
| `GET` | `/` | Static dashboard shell. |
| `GET` | `/api/v1/health` | Public liveness and schema version. |
| `GET` | `/api/v1/status` | Watch setup handshake and record counts. |
| `POST` | `/api/v1/sessions` | Upload a `SyncEnvelope`; return per-ID acceptance/rejection. |
| `GET` | `/api/v1/sessions` | Stored raw session envelopes, newest first. |
| `GET` | `/api/v1/sessions/{id}` | One lossless raw session for owner/API compatibility. |
| `GET` | `/api/v1/sessions/{id}/detail` | Reviewed primary detail plus immutable raw audit envelope. |
| `PUT` | `/api/v1/sessions/{id}/diary` | CAS-update only typed player-reported diary fields. |
| `DELETE` | `/api/v1/sessions/{id}` | Delete one server session. |
| `GET` | `/api/v1/dashboard` | Reviewed aggregate, filters, and comparison groups. |
| `POST` | `/api/v1/captures` | Upload a consent-eligible `CaptureEnvelope`. |
| `GET` | `/api/v1/captures` | Protocol-complete, consented capture corpus. |
| `GET` | `/api/v1/captures/summary` | Usable swings by label and consenting participants. |
| `GET` | `/api/v1/captures/evaluation` | Rule-classifier evaluation over eligible captures. |
| `GET` | `/api/v1/export/archive` | Deterministic lossless JSON archive. |
| `GET` | `/api/v1/export/sessions.csv` | Reviewed human-readable CSV. |
| `POST` | `/api/v1/import/archive` | Validate, merge, and restore an archive. |

`GET /api/v1/dashboard` supports repeatable or comma-separated `activityMode`, `completion`, and
`recordingQuality` parameters plus one case-insensitive `comparisonTag`. Unknown enum values return
HTTP 400. Diary mutation is intentionally narrower than `SessionExport`: it cannot replace raw
shots, HR, original exchanges, legacy metadata, or correction history.

Every diary form submits the `diaryRevision` it loaded. A successful save increments the revision;
an intervening save returns HTTP 409 and the browser reloads the current record rather than
discarding either edit silently.

## Demo data

Synthetic sessions are created only by the explicit developer command:

```bash
./gradlew :server:seedDemoData -PdataDir=/tmp/badwatch-demo
BADWATCH_DATA_DIR=/tmp/badwatch-demo ./gradlew :server:run
```

They are fixtures for UI and analytics development, never model-accuracy or physiological
evidence.
