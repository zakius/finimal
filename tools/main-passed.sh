#!/usr/bin/env bash
# Exits 0 when the release workflow's run for a push of <sha> to main succeeded. A release tag publishes only a
# commit that main already proved, so main and the tag never build the same commit at the same time.
# Usage: tools/main-passed.sh <sha>
set -euo pipefail
sha="$1"
conclusion="$(gh run list --workflow release --branch main --event push --commit "$sha" --json conclusion --jq '.[0].conclusion // "none"')"
if [[ "$conclusion" != success ]]; then
  echo "main's run of $sha: $conclusion. Push main alone, wait for it to pass, then push the tag: tools/ship.sh does both." >&2
  exit 1
fi
