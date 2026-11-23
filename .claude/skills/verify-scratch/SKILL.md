---
name: verify-scratch
description: "Model-checks finimal's on-disk scratch (Scratch.kt, ScratchServer.kt) with TLA+: entries for one key shut down and created again, their filler threads, ScratchServer reads, and Scratch.delete, under every interleaving. Use when a change touches Scratch.kt or ScratchServer.kt, adds or changes a caller of Scratch.delete or Scratch.entry (eviction, clearBuffer and download deletion in Media.kt or DesktopMedia.kt), or when asked whether a scratch race can delete or damage bytes in use, or make a read or a fetch loop forever."
---

# Verify the scratch

The scratch keeps server files on disk in chunks that streaming, buffering and downloads share (the class comment in `Scratch.kt`). Entries for one key come and go. `delete` shuts an entry down and removes its folder, and the next `entry(key)` creates a new entry on the same folder while the old entry's filler thread can still run. This skill checks these promises under every order of steps, with the TLC model checker:

- **Older entries keep out.** Once a newer entry exists for a key, no step done for an older entry truncates, replaces, renames or deletes a file that the newer entry created or took over. An older entry's step is its filler's, or a `delete` that read the map before the newer entry existed. Harm when broken: a part or chunk that the player or a download relies on is emptied, deleted or moved half-written, so a read fails or the cache keeps a corrupt chunk.
- **Delete spares open bytes.** `delete(key)` removes no chunk or part while a reader, buffer or download has the key open (`Scratch.kt:52`). Harm: the reader finds a chunk marked present that is gone.
- **Everything ends.** Every read call returns data, end of file or an exception, and `fill()` never sends the same request again because the last answer stored nothing. Harm: a thread spins at full CPU forever, or the app requests the same range from the server in a loop.

The model is `Scratch.tla` in this folder. It is a hand-written mirror of the Kotlin code, so it is only as true as its last confirmation against the code (see Coverage). Tests cannot reach these cases: each needs one thread to pause at an exact statement while other threads delete the key or open it again.

## Run it

From the repo root:

```
.claude/skills/verify-scratch/run.sh
```

About 40 s. It needs Java 11 or newer: `java` on PATH, `JAVA=/path/to/bin/java`, or `~/toolchain/jdk-*/bin/java`. The first run downloads tla2tools.jar v1.7.4, the latest stable release, to `~/.cache/finimal-formal/` (override with `FINIMAL_FORMAL_CACHE`) and checks its pinned SHA-256. Each run writes TLC's full output to a new folder under `build/formal/` in the repo and prints its path. TLC runs with one worker, a fixed fingerprint (`-fp 0`), and liveness checked once at the end (`-lncheck final`), so every run finds the same counterexample.

Each line of output is one TLC run, from the table at the top of `run.sh`:

- `ok`: the check holds in every reachable state within the bounds.
- `known`: TLC's counterexample is the recorded one, step for step. It is a replay of a race that a past commit fixed.
- `CHANGE`: the result differs from the baseline: a check that held now fails, a recorded counterexample changed, or a replay passes. The exit code is 1.
- `DRIFT` (printed first): a covered file no longer matches its hash in Coverage, in a commit or in the working tree. It names the commit to diff against. A clean result means nothing until you do step 2 below. A run that matches the baseline exits 3 while drift remains. Exit 2 is a tooling problem (no Java, a bad jar, TLC did not finish).

| Run | Checks | Delete callers | Baseline |
| --- | --- | --- | --- |
| `types` | `TypeOK` | all | holds |
| `older-keeps-out` | `OlderEntryKeepsOut` | all | holds |
| `delete-open` | `DeleteSparesOpen` | all | holds |
| `old-filler-parts-all` | `OldFillerLeavesParts` | all | holds |
| `reads-return` | `ReadsReturn` | all | holds |
| `older-keeps-out-evict` | `OlderEntryKeepsOut` | evict | holds |
| `old-filler-truncate` | `OldFillerNeverTruncates` | evict | holds |
| `old-filler-parts` | `OldFillerLeavesParts` | evict | holds |
| `delete-open-evict` | `DeleteSparesOpen` | evict | holds |
| `range-ignored` | `NoRequestLoop`, server ignores Range, 2 chunks, 1 entry | all | holds |
| `5beae1e-delete-race` | `DeleteSparesOpen`, `PreFixDeleteUnlocked` | evict | fails (replay) |
| `5beae1e-busy-loop` | `ReadsReturn`, `PreFixDeleteUnlocked`, `PreFixNoDeadline` | evict | fails (replay) |
| `5beae1e-old-filler` | `OldFillerLeavesParts`, `PreFixNoGoneCheck` | evict | fails (replay) |
| `5beae1e-retry-deadline` | `ReadsReturn`, `PreFixNoDeadline`, `PreFixDeleteNoRecheck`, 1 entry | all | fails (replay) |
| `5beae1e-range-loop` | `NoRequestLoop`, `PreFixNoSkip`, server ignores Range, 2 chunks, 1 entry | all | fails (replay) |
| `delete-recheck` | `DeleteSparesOpen`, `PreFixDeleteNoRecheck` | all | fails (replay) |
| `open-unlocked` | `OldFillerNeverTruncates`, `PreFixOpenUnlocked` | evict | fails (replay) |
| `length-no-gone` | `OlderEntryKeepsOut`, `PreFixLengthNoGone` | evict | fails (replay) |

"evict" sets `DeleteFindsEntry`: `delete` runs only while the key has an entry in the map, as in `evict()`, which calls `entry(key)` for every key before it deletes (`Media.kt:693-704`, `DesktopMedia.kt:93-101`). "all" adds `clearBuffer()` and `removeDownloadBytes()`, which can call `delete` for a key with no entry.

The replay runs each put back a race that a commit fixed, with `PreFix*` constants. They must keep failing with their recorded counterexample. If one passes, the model can no longer see that race: treat it as a modeling error and fix the model. Never set a `PreFix*` constant in `Scratch.cfg`.

- `5beae1e-delete-race` and `5beae1e-busy-loop`: `delete` ran `deleteRecursively` after leaving the lock (`PreFixDeleteUnlocked`), so an `entry(key)` in between created an entry that counted the chunks being deleted as present. With the retry that had no deadline (`PreFixNoDeadline`), its reader spun forever.
- `5beae1e-old-filler`: a shut-down filler renamed or deleted a part without checking `gone` (`PreFixNoGoneCheck`, `Scratch.kt:474`, `:603`), so it hit the part of the newer entry for the same key.
- `5beae1e-retry-deadline`: the retry after `NoSuchFileException` or `AccessDeniedException` did not check its deadline (`PreFixNoDeadline`, `:330`). A chunk vanishing under a reader made the reader spin forever. The model's only way to make one vanish is the race `delete-recheck` replays, so this run needs `PreFixDeleteNoRecheck` too.
- `5beae1e-range-loop`: for a server that ignores Range, `fetch` returned at the first unwanted chunk instead of skipping to the target (`PreFixNoSkip`, `:564`), so `fill` sent the same request again at once, over and over.
- `delete-recheck`: `delete` did not check that `entries[key]` was still the entry it read (`PreFixDeleteNoRecheck`, `:59`). `clearBuffer()` or `removeDownloadBytes()` called `delete(key)` for a key with no entry, so `delete` skipped `release`. Between its two `synchronized` blocks, the player's request called `entry(key)`, which created an entry that found chunk 0 in the folder and marked it present, and then `demand()`. Then `delete` removed that entry from the map and deleted the folder, and the entry was never shut down. Its reader found chunk 0 marked present, the open threw `NoSuchFileException`, and `readInternal` retried without waiting until its deadline, 20 s for the server (`ScratchServer.kt:18`). The filler never fetched chunk 0 again, and the next `entry(key)` made a second live entry on the same folder, whose filler could rename or delete the first entry's parts.
- `open-unlocked`: the filler opened `FileOutputStream(partFile(i))` after leaving the lock in which it chose chunk i, with no `gone` check (`PreFixOpenUnlocked`, `:558`). Filler 1 chose chunk 0 and stalled. The request closed its demand, `delete` shut entry 1 down and deleted the folder, and a new request created entry 2, whose filler created the folder again and opened part 0. Filler 1 then opened part 0 and truncated entry 2's bytes.
- `length-no-gone`: the length block did not check `gone` (`PreFixLengthNoGone`, `:528`). Filler 1's response headers arrived just before `delete` shut entry 1 down. Entry 2 was created and its filler wrote the length file, and then filler 1 created the folder again and replaced the length file. The value was the same, but two `writeAtomically` calls on `length.tmp` at once could fail one of them, and with no entry 2 it left a folder with only a length file.

Read every counterexample with the pstack **statechart** skill (`/pstack:statechart`). It tells each invariant violation as its shortest path in plain words, and with a fixed model it shows which violations a change fixes. For `ReadsReturn`, a property, it points to TLC's loop; `run.sh` writes the loop's way back as `Action->N`, which returns to state N. TLC's raw trace names each step's action; the Coverage table maps the names to code.

## Check a change

1. Run `run.sh` on the change.
2. If it prints `DRIFT`, run the `git diff` it names for each file. For each changed line, find the model action in Coverage. If the change alters what that step does, edit the action to match. Keep one action per indivisible step of the code: a step ends where the code leaves a `synchronized` block or does network or file I/O outside one. Give each condition a `\* :line` comment (`Scratch.kt`) or `\* ScratchServer.kt:line`. A new path that creates, truncates, renames or deletes a file under the key's folder, or that changes `gone`, `present`, `writing` or the demands, needs its own action. Removing a check from the code means removing the same condition from its action, not setting a `PreFix*` constant.
3. Run again and read each `CHANGE`:
   - Walk the trace step by step against the code. If a step cannot happen in the code (a lock the model misses, an order the code forces), it is a modeling error. Fix the model and cite the line that forbids the step. Never weaken a check, and never shrink the bounds to make a run pass.
   - If every step can happen, it is a real bug. Report it as a story in the code's words (which thread, which entry, which file) and change nothing to hide it.
   - Record a real bug as a baseline in `run.sh` and under Known findings below. A fix should turn its runs to `ok`: set those baselines to `pass`, and add a `PreFix*` constant and a replay run that puts the bug back and fails with the recorded counterexample.
4. When the model describes the code again, run `run.sh --confirm`. It runs everything and, only if every run matches its baseline, rewrites the hashes below. Update the line numbers in Coverage and in the model's comments first. Do not confirm only because the runs match: a stale model matches too.

## Coverage

| Model | Code (`core/src/main/kotlin/finimal/core/`) |
| --- | --- |
| `Lookup` | `Scratch.entry` (`Scratch.kt:43-47`), `Entry` init (`:96-100`, `:143-152`); `ScratchServer.kt:82-86` |
| `Demand` | `Entry.demand` (`:202-215`), `wake` (`:224-229`); `ScratchServer.kt:90-94` |
| `ReadFind`, `ReadFail`, `ReadOpen`, `ReadRetry`, `Tick` | `readInternal` (`:286-337`), with `awaitLength` (`:340-352`) folded in; `ScratchServer.kt:96-126` |
| `ReadNext`, `Close` | `ScratchServer.kt:95`, `:129`; `Demand.moveTo` (`:84-88`), `drop` (`:217-221`) |
| `DelLookup`, `DelRefused`, `DelRelease`, `DelRemove`, `DelFiles` | `Scratch.delete` (`:53-64`), `release` (`:232-237`), `shutDown` (`:239-246`) |
| `Target`, `Pick`, `FillerExit` | `computeTarget` (`:403-424`), `fill` (`:429-440`), the call in `fetch` (`:503-508`) |
| `HeaderArrives`, `CallFails` | `fetch` (`:510-542`), `fill`'s catch (`:465-467`) |
| `StartChunk`, `SkipChunk`, `SkipFails`, `StopFetch` | `fetch` (`:546-577`), `fill`'s `StorageError` catch (`:443-455`); `OpenPart` only with `PreFixOpenUnlocked` |
| `WriteChunk`, `BodyFails`, `Commit` | `fetch` (`:578-611`) |
| `Finally` | `fill`'s finally (`:469-479`) |

Callers that decide `DeleteFindsEntry` (not hashed; re-read them when they change): `evict`, `clearBuffer` and `removeDownloadBytes` in `app/src/main/kotlin/com/zakius/finimal/Media.kt`, and `evict` and `clearBuffer` in `desktop/src/main/kotlin/com/zakius/finimal/desktop/DesktopMedia.kt`.

Hashes of the covered files when the model was last confirmed against them (`run.sh --confirm` rewrites this block):

```sha256
0ca91e24840f94ede95a0bfdeef8feeb1267e5e2884851e265b2938a71e39437  core/src/main/kotlin/finimal/core/Scratch.kt
bf81dd2043a682e115f31747f657c3a908a71564eeb11130ec0d79d55d9f34c3  core/src/main/kotlin/finimal/core/ScratchServer.kt
```

## Known findings

None.

## What the model leaves out

- Bounds: one key, one chunk (two in the `range` runs), up to two entries per run (one in the `range` runs and `5beae1e-retry-deadline`), one ScratchServer request at a time, one `delete` call at a time. The disk starts empty, or with a length file and any set of chunks from an earlier run. With all callers, `older-keeps-out` and `delete-open`, which between them cover every check except `ReadsReturn` and `NoRequestLoop`, also hold with three entries, and with two chunks.
- One demand per entry. The player, a buffer and a download on the same entry at once are not modeled, and neither are `prewarmRead`, `download()` or `startBuffer`, which only open and close demands.
- Network errors other than a cancel, `backOff`, HTTP status errors, `WrongFile`, and a server whose file changes. `StorageError` makes the entry fail at once: `onStorageFull()` is taken to free nothing, and `lowSpace` is folded into `fatal`.
- Disk semantics are Linux's: deleting or renaming an open file works, and `renameTo` replaces an existing chunk. Windows sharing rules (`AccessDeniedException` while a delete is pending) appear only as an open that fails, like a missing file. `deleteRecursively`, `writeAtomically` and the writes of one chunk are each one step. A filler stalled between two writes of a chunk is not modeled.
- `Scratch.close()` (switching storage), `keys()`, `touch()`, the eviction order and the `playing` key.
- Assumed: an HTTP response that arrived before a cancel is processed (the model lets `HeaderArrives` run after a cancel), and a body read on a cancelled call may throw or still return bytes.
