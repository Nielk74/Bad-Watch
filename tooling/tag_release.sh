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

./gradlew clean test

git commit -am "chore: release $VERSION"
git tag -a "v$VERSION" -m "Release $VERSION"

echo "Tagged v$VERSION. Push with: git push origin main --tags"
