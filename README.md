# Bad Watch

Bad Watch is a Wear OS 5.1 training companion built for the Pixel Watch 3. It keeps score of badminton sessions directly from the wrist by fusing the gyroscope and heart-rate sensors to detect shot types, effort, and fatigue in real time.

## Highlights
- Real-time shot classification for smash, clear, drop, drive, and backhand drive swings.
- Live metrics HUD with heart-rate zone tracking, fatigue/effort scores, and last-shot context.
- Automatic session summaries with shot distribution, rally insights, and recovery scoring kept on the watch via DataStore.
- Tested analytics pipeline (`core` module) plus ViewModel unit coverage.
- Semantic versioning (`VERSION.md`) and release automation stub (`tooling/tag_release.sh`).

## Project Layout
```
.
├── app/        # Wear OS Compose app, sensors, UI, ViewModel
├── core/       # Platform-agnostic analytics, classifiers, session math
├── docs/       # Architecture notes, usage guide
├── tooling/    # Release/version scripts
└── VERSION.md  # SemVer snapshot
```

## Getting Started
1. Ensure you have Android Studio Koala Feature Drop (or newer) with Wear OS toolchain installed.
2. Connect a Pixel Watch 3 (Wear OS 5.1) or start a compatible emulator.
3. From the repo root run:
   ```bash
   ./gradlew :app:assembleDebug
   ```
4. Install on the watch via `adb install app/build/outputs/apk/debug/app-debug.apk`.

## Running Tests
- Core analytics:
  ```bash
  ./gradlew :core:test
  ```
- Application layer (ViewModel + analytics):
  ```bash
  ./gradlew :app:test
  ```
- Full suite:
  ```bash
  ./gradlew test
  ```

## Documentation
- Architecture: `docs/architecture.md`
- User guide: `docs/usage.md`
- Changelog: `CHANGELOG.md`

## Versioning & Releases
- Current version comes from `VERSION.md` and is injected into the Android Manifest at build time.
- Use `tooling/tag_release.sh <new-version>` to bump the version, run tests, and tag a release (script validates the working tree is clean and tests are green before tagging).

## License
Released under the Apache 2.0 License.
