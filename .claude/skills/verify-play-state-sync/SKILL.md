---
name: verify-play-state-sync
description: "Model-checks finimal's play-state sync (PendingStore, Server.flush, reportProgress/reportStop, confirm, switchTo) with TLA+ for lost or cross-account progress under every interleaving. Use when a change touches PendingStore.kt, shouldPush in PlayState.kt, the play-state part of Server.kt (record, confirm, flush, reports, switchTo, logout), App.pending in Platform.kt, or any new caller of Server.flush, or when asked whether a sync ordering can lose progress."
---

# Verify play-state sync

finimal keeps play state locally and pushes it to the server (docs/design.md, Sync). This skill checks one promise of that design under every order of steps, with the TLC model checker:

- **Property.** Once an account has no unsynced entry for an item, the server holds a play state for that account at least as new as the newest one the account recorded, and nothing another account recorded.
- **Harm when broken.** The server, and so every device and finimal itself after a restart, resumes an episode at an older position or shows it unplayed again, and nothing pending will ever correct it; or one person's progress lands on another person's account.

The model is `PlayStateSync.tla` in this folder. It is a hand-written mirror of the Kotlin code, not generated from it, so the model is only as true as its last sync with the code (see Coverage). Tests cannot reach these bugs: each needs a report and a flush, or an account switch, to interleave at one exact point.

## Run it

From the repo root:

```
.claude/skills/verify-play-state-sync/run.sh
```

About 5 s. It needs Java 11 or newer: `java` on PATH, `JAVA=/path/to/bin/java`, or `~/toolchain/jdk-*/bin/java`. The first run downloads tla2tools.jar v1.8.0 to `~/.cache/finimal-formal/` (override with `FINIMAL_FORMAL_CACHE`) and checks its pinned SHA-256. GitHub's v1.8.0 is a rolling pre-release, so the pin breaks when it is rebuilt; then download it again and update `TLA_SHA256` in `run.sh`. Each run writes TLC's full output to a fresh folder under `/tmp` and prints its path.

Each line of output is one TLC run (the table at the top of `run.sh`):

| Run | Checks | Baseline at the sync commit |
| --- | --- | --- |
| `types` | TypeOK, two accounts, player reporting | holds |
| `flush-only` | NoLostProgress, one account, no player reports | holds |
| `one-account` | NoLostProgress, one account, player reporting | holds |
| `two-accounts` | NoLostProgress, two accounts | holds |
| `own-account` | OwnAccountOnly, two accounts | holds |
| `own-account-flush` | OwnAccountOnly, two accounts, no player reports | holds |

- `ok`: the invariant holds in every reachable state within the bounds.
- `known`: TLC's shortest counterexample is the known finding's path, step for step.
- `CHANGE`: the result differs from the baseline. A run that held now fails, or a known finding's shortest path changed (a shorter new bug, or the finding fixed or moved). The exit code is 1.
- `DRIFT` (printed first): a covered file differs from the sync commit, in commits or in the working tree. The model may no longer describe the code, so a clean result means nothing until you do step 2 below. A run that matches the baseline exits 3 while drift remains. Exit 2 is a tooling problem (no Java, a bad jar, TLC did not finish).

Read every trace with the pstack **statechart** skill (`/pstack:statechart`). It tells each invariant violation as its shortest path in plain words, and with a fixed model it shows which violations a change fixes. When all runs hold, it draws how the sync moves between states. TLC's raw trace lists each step's action name; the Coverage table maps the names to code.

## Check a change

1. Run `run.sh` on the change.
2. If it prints `DRIFT`, read the diff it names (`git diff <sync commit> -- <file>`). For each changed line, find the model action in Coverage. If the change alters what that step does, edit the action to match. Keep one action per indivisible step of the code: a step ends where the code suspends or another coroutine or thread can run (a network call, `withContext`, a lock released). Give each condition a `\* File.kt:line` comment. If the change adds a new path that writes PendingStore, posts play state, or reads the current account, add an action for it.
3. Run again and read each `CHANGE`:
   - Walk the trace step by step against the code. If some step cannot happen in the code (a lock the model misses, an order the code forces), it is a modeling error: fix the model and cite the line that forbids the step. Never weaken `NoLostProgress` or `OwnAccountOnly`, and never shrink the bounds to make a run pass.
   - If every step can happen, it is a real bug. Report it as a story in the code's words (which function, which account, which state reaches the server) and change nothing to hide it.
   - A change that intends to fix a known finding should turn its run to `ok`. Confirm the run now holds, set the baseline in `run.sh` to `pass`, and move the finding out of Known findings.
4. When the model describes the code at the new commit, set Sync commit below to that commit and update the line numbers in Coverage and in the model's comments. Do not move the sync commit only because the runs match the baseline: a stale model matches too.

## Coverage

Sync commit: `6836a29bc99fcb7f3bc86d1084f0a31398ebfc0d` (the model was last confirmed to describe these files at this commit).

| Model | Code |
| --- | --- |
| `pending`, `Confirm` | `core/src/main/kotlin/finimal/core/PendingStore.kt`: `put`, `putAll`, `all`, `confirm`, `confirmAll` (lines 58, 67: remove only if still equal) |
| `ShouldPush` | `core/src/main/kotlin/finimal/core/PlayState.kt`: `shouldPush` (lines 52-56) |
| `Record`, `LocalEdit` | `shared/src/commonMain/kotlin/com/zakius/finimal/Server.kt`: `recordOnDisk` (452), `record` (468), `recordAll` (478), `startOver` (505), `setPlayed` (522), `owner` (108) |
| `ReportRecord`, `ReportSkip`, `ReportPost`, `ReportConfirm` | `Server.kt`: `reportProgress` (371-389), `reportStop` (399-433), `confirm` (513), `itemLock` (137) |
| `IdleStop` | `Server.kt`: `reportStop` with `idle` (405-409, failed stop at 423-428); `PlayState.kt`: `idleStop` (63-66) |
| `FlushSnapshot`, `FlushFetch`, `FlushPush`, `FlushConfirm` | `Server.kt`: `flush` (546), `runFlush` (570-648), `userData` (283), the `flushing` Mutex (651) |
| `Switch`, `cur` | `Server.kt`: `switchTo` (190-197), `login` and `logout` (under `synchronized(App)`); `shared/src/commonMain/kotlin/com/zakius/finimal/Platform.kt`: `App.pending` (127-146), `App.disk` (163) |

Callers of `flush` that make it run at any time (not modeled one by one; the model lets a flush start whenever an entry is pending): list loads in `Screens.kt` (`loadFresh`), "Sync now", `setPlayed`, `Server.item(flushFirst)`, the players on open (`app/.../Player.kt`, `desktop/.../Player.kt`), Android `Housekeeping` in `app/.../Media.kt`, and the desktop's `scheduleHousekeeping`. `flush` lets a caller join a running flush when nothing was recorded since it started; that only skips runs, so it adds no step.

The players decide `idle` (`app/.../Player.kt` and `desktop/.../Player.kt`, `reportedTicks`): paused, at the position their last progress report sent.

## Known findings

None. Three findings at `96575ed` are fixed in the code this model describes: a flush that posted an older state over a newer report (the flush now posts under `reports` and only while its entry is still pending), and reports and flushes that crossed an account switch (each now uses the `Owner` it recorded for: that account's client, user and pending file). Fixing the first exposed a fourth path, a report that waited for `reports` while a newer local change was recorded and flushed, then posted its older position; a report whose entry was replaced is now not sent.

## What the model leaves out

- Bounds: one item, at most two accounts, at most 4 new play states per run (the results are the same at 6), one report and one flush in flight at a time.
- Several items are not modeled. `flush` handles each item on its own, except that an unreachable server stops it, which only delays pushes.
- HTTP 404 (drop) and 401 (expire, log out) during a flush, `logout`, and `login` are not modeled. A switch is one step: `owner()` and the prefs writes in `switchTo`, `login` and `logout` share `synchronized(App)`.
- A skipped stop report leaves the server's playback session open until the server times it out.
- What `IdleStop` fixes is outside the invariants: a stop dated now for a player that sat paused while another device played on. In the model that stop is an ordinary `ReportRecord` with a newer t, which is indistinguishable from real progress. The model checks only that an idle stop, which keeps its entry's t and confirms nothing, loses no progress.
- The played flag (`recorded`, `knownPlayed`, design rule 11), `ResumeRules`, `ResumeGuard` and the server clock offset are not modeled. A play state is its LastPlayedDate; the position and played flag ride along with it.
- Assumed: a progress or stop report leaves the server with the reported state, dated no earlier than the local record. All devices stamp dates on one server clock.
- Another device's write that lands between a flush's fetch and its post is overwritten, because the Jellyfin API has no conditional update. The invariants do not check other devices' progress.
- Download records (`rememberState`) and the list snapshots are not modeled.
