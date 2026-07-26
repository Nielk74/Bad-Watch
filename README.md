# Bad Watch

Bad Watch is a private, standalone badminton session companion for Wear OS. Wear it on the
racket hand, tap once, and it keeps a durable record of what the watch actually observed:
elapsed time, candidate hits, inferred exchange bursts, and optical heart rate when available.
After play, add context and correct the detector before comparing like-for-like sessions.

No account. No subscription. No racket sensor. The optional self-hosted dashboard works in any
browser and keeps the archive under your control.

> **Measurement boundary:** a single wrist does not see shuttle contact, the opponent, partner,
> point outcome, court position, or technique. Counts are **detected hits**, exchange boundaries
> and active time are **estimates**, stroke names are **provisional**, and score/RPE/soreness are
> **player-reported**. See [the sport model](docs/SPORT_MODEL.md).

## What is included

| Area | Current behavior |
| --- | --- |
| Reliable recording | Health foreground service, required gyro plus optional accelerometer/Health Services HR, screen-off capture, process-death journal |
| Live watch UI | Glance-first OLED design, optional hit haptics, ambient mode, stop/save/discard controls |
| Session review | Activity, RPE, soreness, completion, recording quality, equipment, conditions, notes, edge trim, false-hit and missed-hit review |
| Honest analysis | Corrected detected-hit totals, rebuilt exchange estimates, HR coverage, transparent HRR-min/session-RPE, evidence-backed insights |
| Progress | Seven-day goals, personal archive records, self-reported experience, multidimensional like-for-like play pattern—never a guessed global level |
| Match utility | Manual singles/doubles BWF scoring, service side, intervals, change of ends, undo, ambient display, durable recovery |
| Practice | Balanced six-corner shadow prompts and sourced BWF practice cards with explicit measurement limits |
| Wear surfaces | Start/recap Tile and rolling seven-day corrected-detected-hit complication |
| Data ownership | Atomic local JSON, fingerprinted sync state, authenticated self-hosted dashboard, reviewed CSV, deterministic backup and validated restore |
| Detection research | Consent-bound labelled capture plus participant-grouped ingestion/training/evaluation; no learned model is shipped without real-player evidence |
| Language/platform | English and French, accessibility semantics, Android 16 granular HR permission, `targetSdk 36` |

The current motion classifier is a rule-based fallback and is not validated against representative
match play. It is useful for exercising the end-to-end detector and research pipeline; do not use
its stroke labels as coaching truth.

## Repository

```text
app/       Wear OS app: sensing, Health Services, foreground lifecycle, stores, sync, UI
core/      Android-free models, detector, corrections, scoring, training and analytics
server/    Ktor API plus responsive self-hosted browser dashboard
docs/      Product contract, sport model, architecture and operating guides
tools/     Consent-aware capture ingestion and offline classifier evaluation/training
isolate/   Wear/emulator inspection helpers
tooling/   Release and real-device endurance probes
```

`core` is the single schema and analysis source for the watch and server. Raw session evidence is
immutable; reviewed metrics are deterministic projections shared by every surface.

## Build

Requirements: JDK 17 and Android SDK platform/build-tools 36.

```bash
brew install --cask android-commandlinetools
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0"
echo "sdk.dir=/opt/homebrew/share/android-commandlinetools" > local.properties

./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --stacktrace --no-daemon
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The complete CI-equivalent gate also verifies the Python model tooling:

```bash
python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 -m py_compile tools/ingest.py tools/train.py tooling/wear_session_probe.py
```

## Dashboard

Local-only, with no token required:

```bash
./gradlew :server:run
# http://127.0.0.1:8080
```

To reach it from a watch on the LAN, a bearer token is mandatory:

```bash
BADWATCH_HOST=0.0.0.0 \
BADWATCH_TOKEN="$(openssl rand -hex 24)" \
./gradlew :server:run
```

Configure and test the URL/token directly in **Settings → Dashboard** on the watch. The server is
plain HTTP; use only a trusted private LAN or put it behind a TLS reverse proxy. Full setup,
backup/restore, API, and security details are in [dashboard.md](docs/dashboard.md).

## Documentation

- [Completed product plan](docs/PRODUCT_PLAN.md)
- [Sport and measurement model](docs/SPORT_MODEL.md)
- [Architecture](docs/architecture.md)
- [Court usage guide](docs/usage.md)
- [Session diary and correction schema](docs/session-diary-schema.md)
- [Manual match mode](docs/match-mode.md)
- [Practice and shadow training](docs/training.md)
- [Accessibility and localization](docs/accessibility-localization.md)
- [Watch-face complication](docs/watch-face-complication.md)
- [Dashboard and data ownership](docs/dashboard.md)
- [Detection research tools](tools/README.md)
- [Maintainer guide](AGENT.MD)
- [Changelog](CHANGELOG.md)

## Versioning and releases

`VERSION.md` is injected as the Android version name/code. CI runs the full software gate on every
push. `tooling/tag_release.sh <version>` validates a clean version bump and creates an annotated
`v<version>` tag; the tag workflow rebuilds, verifies package/signature/version, writes checksums,
and publishes the artifacts.

## License

Apache 2.0.
