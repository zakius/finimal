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
| `one-account` | NoLostProgress, one account | holds |
| `two-accounts` | NoLostProgress, two accounts | holds |
| `own-account` | OwnAccountOnly, two accounts | holds |
| `own-account-flush` | OwnAccountOnly, two accounts, no player reports | holds |
| `7639821-flush` | `reports-only`, one account, the flush pushing outside the item lock and without its still-pending check | broken |
| `7639821-owner` | `own-account` with every step using the account logged in when it runs, not the Owner | broken by a report |
| `7639821-owner-flush` | the same, no player reports | broken by a flush |
| `7639821-report` | `one-account`, reports sent without their still-pending check, and no re-dating | broken: the edit is recorded before the report's check, which the check would catch |
| `7cbbb32-item-lock` | `reports-only`, one account, the flush pushing under its Semaphore permit only, not `itemLock` | broken |
| `fix-stamp-on-disk` | `one-account`, `now()` read before the `App.disk` hop | broken: two changes recorded out of date order |
| `fix-redate` | `one-account`, no re-dating after a report | broken: a change recorded while a report is out is dropped |

The rows named after a commit, or starting with `fix-`, are replays. Each sets one or two constants to FALSE to remove a guard a fix added, which puts back the bug it fixed or prevents (the constants are listed under Coverage), and its baseline is TLC's shortest counterexample. They prove that the model can still see each past bug. `7639821-report` also turns off re-dating, which did not exist at 7639821 and would hide that bug.

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
| `Record`, `EditStamp`, `EditRecord` | `shared/src/commonMain/kotlin/com/zakius/finimal/Server.kt`: `recordOnDisk` (444), `record` (461), `recordAll` (474), `startOver` (497), `setPlayed` (513), `owner` (107), `now` (132) |
| `ReportStamp`, `ReportRecord`, `ReportCheck`, `ReportSend`, `ReportConfirm`, `ReportRedate` | `Server.kt`: `reportProgress` (364-377), `report` (379-397), `reportStop` (403-427), `confirm` (504), `itemLock` (135-136); `PendingStore.kt`: `replace` (66-71) |
| `FlushSnapshot`, `FlushFetch`, `FlushCheck`, `FlushSend`, `FlushConfirm` | `Server.kt`: `flush` (539-561: the `hasPendingWork` fast path and `recordVersion` coalescing), `runFlush` (563-641), `userData` (280), the `flushing` Mutex (644) |
| `Switch`, `cur` | `Server.kt`: `switchTo` (189-197), `login` and `logout` (under `synchronized(App)`); `shared/src/commonMain/kotlin/com/zakius/finimal/Platform.kt`: `App.pending` (127-146), `pendingLoadedEmpty` (149), `App.disk` (163) |
| `StampOnDisk` | `record` and `recordAll` (464, 477) build the state inside their `App.disk` task, and `reportStop` (408-411) reads `now()` in its own, so a change recorded later always has the later date. FALSE: `reportProgress`, `startOver` and `setPlayed` read `now()` before the hop. |
| `RedateNewer` | `report` (388-394): once the request returns, still under `itemLock`, a change recorded since the check is dated `now() + CLOCK_NOISE_MS` on `App.disk`. FALSE: it keeps its date, older than the report's arrival date. |
| `UsesOwner` | `Owner` (`Server.kt` 102) in every report, flush and confirm (7639821). FALSE: `api`, `userId` and `App.pending` as they are at each step. |
| `ReportRecheck` | `owner.pending.get(id) != state` in `report` (386) (7639821) |
| `FlushRecheck` | `owner.pending.get(id) == local` in `runFlush` (597) (7639821) |
| `FlushLock` | the flush's push under `itemLock(id)` (595), `reports` at 7639821, per item since 7cbbb32 |

The server dates a progress or stop report on arrival (the comment at `Server.kt` 381), and the model gives each stored report a new date. Checked against Jellyfin 12.4.0 on the local test server: a progress report and a stop report each set LastPlayedDate to the time they arrived, replacing a date set before. A flush's push keeps the local LastPlayedDate.

Callers of `flush` that make it run at any time (not modeled one by one; the model lets a flush start whenever an entry is pending): list loads in `Screens.kt` (`loadFresh`), "Sync now", `setPlayed`, `Server.item(flushFirst)`, the players on open (`app/.../Player.kt`, `desktop/.../Player.kt`), Android `Housekeeping` in `app/.../Media.kt` and `FastStart.kt`, and the desktop's `scheduleHousekeeping`.

## Known findings

None. Two findings from the 7cbbb32 model are fixed, and the `fix-` rows replay them:

1. **A local change made while an older report was in flight could be dropped** (`fix-redate`). The report sent #1. While its request was out, Mark played recorded #2. The server dated #1 on arrival, later than #2, so the next flush found `shouldPush(#2, server)` false, counted #2 as confirmed, and removed it. Now `report` dates #2 again once the request returns, so the flush pushes it.
2. **Two changes could be recorded out of date order** (`fix-stamp-on-disk`). `reportProgress` read `now()` as #1 on Dispatchers.IO, and Mark played read a later `now()` as #2 and recorded it first. The report's `App.disk` task then replaced #2 with #1. Now every change reads `now()` and its played flag inside the `App.disk` task that records it.

Fixed earlier, and replayed by the rows named after 7639821: a flush that posted an older state over a newer report, reports and flushes that crossed an account switch, and a report that waited for the lock while a newer local change was recorded and flushed, then posted its older position.

## What the model leaves out

- Bounds: one item, at most two accounts, at most 4 `now()` reads and report arrivals per run (the results are the same at 6), one report, one Start over or Mark played, and one flush in flight at a time. Two reports for one item in flight together (a progress report and the stop) are not modeled. `reportStop` reads `now()` inside its `App.disk` task; the model's report covers it as the case with no step between the read and the record.
- Several items are not modeled. `runFlush` pushes up to four items at once (`Semaphore(4)`), each under its own `itemLock`; an unreachable server or a 401 stops the rest, which only delays pushes.
- The `hasPendingWork` fast path and the `recordVersion` coalescing only decide whether a `flush` call starts a run or joins one. The model checks no promise about what a caller sees when `flush` returns, such as "Sync now" saying Synced.
- HTTP 404 (drop) and 401 (expire, log out) during a flush, `logout`, and `login` are not modeled. A switch is one step: `owner()` and the prefs writes in `switchTo`, `login` and `logout` share `synchronized(App)`.
- A skipped stop report leaves the server's playback session open until the server times it out.
- The played flag (`recorded`, `knownPlayed`, design rule 11), `ResumeRules`, `ResumeGuard` and the server clock offset are not modeled. A play state is its LastPlayedDate; the position and played flag ride along with it. All devices stamp dates on one exact server clock, so the 2 s noise in `setClock` is left out. In the code `now()` can lag the server's clock by that noise and the Date header's whole seconds, so a change made in the seconds after a report arrives can still date older than it; re-dating adds `CLOCK_NOISE_MS` for the in-flight case only.
- Another device's write that lands between a flush's fetch and its post, or under a report in flight, is overwritten, because the Jellyfin API has no conditional update. The invariants do not check other devices' progress.
- Download records (`rememberState`) and the list snapshots are not modeled.
