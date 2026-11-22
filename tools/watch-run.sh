#!/usr/bin/env bash
# Waits for one GitHub Actions run and exits at its first failed job, not when its slowest job ends.
# Exit 0: the run succeeded. Exit 1: a job failed, was cancelled or timed out. Exit 2: gave up after 60 minutes.
# Usage: tools/watch-run.sh <run id>
set -euo pipefail
run="$1"
deadline=$((SECONDS + 3600))
while ((SECONDS < deadline)); do
  view="$(gh run view "$run" --json status,conclusion,url,jobs)"
  failed="$(jq -r '.jobs[] | select(.conclusion == "failure" or .conclusion == "cancelled" or .conclusion == "timed_out") | .name' <<< "$view")"
  if [[ -n "$failed" ]]; then
    echo "run $run: failed job(s): $(tr '\n' ' ' <<< "$failed")"
    jq -r .url <<< "$view"
    exit 1
  fi
  if [[ "$(jq -r .status <<< "$view")" == completed ]]; then
    conclusion="$(jq -r .conclusion <<< "$view")"
    echo "run $run: $conclusion"
    [[ "$conclusion" == success ]] && exit 0
    jq -r .url <<< "$view"
    exit 1
  fi
  sleep 20
done
echo "run $run: still running after 60 minutes"
exit 2
