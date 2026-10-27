#!/usr/bin/env bash
# Model-checks PlayStateSync.tla with TLC, one invariant at a time, and compares each result
# with the baseline below. Exit 0: every result matches the baseline. Exit 1: something changed.
# Exit 2: tooling problem. Exit 3: matches, but covered code changed since the sync commit in
# SKILL.md. Full TLC output goes to $OUT (printed at the end).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"

TLA_VERSION=v1.8.0
TLA_SHA256=78f7e2c6bf9a8b65eda665a23e94431255587e0b43a834a4e2af1723562d9b4a
JAR="${FINIMAL_FORMAL_CACHE:-$HOME/.cache/finimal-formal}/$TLA_VERSION/tla2tools.jar"

# One TLC run per line: name | invariant | Accounts | Reports | baseline. The baseline is "pass",
# or the action sequence of TLC's shortest counterexample (a known finding, SKILL.md "Known findings").
RUNS='
types             | TypeOK         | {"a", "b"} | TRUE  | pass
flush-only        | NoLostProgress | {"a"}      | FALSE | pass
one-account       | NoLostProgress | {"a"}      | TRUE  | pass
two-accounts      | NoLostProgress | {"a", "b"} | TRUE  | pass
own-account       | OwnAccountOnly | {"a", "b"} | TRUE  | pass
own-account-flush | OwnAccountOnly | {"a", "b"} | FALSE | pass
'

# --- tools ------------------------------------------------------------------------------------
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

# --- drift: covered code changed since the sync commit? ----------------------------------------
SYNC="$(sed -n 's/^Sync commit: `\([0-9a-f]*\)`.*/\1/p' "$HERE/SKILL.md")"
COVERED=($(awk '/^## Coverage/{on=1;next} /^## /{on=0} on' "$HERE/SKILL.md" | grep -o '`[a-z][^`.]*/[^`]*\.kt`' | tr -d '`' | sort -u))
drift=0
if ! git -C "$REPO" diff --quiet "$SYNC" -- "${COVERED[@]}"; then
  drift=1
  echo "DRIFT: covered code changed since sync commit $SYNC (working tree included):"
  git -C "$REPO" diff --stat "$SYNC" -- "${COVERED[@]}" | sed 's/^/  /'
  echo "  Re-read the changed lines against the model before trusting the result (SKILL.md, step 2)."
  echo
fi

# --- check ------------------------------------------------------------------------------------
OUT="$(mktemp -d "${TMPDIR:-/tmp}/play-state-sync.XXXXXX")"
status=0
while IFS='|' read -r run inv accounts reports expected; do
  run="$(echo $run)"; inv="$(echo $inv)"; accounts="$(echo $accounts)"; reports="$(echo $reports)"; expected="$(echo $expected)"
  [[ -z "$run" ]] && continue
  sed -e "s/^INVARIANTS.*/INVARIANTS $inv/" -e "s/^\( *Accounts = \).*/\1$accounts/" \
      -e "s/^\( *Reports = \).*/\1$reports/" "$HERE/PlayStateSync.cfg" > "$OUT/$run.cfg"
  (cd "$HERE" && "$JAVA" -XX:+UseParallelGC -cp "$JAR" tlc2.TLC -workers 1 -deadlock -noGenerateSpecTE \
      -metadir "$OUT/meta-$run" -config "$OUT/$run.cfg" PlayStateSync.tla) > "$OUT/$run.log" 2>&1 || true
  if grep -q "^Model checking completed. No error has been found" "$OUT/$run.log"; then
    got="pass"
  elif grep -q "^Error: Invariant $inv is violated" "$OUT/$run.log"; then
    got="$(grep -oE '^State [0-9]+: <[A-Za-z]+' "$OUT/$run.log" | sed -E 's/.*<//; s/^Initial$/Init/' | tr '\n' ' ' | sed 's/ $//')"
  else
    echo "ERROR  $run: TLC did not finish; see $OUT/$run.log"; status=2; continue
  fi
  states="$(grep -oE '[0-9]+ distinct states found' "$OUT/$run.log" | tail -1)"
  if [[ "$got" == "$expected" ]]; then
    [[ "$got" == pass ]] && echo "ok     $run: $inv holds ($states)" \
                         || echo "known  $run: $inv broken by the known finding, unchanged ($states)"
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
