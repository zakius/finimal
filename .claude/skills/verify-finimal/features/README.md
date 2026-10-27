# finimal desktop verification map

This directory is the maintained source for verifying the user-facing behavior of the finimal desktop app. Read this index before you drive the app, then use the matching feature file as the recipe. `V` is `.claude/skills/verify-finimal/finimal-verify` from the repo root.

## Baseline preconditions

- The test server `plainfin-test-jellyfin` answers at `http://localhost:8097` with users `test`/`test` and `test2`/`test`.
- `$V build` ran after the last source change, and `$V launch` printed `ready`.
- `$V doctor` exits 0 and names this checkout's jar.
- The server is shared with other sessions and keeps their changes. Every item under user `test` is usually already `Played`, and the movies have resume points. Read the starting state with `$V api` before you assert a change.
- Never drive an instance this run did not start, and never point the app at the `jellyfin` container on :8096.

## Driving conventions

- Start every recipe from the Libraries screen (`Movies` and `Shows` rows) unless its preconditions say otherwise.
- Click by visible text (`$V click <text>`), and match a distinctive fragment because OCR misreads `0` as `O`. Use `$V xy` only for icon-only buttons, with the coordinates in `SKILL.md`.
- After each action, `$V wait <text>` for the next state instead of a fixed sleep.
- Restore server state that a journey changed (played flags, resume points, PINs), and do not delete proof artifacts.

## Proof and skip reporting

- Capture the user action and the resulting state with `$V shot <feature>-<step>`, before and after.
- Pair the screen with a server read: `$V api /Users/<USER_ID>/Items/<id> | jq .UserData > <evidence>/<feature>-server.json`. `$V env` prints `USER_ID` and `EVIDENCE`.
- Record the feature file and sub-feature ID with every artifact name.
- Report an unreachable path with the command you ran and the unmet precondition. Do not report a skipped entry point as verified through a different path.

## Feature entry contract

Each feature file starts with an H1 title and one paragraph that describes the user-visible behavior. It then has exactly four H2 sections in this order: `Sub-features`, `How to get to it (user POV)`, `Driving it with finimal-verify` (starts with `Preconditions:`), and `Gotchas`.

## Test media

| Item | Id | Runtime | Notes |
| --- | --- | --- | --- |
| Subs Test (2022) | `54f15171b8f4c52c08819d2f3d376614` | 2:00 | 1080p, embedded ASS subtitles, 4 chapters |
| Test Movie (2020) | `da7db03047855530a8c82f7e82385556` | 7:00 | no chapters |
| HEVC Test (2021) | `942fddcbde57fb40417f6243c6a6aaa6` | 7:00 | HEVC |
| Dette er en test show | `9ea196e7ccc3ae17d2c3f75c3ab34406` | | Season 1: 3 episodes of 6:00. Season 2: 3 episodes of 1:00 |

## Features

- [Browse libraries](./browse.md) covers Libraries, the poster grid with sort and the Unplayed filter, and series, seasons and episodes.
- [Play a movie or episode](./playback.md) covers starting playback, pause, seek, chapters, the player menu, leaving, and the progress the server receives.
- [Mark played or unplayed](./mark-played.md) covers the right-click item menu and the played flag on the server.
- [Search](./search.md) covers the Libraries search screen and the search field inside a library.
- [Log in](./login.md) covers the Login screen from a logged-out start.
- [Accounts and PIN](./accounts.md) covers Settings → Accounts and PIN, adding a second user, and the PIN.

Not mapped yet: playback through network drops ("Reconnecting…"; needs a throttling TCP proxy on :8098 in front of :8097 and `FV_SERVER=http://localhost:8098`), Settings values (buffer cache, audio delay, skip step, direct play limit), and Android.
