#!/usr/bin/env bash
# Model-checks PlayStateSync.tla with TLC, one invariant per run, and compares each result with the
# baseline below. Exit 0: every result matches the baseline. Exit 1: something changed. Exit 2:
# tooling problem. Exit 3: matches, but a covered file differs from the content confirmed in
# covered.sha256. Full TLC output goes to $OUT (printed at the end).
#
# run.sh --confirm records the covered files' current content as the content the model describes,
# then runs the checks. Run it only after re-reading the model against that code (SKILL.md).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
HASHES="$HERE/covered.sha256"
WORKFLOW="$REPO/.github/workflows/formal.yml"

TLA_VERSION=v1.7.4
TLA_SHA256=936a262061c914694dfd669a543be24573c45d5aa0ff20a8b96b23d01e050e88
JAR="${FINIMAL_FORMAL_CACHE:-$HOME/.cache/finimal-formal}/$TLA_VERSION/tla2tools.jar"

# One TLC run per line: name | invariant | constants that differ from PlayStateSync.cfg | baseline.
# The baseline is "pass", or the action sequence of TLC's shortest counterexample: a known finding
# (SKILL.md, Known findings), or, for a row named after a commit, the bug its guard prevents, put back.
RUNS='
types               | TypeOK         |                                                               | pass
flush-only          | NoLostProgress | Accounts={"a"} Reports=FALSE                                  | pass
reports-only        | NoLostProgress | Edits=FALSE                                                   | pass
one-account         | NoLostProgress | Accounts={"a"}                                                | Init ReportStamp EditStamp EditRecord ReportRecord ReportCheck ReportSend ReportConfirm
two-accounts        | NoLostProgress |                                                               | Init ReportStamp EditStamp EditRecord ReportRecord ReportCheck ReportSend ReportConfirm
report-in-flight    | NoLostProgress | OutOfOrderRecords=FALSE                                       | Init ReportStamp EditStamp ReportRecord ReportCheck EditRecord ReportSend ReportConfirm FlushSnapshot FlushFetch FlushCheck FlushConfirm
own-account         | OwnAccountOnly |                                                               | pass
own-account-flush   | OwnAccountOnly | Reports=FALSE                                                 | pass
7639821-flush       | NoLostProgress | Accounts={"a"} Edits=FALSE FlushRecheck=FALSE FlushLock=FALSE | Init ReportStamp ReportRecord ReportCheck ReportSend ReportStamp FlushSnapshot ReportRecord ReportCheck FlushFetch ReportSend ReportConfirm FlushCheck FlushSend
7639821-owner       | OwnAccountOnly | UsesOwner=FALSE                                               | Init ReportStamp ReportRecord ReportCheck Switch ReportSend
7639821-owner-flush | OwnAccountOnly | UsesOwner=FALSE Reports=FALSE                                 | Init EditStamp EditRecord FlushSnapshot FlushFetch FlushCheck Switch FlushSend
7639821-report      | NoLostProgress | Accounts={"a"} OutOfOrderRecords=FALSE ReportRecheck=FALSE    | Init ReportStamp EditStamp ReportRecord EditRecord ReportCheck ReportSend ReportConfirm FlushSnapshot FlushFetch FlushCheck FlushConfirm
7cbbb32-item-lock   | NoLostProgress | Accounts={"a"} Edits=FALSE FlushLock=FALSE                    | Init ReportStamp ReportRecord ReportCheck ReportSend ReportStamp FlushSnapshot FlushFetch FlushCheck ReportRecord ReportCheck ReportSend ReportConfirm FlushSend
'

COVERED=($(awk '/^## Coverage/{on=1;next} /^## /{on=0} on && /^\|/' "$HERE/SKILL.md" | grep -o '`[a-z][^`.]*/[^`]*\.kt`' | tr -d '`' | sort -u))
for f in "${COVERED[@]}"; do
  [[ -f "$REPO/$f" ]] || { echo "Covered file $f (SKILL.md, Coverage) does not exist." >&2; exit 2; }
  grep -qF -- "- $f" "$WORKFLOW" 2>/dev/null \
    || { echo "Covered file $f is missing from the path filters in $WORKFLOW, so CI would not check it." >&2; exit 2; }
done

if [[ "${1:-}" == "--confirm" ]]; then
  (cd "$REPO" && sha256sum "${COVERED[@]}") > "$HASHES"
  echo "Confirmed: the model describes the covered files as they are now ($HASHES)."
  echo
fi

# The newest commit whose copy of $1 has the confirmed hash $2, so the agent can diff from it.
confirmed_at() {
  local c
  for c in $(git -C "$REPO" log --format=%h -- "$1"); do
    if [[ "$(git -C "$REPO" show "$c:$1" 2>/dev/null | sha256sum | cut -d' ' -f1)" == "$2" ]]; then echo "$c"; return; fi
  done
}

drift=0
for f in "${COVERED[@]}"; do
  want="$(awk -v f="$f" '$2 == f {print $1}' "$HASHES" 2>/dev/null || true)"
  have="$(sha256sum "$REPO/$f" | cut -d' ' -f1)"
  [[ "$want" == "$have" ]] && continue
  if [[ $drift == 0 ]]; then
    echo "DRIFT: covered code differs from the content the model was confirmed against (covered.sha256):"
    drift=1
  fi
  if [[ -z "$want" ]]; then
    echo "  $f: never confirmed"
  elif base="$(confirmed_at "$f" "$want")" && [[ -n "$base" ]]; then
    echo "  $f: confirmed at $base; read git diff $base -- $f"
  else
    echo "  $f: no commit in this checkout has the confirmed content"
  fi
done
if [[ $drift == 1 ]]; then
  echo "  Re-read the changed lines against the model (SKILL.md, Check a change), then run run.sh --confirm."
  echo
fi

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

mkdir -p "$REPO/build/formal"
OUT="$(mktemp -d "$REPO/build/formal/play-state-sync.XXXXXX")"
status=0
while IFS='|' read -r run inv consts expected; do
  run="$(echo $run)"; inv="$(echo $inv)"; expected="$(echo $expected)"
  [[ -z "$run" ]] && continue
  edits=(-e "s/^INVARIANTS.*/INVARIANTS $inv/")
  for kv in $consts; do edits+=(-e "s/^\( *${kv%%=*} = \).*/\1${kv#*=}/"); done
  sed "${edits[@]}" "$HERE/PlayStateSync.cfg" > "$OUT/$run.cfg"
  (cd "$HERE" && "$JAVA" -XX:+UseParallelGC -Djava.io.tmpdir="$OUT" -cp "$JAR" tlc2.TLC -workers 1 -deadlock \
      -metadir "$OUT/meta-$run" -config "$OUT/$run.cfg" PlayStateSync.tla) > "$OUT/$run.log" 2>&1 || true
  if grep -q "^Model checking completed. No error has been found" "$OUT/$run.log"; then
    got="pass"
  elif grep -q "^Error: Invariant $inv is violated" "$OUT/$run.log"; then
    got="$(grep -oE '^State [0-9]+: <[A-Za-z]+' "$OUT/$run.log" | sed -E 's/.*<//; s/^Initial$/Init/' | tr '\n' ' ' | sed 's/ $//')"
  else
    echo "ERROR  $run: TLC did not finish; see $OUT/$run.log"; status=2; continue
  fi
  states="$(grep -oE '[0-9]+ distinct states found' "$OUT/$run.log" | tail -1)"
  if [[ "$got" == "$expected" && "$got" == pass ]]; then
    echo "ok     $run: $inv holds ($states)"
  elif [[ "$got" == "$expected" && "$run" =~ ^[0-9a-f]{7}- ]]; then
    echo "caught $run: $inv broken with its guard removed, as expected ($states)"
  elif [[ "$got" == "$expected" ]]; then
    echo "known  $run: $inv broken by the known finding, unchanged ($states)"
  else
    status=1
    echo "CHANGE $run: $inv"
    echo "       expected: $expected"
    echo "       got:      $got"
  fi
  [[ "$got" != pass ]] && echo "       trace:    $OUT/$run.log"
done <<< "$RUNS"
echo "TLC output: $OUT"
if [[ $status == 0 && $drift == 1 ]]; then
  echo "Matches the baseline, but the model is unconfirmed against the drifted code (exit 3)."; status=3
fi
exit $status
