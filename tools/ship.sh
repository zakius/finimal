#!/usr/bin/env bash
# Publishes the "Release X.Y.Z" commit at HEAD: pushes main, waits for its run, then pushes the tag vX.Y.Z and waits
# for the release. Each wait stops at the first failed job.
# Usage: tools/ship.sh
set -euo pipefail
cd "$(dirname "$0")/.."
version="$(sed -n 's/^finimal.versionName=//p' gradle.properties)"
sha="$(git rev-parse HEAD)"
[[ "$(git log -1 --format=%s)" == "Release $version" ]] || { echo "HEAD is not \"Release $version\"." >&2; exit 1; }
[[ "$(git branch --show-current)" == main ]] || { echo "Not on main." >&2; exit 1; }
[[ -z "$(git status --porcelain)" ]] || { echo "Uncommitted changes." >&2; exit 1; }

run_of() {
  local ref="$1" id=""
  for _ in $(seq 30); do
    id="$(gh run list --workflow release --branch "$ref" --event push --commit "$sha" --json databaseId --jq '.[0].databaseId // empty')"
    [[ -n "$id" ]] && break
    sleep 5
  done
  [[ -n "$id" ]] || { echo "No run of $sha on $ref after 150 s." >&2; exit 1; }
  echo "$id"
}

git push origin main
tools/watch-run.sh "$(run_of main)"
git tag "v$version" "$sha" 2>/dev/null || [[ "$(git rev-parse "v$version^{commit}")" == "$sha" ]] || { echo "v$version already tags another commit." >&2; exit 1; }
git push origin "v$version"
tools/watch-run.sh "$(run_of "v$version")"
gh release view "v$version" --json url --jq .url
