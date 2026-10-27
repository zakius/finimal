# Play a movie or episode

A click on a movie poster or an episode row opens the player. mpv plays the file directly, and the player has keyboard controls, a seek bar with chapter marks, and a menu for speed, audio, subtitles, subtitle size, audio delay and chapters. The app reports progress to the server when you pause, every 10 s while playing, and when you leave.

## Sub-features

- `play-start` opens the player and shows the first frames.
- `play-pause` toggles pause with Space, K or a click.
- `play-seek` skips with Left/Right (J/L).
- `play-chapters` jumps with Page Down/Page Up and shows chapter marks on the seek bar.
- `play-menu` opens the ⋮ menu (Speed, Audio, Subtitles, Subtitle size, Audio delay, Chapters).
- `play-leave` leaves with Esc (first out of fullscreen) and reports the stop position.
- `play-resume` reopens a movie at its saved position with a `Resumed at m:ss` note and `Start over`.

## How to get to it (user POV)

- Libraries → `Movies` → click a poster.
- Libraries → `Shows` → series → season → click an episode row, or the series' Continue button.
- Right-click a movie or episode → `Start over`.

## Driving it with finimal-verify

Preconditions:

- Launched as `test`. Libraries screen shown.
- For progress checks, use `Test Movie (2020)` (7:00). See Gotchas for `Subs Test`.

- **Start.** Run `$V click Movies`, `$V wait "Test Movie"`, `$V click "Test Movie"`. Wait 5 s, then run `$V shot playback-start`. The window shows video, and the mpv OSD at the top left shows a running timestamp.
- **Pause and show controls.** Run `$V key space`, then `$V move 800 300` to show the controls without a click. Then run `$V wait buffered 10` and `$V shot playback-paused`. The bottom line reads `m:ss / 7:00  (buffered 7:00)` with a play icon, and the title bar reads `Test Movie (2020)`.
- **Seek.** Run `$V key Right Right`, `$V move 820 320`, and `$V shot playback-seeked`. The time is 20 s later (the skip step is 10 s by default, set in Settings). A seek reports to the server 0.5 s later, so `PlaybackPositionTicks` grows by 200000000.
- **Server sees the pause.** A pause reports at once. After 1 s, `$V api /Users/<USER_ID>/Items/da7db03047855530a8c82f7e82385556 | jq .UserData.PlaybackPositionTicks` matches the paused time (10,000,000 ticks a second). Save it to `<EVIDENCE>/playback-server-paused.json`.
- **Leave.** Run `$V key Escape`, `$V wait "Date added"`, and `$V shot playback-left`. The library grid is back, and the server position is the position at leaving.
- **Resume.** Run `$V click "Test Movie"`, `$V wait "Resumed at" 15`, and `$V shot playback-resumed`. The note reads `Resumed at m:ss  Start over`, 5 s before the saved position (the player rewinds 5 s on resume): saved 1:13 shows `Resumed at 1:08`. The note goes away after 5 s.
- **Chapters.** Play `Subs Test`. Run `$V key Page_Down`, `$V key space`, and `$V move 800 300`. The seek bar shows chapter ticks, and the time jumps to the next chapter start. Run `$V shot playback-chapters`.
- **Restore.** Nothing to undo: the item stays `Played` and only its resume point moved. Use `Start over` from the item menu if a journey needs it at 0.

## Gotchas

- `Subs Test (2022)` is 2:00 long, under Jellyfin's 5-minute minimum resume duration. The server stores `Played: true` and position 0 on every stop, so the server shows no resume point for it. Use a 7:00 movie to check progress.
- A click on the video toggles pause. To show the controls, use `$V move <x> <y>`. A real click changes the state you are trying to observe.
- The controls hide after a few seconds. Take the screenshot right after the mouse moves.
- OCR of the time line over video is unreliable: it read `D> 0:53/ 7:00 (buffered 7:00)` once and only `(buffered 7:00)` another time. Wait for `buffered` and take the position from the server or the screenshot, not from the OCR text.
- `Test Movie (2020)` usually has a resume point from earlier sessions, so `play-start` resumes rather than starting at 0:00. Read `PlaybackPositionTicks` before you start.
- Esc leaves fullscreen first. If `F` or a double-click made the player fullscreen, press Esc twice.
- `mpv.log` in the evidence dir has mpv's verbose log (decoder, demuxer, network). Read it when the screen stays black.
