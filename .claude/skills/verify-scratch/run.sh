#!/usr/bin/env bash
# Model-checks Scratch.tla with TLC, one check per run, and compares each result with the baseline
# below. Exit 0: every result matches the baseline. Exit 1: something changed. Exit 2: tooling
# problem. Exit 3: matches, but a covered file differs from the hash in SKILL.md. Full TLC output
# goes to $OUT (printed at the end).
# --confirm: after you re-read the model against the code, rewrites the hashes in SKILL.md, only
# when every run matches its baseline.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
CONFIRM=0
[[ "${1:-}" == "--confirm" ]] && CONFIRM=1

TLA_VERSION=v1.7.4
TLA_SHA256=936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88
JAR="${FINIMAL_FORMAL_CACHE:-$HOME/.cache/finimal-formal}/$TLA_VERSION/tla2tools.jar"

# One TLC run per line: name | check | constants that differ from Scratch.cfg | baseline. The
# baseline is "pass", or the action sequence of TLC's counterexample (shortest for an invariant;
# for ReadsReturn a loop, "Action->N" going back to state N): a known finding (SKILL.md "Known
# findings") or a replay of a fixed race, which must keep failing.
RUNS='
types                  | TypeOK                  |                                                                       | pass
older-keeps-out        | OlderEntryKeepsOut      |                                                                       | pass
delete-open            | DeleteSparesOpen        |                                                                       | pass
old-filler-parts-all   | OldFillerLeavesParts    |                                                                       | pass
reads-return           | ReadsReturn             |                                                                       | pass
older-keeps-out-evict  | OlderEntryKeepsOut      | DeleteFindsEntry=TRUE                                                 | pass
old-filler-truncate    | OldFillerNeverTruncates | DeleteFindsEntry=TRUE                                                 | pass
old-filler-parts       | OldFillerLeavesParts    | DeleteFindsEntry=TRUE                                                 | pass
delete-open-evict      | DeleteSparesOpen        | DeleteFindsEntry=TRUE                                                 | pass
range-ignored          | NoRequestLoop           | NChunks=2 MaxEntries=1 IgnoresRange=TRUE                              | pass
5beae1e-delete-race    | DeleteSparesOpen        | DeleteFindsEntry=TRUE PreFixDeleteUnlocked=TRUE                       | Init Lookup DelLookup DelRelease Demand DelRemove Lookup Demand DelFiles
5beae1e-busy-loop      | ReadsReturn             | DeleteFindsEntry=TRUE PreFixDeleteUnlocked=TRUE PreFixNoDeadline=TRUE | Init Lookup DelLookup DelRelease Demand DelRemove Lookup Demand Pick HeaderArrives Tick StartChunk WriteChunk Commit ReadFind DelFiles DelLookup DelRefused ReadRetry ReadFind->18
5beae1e-old-filler     | OldFillerLeavesParts    | DeleteFindsEntry=TRUE PreFixNoGoneCheck=TRUE                          | Init Lookup DelLookup Demand Tick ReadFail Pick HeaderArrives StartChunk Close DelRelease DelRemove Lookup Demand BodyFails Pick HeaderArrives StartChunk Finally
5beae1e-retry-deadline | ReadsReturn             | MaxEntries=1 PreFixNoDeadline=TRUE PreFixDeleteNoRecheck=TRUE         | Init DelLookup Lookup Demand Tick Pick HeaderArrives StartChunk WriteChunk Commit DelRemove DelLookup DelRemove ReadFind ReadRetry DelLookup->12
5beae1e-range-loop     | NoRequestLoop           | NChunks=2 MaxEntries=1 IgnoresRange=TRUE PreFixNoSkip=TRUE            | Init Lookup Demand Pick HeaderArrives StopFetch
delete-recheck         | DeleteSparesOpen        | PreFixDeleteNoRecheck=TRUE                                            | Init DelLookup Lookup Demand DelRemove
open-unlocked          | OldFillerNeverTruncates | DeleteFindsEntry=TRUE PreFixOpenUnlocked=TRUE                         | Init Lookup DelLookup Demand Tick ReadFail Pick HeaderArrives StartChunk Close DelRelease DelRemove Lookup Demand Pick HeaderArrives StartChunk OpenPart OpenPart
length-no-gone         | OlderEntryKeepsOut      | DeleteFindsEntry=TRUE PreFixLengthNoGone=TRUE                         | Init Lookup DelLookup Demand Tick ReadFail Pick Close DelRelease DelRemove Lookup Demand Pick HeaderArrives HeaderArrives
'
PROPERTIES=" ReadsReturn "

if [[ ! -f "$JAR" ]]; then
  mkdir -p "$(dirname "$JAR")"
  curl -fsSL -o "$JAR.tmp" "https://github.com/tlaplus/tlaplus/releases/download/$TLA_VERSION/tla2tools.jar"
  mv "$JAR.tmp" "$JAR"
fi
if ! echo "$TLA_SHA256  $JAR" | sha256sum -c --status; then
  echo "tla2tools.jar at $JAR does not match the pinned SHA-256; delete it and rerun." >&2; exit 2
fi
JAVA="${JAVA:-$(command -v java || true)}"
[[ -z "$JAVA" ]] && JAVA="$(ls -d "$HOME"/toolchain/jdk-*/bin/java 2>/dev/null | tail -1 || true)"
[[ -x "$JAVA" ]] || { echo "No java: set JAVA=/path/to/bin/java (11 or newer)." >&2; exit 2; }

HASHES="$(awk '/^```sha256$/{on=1;next} /^```$/{on=0} on' "$HERE/SKILL.md")"
[[ -n "$HASHES" ]] || { echo "No sha256 block in SKILL.md." >&2; exit 2; }
COVERED=($(echo "$HASHES" | awk '{print $2}'))
drift=0
while read -r hash file; do
  [[ "$(cd "$REPO" && sha256sum "$file" 2>/dev/null | awk '{print $1}')" == "$hash" ]] && continue
  [[ $drift == 0 ]] && echo "DRIFT: covered files differ from the hashes in SKILL.md (working tree included):"
  drift=1
  base=""
  for c in $(git -C "$REPO" log --format=%H -- "$file"); do
    if [[ "$(git -C "$REPO" show "$c:$file" 2>/dev/null | sha256sum | awk '{print $1}')" == "$hash" ]]; then base=$c; break; fi
  done
  if [[ -n "$base" ]]; then
    echo "  $file: confirmed at ${base:0:10}; read: git diff ${base:0:10} -- $file"
  else
    echo "  $file: no commit has the confirmed content; re-read the whole file against the model"
  fi
done <<< "$HASHES"
[[ $drift == 1 ]] && echo "  Re-read the changes against the model before trusting the result (SKILL.md, step 2)." && echo

mkdir -p "$REPO/build/formal"
OUT="$(mktemp -d "$REPO/build/formal/scratch.XXXXXX")"
status=0
while IFS='|' read -r run check consts expected; do
  run="$(echo $run)"; check="$(echo $check)"; consts="$(echo $consts)"; expected="$(echo $expected)"
  [[ -z "$run" ]] && continue
  kind=INVARIANT
  [[ "$PROPERTIES" == *" $check "* ]] && kind=PROPERTY
  sedargs=(-e "s/^INVARIANT .*/$kind $check/")
  for kv in $consts; do sedargs+=(-e "s/^\( *${kv%%=*} = \).*/\1${kv#*=}/"); done
  sed "${sedargs[@]}" "$HERE/Scratch.cfg" > "$OUT/$run.cfg"
  (cd "$HERE" && "$JAVA" -Djava.io.tmpdir="$OUT" -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -workers 1 -fp 0 -seed 0 -lncheck final -deadlock \
      -metadir "$OUT/meta-$run" -config "$OUT/$run.cfg" Scratch.tla) > "$OUT/$run.log" 2>&1 || true
  if grep -q "^Model checking completed. No error has been found" "$OUT/$run.log"; then
    got="pass"
  elif grep -qE "^Error: (Invariant $check is violated|Temporal properties were violated)" "$OUT/$run.log"; then
    got="$(grep -oE '^(State [0-9]+|Back to state [0-9]+): <[A-Za-z]+' "$OUT/$run.log" \
      | sed -E 's/^State [0-9]+: <//; s/^Initial$/Init/; s/^Back to state ([0-9]+): <(.*)/\2->\1/' | tr '\n' ' ' | sed 's/ $//')"
  else
    echo "ERROR  $run: TLC did not finish; see $OUT/$run.log"; status=2; continue
  fi
  states="$(grep -oE '[0-9]+ distinct states found' "$OUT/$run.log" | tail -1)"
  if [[ "$got" == "$expected" ]]; then
    [[ "$got" == pass ]] && echo "ok     $run: $check holds ($states)" \
                         || echo "known  $run: $check broken as recorded, unchanged ($states)"
  else
    status=1
    echo "CHANGE $run: $check"
    echo "       expected: $expected"
    echo "       got:      $got"
  fi
  [[ "$got" != pass ]] && echo "       trace:    $OUT/$run.log"
done <<< "$RUNS"
echo "TLC output: $OUT"
if [[ $CONFIRM == 1 ]]; then
  if [[ $status != 0 ]]; then
    echo "Not confirming: the runs above do not match their baselines." >&2; exit $status
  fi
  NEW="$(cd "$REPO" && sha256sum "${COVERED[@]}")"
  awk -v new="$NEW" '/^```sha256$/{print; print new; skip=1; next} /^```$/{skip=0} !skip' "$HERE/SKILL.md" > "$OUT/SKILL.md"
  cp "$OUT/SKILL.md" "$HERE/SKILL.md"
  echo "Confirmed: SKILL.md now records the hashes of ${COVERED[*]}."
  exit 0
fi
if [[ $status == 0 && $drift == 1 ]]; then
  echo "Matches the baseline, but the model is unconfirmed against the drifted code (exit 3)."; status=3
fi
exit $status
