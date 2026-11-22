---
name: verify-play-state-sync
description: "Model-checks finimal's play-state sync (PendingStore, Server.flush and runFlush, reportProgress/reportStop, itemLock, record/recordAll, confirm/confirmAll, switchTo) with TLA+ for lost or cross-account progress under every interleaving. Use when a change touches PendingStore.kt, shouldPush in PlayState.kt, the play-state part of Server.kt, App.pending or App.disk in Platform.kt, or any new caller of Server.flush, when CI's formal workflow fails, or when asked whether a sync ordering can lose progress."
---

# Verify play-state sync

finimal keeps play state locally and pushes it to the server (docs/design.md, Sync). This skill checks one promise of that design under every order of steps, with the TLC model checker:

- **Property.** Once an account has no unsynced entry for an item, the server holds a play state for that account at least as new as the newest one the account recorded, and nothing another account recorded.
- **Harm when broken.** The server, and so every device and finimal itself after a restart, resumes an episode at an older position or shows it unplayed again, and nothing pending will ever correct it; or one person's progress lands on another person's account.

The model is `PlayStateSync.tla` in this folder. It is a hand-written mirror of the Kotlin code, not generated from it, so it is only as true as its last confirmation against the code (see Coverage). Tests cannot reach these bugs: each needs a report, a local change, a flush, or an account switch to interleave at one exact point.

## Run it

From the repo root:

```
.claude/skills/verify-play-state-sync/run.sh
```

About 15 s. It needs Java 11 or newer: `java` on PATH, `JAVA=/path/to/bin/java`, or `~/toolchain/jdk-*/bin/java`. The first run downloads tla2tools.jar v1.7.4, the latest stable release (pre-release jars such as v1.8.0 get re-uploaded under the same tag, which breaks the pin), to `~/.cache/finimal-formal/` (override with `FINIMAL_FORMAL_CACHE`) and checks its pinned SHA-256. Each run writes TLC's full output to a fresh folder under `build/formal/` in the repo and prints its path.

CI runs the same script (`.github/workflows/formal.yml`) on every pull request and push to main that touches this folder or a covered file, and fails on any exit code but 0.

Each line of output is one TLC run (the table at the top of `run.sh`):

| Run | Checks | Baseline |
| --- | --- | --- |
| `types` | TypeOK, two accounts, player reports and local edits | holds |
| `flush-only` | NoLostProgress, one account, no player reports | holds |
| `reports-only` | NoLostProgress, two accounts, no startOver or setPlayed | holds |
| `one-account` | NoLostProgress, one account | known finding 2 |
| `two-accounts` | NoLostProgress, two accounts | known finding 2 |
| `report-in-flight` | NoLostProgress, two accounts, changes recorded in the order they read `now()` | known finding 1 |
| `own-account` | OwnAccountOnly, two accounts | holds |
| `own-account-flush` | OwnAccountOnly, two accounts, no player reports | holds |
| `7639821-flush` | `reports-only`, one account, the flush pushing outside the item lock and without its still-pending check | broken |
| `7639821-owner` | `own-account` with every step using the account logged in when it runs, not the Owner | broken by a report |
| `7639821-owner-flush` | the same, no player reports | broken by a flush |
| `7639821-report` | `report-in-flight`, one account, reports sent without their still-pending check | broken: the edit is recorded before the report's check, which the check would catch |
| `7cbbb32-item-lock` | `reports-only`, one account, the flush pushing under its Semaphore permit only, not `itemLock` | broken |

The rows named after a commit are replays. Each sets one or two constants to FALSE to remove a guard that commit added, which puts back the bug it fixed or prevents (the constants are listed under Coverage), and its baseline is TLC's shortest counterexample. They prove that the model can still see each past bug. `reports-only` holds, so the replays run on it fail only through their removed guard. `report-in-flight` fails through finding 1, so `7639821-report` is pinned to a path that finding 1 cannot take.

- `ok`: the invariant holds in every reachable state within the bounds.
- `known`: TLC's shortest counterexample is the known finding's path, step for step.
- `caught`: a replay row breaks its invariant along its recorded path.
- `CHANGE`: the result differs from the baseline: a run that held now fails, a replay row passes, or a shortest path changed (a shorter new bug, a finding fixed or moved, or a replay the model no longer sees). Exit 1.
- `DRIFT` (printed first): a covered file's content differs from the content recorded in `covered.sha256`, in the working tree. The model may no longer describe the code, so a clean result means nothing until you do step 2 below. A run that matches the baseline exits 3 while drift remains, and CI fails on it.
- Exit 2 is a tooling problem: no Java, a bad jar, TLC did not finish, or a covered file missing from the workflow's path filters.

Read every trace with the pstack **statechart** skill (`/pstack:statechart`). It tells each invariant violation as its shortest path in plain words, and with a fixed model it shows which violations a change fixes. When all runs hold, it draws how the sync moves between states. TLC's raw trace lists each step's action name; the Coverage table maps the names to code.

## Check a change

1. Run `run.sh` on the change.
2. If it prints `DRIFT`, read the diff it names for each file (`git diff <commit> -- <file>`). For each changed line, find the model action in Coverage. If the change alters what that step does, edit the action to match. Keep one action per indivisible step of the code: a step ends where the code suspends or another coroutine or thread can run (a network call, `withContext`, a lock or semaphore wait, a lock released). Reading `now()` before a `withContext` hop and the moment the server applies a request are steps of their own. Give each condition a `\* File.kt:line` comment. If the change adds a new path that writes PendingStore, posts play state, or reads the current account, add an action for it. If the change adds a guard against a past bug, add a constant that removes it and a replay row.
3. Run again and read each `CHANGE`:
   - Walk the trace step by step against the code. If some step cannot happen in the code (a lock the model misses, an order the code forces), it is a modeling error: fix the model and cite the line that forbids the step. Never weaken `NoLostProgress` or `OwnAccountOnly`, never shrink the bounds, and never edit a replay row's constants to make a run pass.
   - If every step can happen, it is a real bug. Report it as a story in the code's words (which function, which account, which state reaches the server) and change nothing to hide it. To keep CI green while it stays visible, set the row's baseline in `run.sh` to the new path and add the story under Known findings.
   - A change that intends to fix a known finding should turn its rows to `ok`. Confirm they now hold, set their baselines in `run.sh` to `pass`, and move the finding out of Known findings.
   - A replay row whose path changed: read the new trace. If it still shows the replayed bug, set the new path as its baseline. If the row now passes, the model lost sight of that bug: fix the model.
4. When the model describes the code as it is now, update the line numbers in Coverage and in the model's comments, then run `run.sh --confirm`. It records the covered files' current content in `covered.sha256` and runs the checks. Commit `covered.sha256` with the change. Do not confirm only because the runs match the baseline: a stale model matches too.

## Coverage

The content the model was last confirmed against is in `covered.sha256` (written by `run.sh --confirm`). Line numbers below are at that content.

| Model | Code |
| --- | --- |
| `pending`, `Confirm` | `core/src/main/kotlin/finimal/core/PendingStore.kt`: `put`, `putAll`, `get`, `all`, `confirm` and `confirmAll` (lines 59, 70: remove only if still equal) |
| `ShouldPush` | `core/src/main/kotlin/finimal/core/PlayState.kt`: `shouldPush` (lines 52-56) |
| `Record`, `EditStamp`, `EditRecord` | `shared/src/commonMain/kotlin/com/zakius/finimal/Server.kt`: `recordOnDisk` (437), `record` (453), `recordAll` (463), `startOver` (486), `setPlayed` (503), `owner` (107), `now` (132) |
| `ReportStamp`, `ReportRecord`, `ReportCheck`, `ReportSend`, `ReportConfirm` | `Server.kt`: `reportProgress` (368-386), `reportStop` (392-420), `confirm` (494), `itemLock` (135-136) |
| `FlushSnapshot`, `FlushFetch`, `FlushCheck`, `FlushSend`, `FlushConfirm` | `Server.kt`: `flush` (527-549: the `hasPendingWork` fast path and `recordVersion` coalescing), `runFlush` (551-629), `userData` (280), the `flushing` Mutex (632) |
| `Switch`, `cur` | `Server.kt`: `switchTo` (189-197), `login` and `logout` (under `synchronized(App)`); `shared/src/commonMain/kotlin/com/zakius/finimal/Platform.kt`: `App.pending` (127-146), `pendingLoadedEmpty` (149), `App.disk` (163) |
| `OutOfOrderRecords` | TRUE is the code: `reportProgress` (370), `startOver` (490) and `setPlayed` (505) read `now()` before their `App.disk` hop, so a change can be recorded after one that read `now()` later. FALSE keeps that order, to show finding 1 apart from finding 2. |
| `UsesOwner` | `Owner` (`Server.kt` 102) in every report, flush and confirm (7639821). FALSE: `api`, `userId` and `App.pending` as they are at each step. |
| `ReportRecheck` | `owner.pending.get(id) == state` in `reportProgress` (373) and `reportStop` (408) (7639821) |
| `FlushRecheck` | `owner.pending.get(id) == local` in `runFlush` (585) (7639821) |
| `FlushLock` | the flush's push under `itemLock(id)` (583), `reports` at 7639821, per item since 7cbbb32 |

The server dates a progress or stop report on arrival (the comment at `Server.kt` 366), and the model gives each stored report a new date. Checked against Jellyfin 12.4.0 on the local test server: a progress report and a stop report each set LastPlayedDate to the time they arrived, replacing a date set before. A flush's push keeps the local LastPlayedDate.

Callers of `flush` that make it run at any time (not modeled one by one; the model lets a flush start whenever an entry is pending): list loads in `Screens.kt` (`loadFresh`), "Sync now", `setPlayed`, `Server.item(flushFirst)`, the players on open (`app/.../Player.kt`, `desktop/.../Player.kt`), Android `Housekeeping` in `app/.../Media.kt` and `FastStart.kt`, and the desktop's `scheduleHousekeeping`.

## Known findings

Both were already in the code at 7639821. The model at 7639821 missed them, because it recorded a change in the same step as its `now()`, and stored a report on the server in the same step as the report's still-pending check, dated at the record time.

1. **A local change made while an older report is in flight can be dropped** (row `report-in-flight`). The window is the report's network round trip.
   1. `reportProgress` (or `reportStop`) records #1, takes `itemLock`, finds #1 still pending, and sends the report.
   2. While the request is in flight, Mark played or Start over records #2 on `App.disk`, which does not take `itemLock`.
   3. The server stores #1's position, dated on arrival, which is later than #2.
   4. The report confirms #1 (a no-op, #2 is pending) and releases `itemLock`.
   5. The next flush (`setPlayed` starts one at once) reads #2 and fetches the server's state.
   6. Under `itemLock`, `shouldPush(#2, server)` is false because the server's date is newer, so nothing is pushed and #2 counts as confirmed.
   7. `confirmAll` removes #2. The server keeps #1: the item shows unplayed or at the older position everywhere, and nothing pending corrects it.

   This is the 7639821 report finding narrowed, not closed: the still-pending check runs before the request is sent, not when the server stores it.
2. **Two changes can be recorded out of date order** (rows `one-account`, `two-accounts`). The window is the few instructions between `now()` and the `App.disk` hop.
   1. `reportProgress` reads `now()` as #1 on Dispatchers.IO.
   2. Mark played or Start over reads `now()` as #2, and its `App.disk` task runs first: #2 is pending.
   3. The report's `App.disk` task runs: #1 replaces #2 in the pending file.
   4. The report finds #1 still pending, sends it, the server stores it dated on arrival, and the report confirms #1. #2 was never sent and nothing is pending.

Fixed in the code this model describes, and replayed by the rows named after 7639821: a flush that posted an older state over a newer report, reports and flushes that crossed an account switch, and a report that waited for the lock while a newer local change was recorded and flushed, then posted its older position.

## What the model leaves out

- Bounds: one item, at most two accounts, at most 4 `now()` reads and report arrivals per run (the results are the same at 6), one report, one Start over or Mark played, and one flush in flight at a time. Two reports for one item in flight together (a progress report and the stop) are not modeled. `reportStop` reads `now()` inside its `App.disk` task; the model's report covers it as the case with no step between the read and the record.
- Several items are not modeled. `runFlush` pushes up to four items at once (`Semaphore(4)`), each under its own `itemLock`; an unreachable server or a 401 stops the rest, which only delays pushes.
- The `hasPendingWork` fast path and the `recordVersion` coalescing only decide whether a `flush` call starts a run or joins one. The model checks no promise about what a caller sees when `flush` returns, such as "Sync now" saying Synced.
- HTTP 404 (drop) and 401 (expire, log out) during a flush, `logout`, and `login` are not modeled. A switch is one step: `owner()` and the prefs writes in `switchTo`, `login` and `logout` share `synchronized(App)`.
- A skipped stop report leaves the server's playback session open until the server times it out.
- The played flag (`recorded`, `knownPlayed`, design rule 11), `ResumeRules`, `ResumeGuard` and the server clock offset are not modeled. A play state is its LastPlayedDate; the position and played flag ride along with it. All devices stamp dates on one exact server clock, so the 2 s noise in `setClock` is left out.
- Another device's write that lands between a flush's fetch and its post, or under a report in flight, is overwritten, because the Jellyfin API has no conditional update. The invariants do not check other devices' progress.
- Download records (`rememberState`) and the list snapshots are not modeled.
