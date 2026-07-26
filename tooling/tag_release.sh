#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <version>" >&2
  exit 1
fi

VERSION="$1"

if ! [[ $VERSION =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Version must follow SemVer (e.g. 1.2.3)" >&2
  exit 1
fi

if [[ -n "$(git status --porcelain)" ]]; then
  echo "Working tree dirty. Commit or stash changes before tagging." >&2
  exit 1
fi

echo "$VERSION" > VERSION.md

if command -v sd >/dev/null 2>&1; then
  sd '## \[Unreleased\]' "## [Unreleased]\n\n## [$VERSION] - $(date +%Y-%m-%d)" CHANGELOG.md
else
  printf '\n## [%s] - %s\n- Describe changes.\n' "$VERSION" "$(date +%Y-%m-%d)" >> CHANGELOG.md
fi

python3 -m unittest discover -s tools -p 'test_*.py' -v
python3 -m py_compile tools/ingest.py tools/train.py \
  tooling/wear_session_probe.py tooling/wear_recovery_probe.py

./gradlew test :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --stacktrace --no-daemon

git commit -am "chore: release $VERSION"
git tag -a "v$VERSION" -m "Release $VERSION"

echo "Tagged v$VERSION. Push with: git push origin master --tags"
echo "The tag push triggers .github/workflows/release.yml, which repeats the full gate,"
echo "verifies package/version/signatures, and publishes APK checksums with the release."
