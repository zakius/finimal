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
- `known`: TLC's counterexample is the recorded one, step for step. This is a known finding, or a replay of a bug that 5beae1e fixed.
- `CHANGE`: the result differs from the baseline: a check that held now fails, a recorded counterexample changed, or a replay passes. The exit code is 1.
- `DRIFT` (printed first): a covered file no longer matches its hash in Coverage, in a commit or in the working tree. It names the commit to diff against. A clean result means nothing until you do step 2 below. A run that matches the baseline exits 3 while drift remains. Exit 2 is a tooling problem (no Java, a bad jar, TLC did not finish).

| Run | Checks | Delete callers | Baseline |
| --- | --- | --- | --- |
| `types` | `TypeOK` | all | holds |
| `older-keeps-out` | `OlderEntryKeepsOut` | all | known finding 1 |
| `delete-open` | `DeleteSparesOpen` | all | known finding 1 |
| `old-filler-parts-all` | `OldFillerLeavesParts` | all | known finding 1 |
| `reads-return` | `ReadsReturn` | all | holds |
| `older-keeps-out-evict` | `OlderEntryKeepsOut` | evict | known finding 3 |
| `old-filler-truncate` | `OldFillerNeverTruncates` | evict | known finding 2 |
| `old-filler-parts` | `OldFillerLeavesParts` | evict | holds |
| `delete-open-evict` | `DeleteSparesOpen` | evict | holds |
| `range-ignored` | `NoRequestLoop`, server ignores Range, 2 chunks, 1 entry | all | holds |
| `5beae1e-delete-race` | `DeleteSparesOpen`, `PreFixDeleteUnlocked` | evict | fails (replay) |
| `5beae1e-busy-loop` | `ReadsReturn`, `PreFixDeleteUnlocked`, `PreFixNoDeadline` | evict | fails (replay) |
| `5beae1e-old-filler` | `OldFillerLeavesParts`, `PreFixNoGoneCheck` | evict | fails (replay) |
| `5beae1e-retry-deadline` | `ReadsReturn`, `PreFixNoDeadline`, 1 entry | all | fails (replay) |
| `5beae1e-range-loop` | `NoRequestLoop`, `PreFixNoSkip`, server ignores Range, 2 chunks, 1 entry | all | fails (replay) |

"evict" sets `DeleteFindsEntry`: `delete` runs only while the key has an entry in the map, as in `evict()`, which calls `entry(key)` for every key before it deletes (`Media.kt:693-704`, `DesktopMedia.kt:93-101`). "all" adds `clearBuffer()` and `removeDownloadBytes()`, which can call `delete` for a key with no entry. Known finding 1 breaks the delete and old-filler checks when all callers are in, and its short counterexample hides any other path that breaks them, so the evict runs keep the 5beae1e fixes in view.

The `5beae1e-*` runs each put back a race that commit 5beae1e fixed, with `PreFix*` constants. They must keep failing with their recorded counterexample. If one passes, the model can no longer see that race: treat it as a modeling error and fix the model. Never set a `PreFix*` constant in `Scratch.cfg`.

- `5beae1e-delete-race` and `5beae1e-busy-loop`: `delete` ran `deleteRecursively` after leaving the lock (`PreFixDeleteUnlocked`), so an `entry(key)` in between created an entry that counted the chunks being deleted as present. With the retry that had no deadline (`PreFixNoDeadline`), its reader spun forever.
- `5beae1e-old-filler`: a shut-down filler renamed or deleted a part without checking `gone` (`PreFixNoGoneCheck`, `Scratch.kt:472`, `:599`), so it hit the part of the newer entry for the same key.
- `5beae1e-retry-deadline`: the retry after `NoSuchFileException` or `AccessDeniedException` did not check its deadline (`PreFixNoDeadline`, `:328`). Known finding 1 still makes a chunk vanish under a reader, so the reader spins forever.
- `5beae1e-range-loop`: for a server that ignores Range, `fetch` returned at the first unwanted chunk instead of skipping to the target (`PreFixNoSkip`, `:555`), so `fill` sent the same request again at once, over and over.

Read every counterexample with the pstack **statechart** skill (`/pstack:statechart`). It tells each invariant violation as its shortest path in plain words, and with a fixed model it shows which violations a change fixes. For `ReadsReturn`, a property, it points to TLC's loop; `run.sh` writes the loop's way back as `Action->N`, which returns to state N. TLC's raw trace names each step's action; the Coverage table maps the names to code.

## Check a change

1. Run `run.sh` on the change.
2. If it prints `DRIFT`, run the `git diff` it names for each file. For each changed line, find the model action in Coverage. If the change alters what that step does, edit the action to match. Keep one action per indivisible step of the code: a step ends where the code leaves a `synchronized` block or does network or file I/O outside one. Give each condition a `\* :line` comment (`Scratch.kt`) or `\* ScratchServer.kt:line`. A new path that creates, truncates, renames or deletes a file under the key's folder, or that changes `gone`, `present`, `writing` or the demands, needs its own action. Removing a check from the code means removing the same condition from its action, not setting a `PreFix*` constant.
3. Run again and read each `CHANGE`:
   - Walk the trace step by step against the code. If a step cannot happen in the code (a lock the model misses, an order the code forces), it is a modeling error. Fix the model and cite the line that forbids the step. Never weaken a check, and never shrink the bounds to make a run pass.
   - If every step can happen, it is a real bug. Report it as a story in the code's words (which thread, which entry, which file) and change nothing to hide it.
   - A change meant to fix a known finding should turn its runs to `ok`. Confirm that, set those baselines in `run.sh` to `pass`, and move the finding out of Known findings. Once finding 1 is fixed, its runs can show findings 2 and 3 instead: record those counterexamples as the new baselines.
4. When the model describes the code again, run `run.sh --confirm`. It runs everything and, only if every run matches its baseline, rewrites the hashes below. Update the line numbers in Coverage and in the model's comments first. Do not confirm only because the runs match: a stale model matches too.

## Coverage

| Model | Code (`core/src/main/kotlin/finimal/core/`) |
| --- | --- |
| `Lookup` | `Scratch.entry` (`Scratch.kt:43-47`), `Entry` init (`:94-98`, `:141-150`); `ScratchServer.kt:82-86` |
| `Demand` | `Entry.demand` (`:200-213`), `wake` (`:222-227`); `ScratchServer.kt:90-94` |
| `ReadFind`, `ReadFail`, `ReadOpen`, `ReadRetry`, `Tick` | `readInternal` (`:284-335`), with `awaitLength` (`:338-350`) folded in; `ScratchServer.kt:96-126` |
| `ReadNext`, `Close` | `ScratchServer.kt:95`, `:129`; `Demand.moveTo` (`:82-86`), `drop` (`:215-219`) |
| `DelLookup`, `DelRefused`, `DelRelease`, `DelRemove`, `DelFiles` | `Scratch.delete` (`:53-62`), `release` (`:230-235`), `shutDown` (`:237-244`) |
| `Target`, `Pick`, `FillerExit` | `computeTarget` (`:401-422`), `fill` (`:427-438`), the call in `fetch` (`:501-506`) |
| `HeaderArrives`, `CallFails` | `fetch` (`:508-538`), `fill`'s catch (`:463-465`) |
| `StartChunk`, `SkipChunk`, `SkipFails`, `StopFetch` | `fetch` (`:542-568`) |
| `OpenPart` | `fetch` (`:569-573`), `fill`'s `StorageError` catch (`:441-453`) |
| `WriteChunk`, `BodyFails`, `Commit` | `fetch` (`:574-607`) |
| `Finally` | `fill`'s finally (`:467-477`) |

Callers that decide `DeleteFindsEntry` (not hashed; re-read them when they change): `evict`, `clearBuffer` and `removeDownloadBytes` in `app/src/main/kotlin/com/zakius/finimal/Media.kt`, and `evict` and `clearBuffer` in `desktop/src/main/kotlin/com/zakius/finimal/desktop/DesktopMedia.kt`.

Hashes of the covered files when the model was last confirmed against them (`run.sh --confirm` rewrites this block):

```sha256
ff321d620c5a2945451921274803b798d8f5b5aec20301dc9e89ecc6ee17113b  core/src/main/kotlin/finimal/core/Scratch.kt
bf81dd2043a682e115f31747f657c3a908a71564eeb11130ec0d79d55d9f34c3  core/src/main/kotlin/finimal/core/ScratchServer.kt
```

## Known findings

1. **A delete that finds no entry removes a new entry's files** (`older-keeps-out`, `delete-open`, `old-filler-parts-all`). `clearBuffer()` or `removeDownloadBytes()` calls `delete(key)` for a key that has no entry in the map, so `delete` skips `release` (`Scratch.kt:54-55`). Between its two `synchronized` blocks, the player's request calls `entry(key)`, which creates an entry that finds chunk 0 in the folder and marks it present, and then `demand()`. Then `delete` removes that entry from the map and deletes the folder (`:57-60`). The entry is not shut down. Its reader finds chunk 0 marked present, the open throws `NoSuchFileException`, and `readInternal` retries without waiting until its deadline (`:323-329`), 20 s for the server (`ScratchServer.kt:18`). The filler never fetches chunk 0 again, since it is present. The entry stays alive outside the map, so the next `entry(key)` makes a second live entry on the same folder, and its filler's `finally` and rename (`:472`, `:599-600`) can hit the other entry's parts, because neither entry is gone.
2. **A shut-down filler truncates a new entry's part** (`old-filler-truncate`). Filler 1 decides to write chunk 0 under the lock (`:549-553`) and is descheduled before `FileOutputStream(partFile(0))` (`:570`). Meanwhile the request closes its demand, `delete` shuts entry 1 down and deletes the folder, and a new request creates entry 2, whose filler creates the folder again and opens part 0. Filler 1 then runs line 570, which has no `gone` check and truncates entry 2's part. Bytes that filler 2 already wrote become zeros, filler 2 keeps writing at its own offset, and it renames the part into chunk 0 (read from `:575-606`; the model checks the truncation, not the zeros). The window is two statements long, so it needs a thread to stall for a whole HTTP round trip.
3. **A shut-down filler rewrites a new entry's length file** (`older-keeps-out-evict`). Filler 1's response headers arrive (`:508`) just before `delete` shuts entry 1 down. Entry 2 is created, and its filler writes the length file. Then filler 1 enters the length block (`:524-538`), which checks neither `gone` nor the cancel, creates the folder again and replaces the length file with `writeAtomically`. The value is the same, so this is harmless unless the two `writeAtomically` calls overlap on `length.tmp` (the model treats each as one step), when one of them fails as a `StorageError`. With no entry 2 yet, it leaves a folder with only a length file.

## What the model leaves out

- Bounds: one key, one chunk (two in the `range` runs), up to two entries per run (one in the `range` runs and `5beae1e-retry-deadline`), one ScratchServer request at a time, one `delete` call at a time. The disk starts empty, or with a length file and any set of chunks from an earlier run. `old-filler-parts`, `delete-open-evict` and `reads-return` also hold with three entries, and with two chunks; `range-ignored` also holds with three chunks.
- One demand per entry. The player, a buffer and a download on the same entry at once are not modeled, and neither are `prewarmRead`, `download()` or `startBuffer`, which only open and close demands.
- Network errors other than a cancel, `backOff`, HTTP status errors, `WrongFile`, and a server whose file changes. `StorageError` makes the entry fail at once: `onStorageFull()` is taken to free nothing, and `lowSpace` is folded into `fatal`.
- Disk semantics are Linux's: deleting or renaming an open file works, and `renameTo` replaces an existing chunk. Windows sharing rules (`AccessDeniedException` while a delete is pending) appear only as an open that fails, like a missing file. `deleteRecursively`, `writeAtomically` and the writes of one chunk are each one step. A filler stalled between two writes of a chunk is not modeled.
- `Scratch.close()` (switching storage), `keys()`, `touch()`, the eviction order and the `playing` key.
- Assumed: an HTTP response that arrived before a cancel is processed (the model lets `HeaderArrives` run after a cancel), and a body read on a cancelled call may throw or still return bytes.
