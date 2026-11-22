#!/usr/bin/env bash
# Fails on code patterns that caused bugs here more than once. Each message names the fix and the commit that
# paid for the lesson. Runs on the tracked files of the repository it is started in.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

status=0
rule() {
  local message="$1" pattern="$2"
  shift 2
  local hits
  hits="$(git grep --untracked -nE "$pattern" -- "$@" || true)"
  [[ -z "$hits" ]] && return 0
  status=1
  printf '%s\n  %s\n\n' "$hits" "$message"
}

rule "runCatching also catches CancellationException, so a cancelled coroutine runs on (13b6ebc, 5beae1e). Use finimal.core.catching { }." \
  '\brunCatching\b' '*.kt'
rule "File.renameTo can't replace a file on Windows and fails without an exception (5beae1e). Use finimal.core.moveOver." \
  '\.renameTo\(' 'core/*.kt' 'shared/*.kt' 'desktop/*.kt'

exit $status
