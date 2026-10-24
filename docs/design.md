# plainfin design

A Jellyfin client for Android that goes straight to the library. It prefers a few extra taps over convenience features that break.

## Scope

- Android phone and tablet, portrait and landscape. Minimum API 26. No Google Play Services: it must run on EMUI.
- One server, one user. Username/password login; the token is stored.
- Movies and TV shows only. Other library types are hidden.
- Out of scope: Android TV, background audio, picture-in-picture, Quick Connect, home rows, Next Up, Continue Watching.
- Desktop is a maybe. Keep `core` free of Android imports so a JVM desktop app with libmpv could reuse it.

## Stack

- Kotlin, Jetpack Compose, Media3 with the Jellyfin FFmpeg audio decoder, libass-android for ASS/SSA subtitles, jellyfin-sdk-kotlin, WorkManager.
- License GPL-3.0. Findroid (GPL-3.0) code may be copied. jellyfin-android is GPL-2.0 without "or later", so it is a reference only.
- Distribution: GitHub Actions builds a signed APK on each tag and attaches it to a release. Updates come through Obtainium.

## Navigation

Libraries → poster grid → series → seasons → episodes.

- Grid: sort by name, date added or release date; an "unplayed only" toggle; search.
- Episodes: thumbnail, played mark, progress bar.
- Tap: resume from the saved position minus 5 s. Long-press: start over, mark played/unplayed, download, delete download.
- End of an episode: a "Play next" button. The next episode comes from season order, never from the server's Next Up.

## Playback

- Direct play by default. Transcode only when the device cannot decode the file. Buffering to the end is best-effort during a transcode, and the UI says so.
- Portrait: the video sits on top with episode info below. Swipe up or rotate to enter fullscreen.
- Fullscreen gestures, each one can be turned off:
  - Vertical swipe on the side zones: brightness and volume. Which side controls which is a setting.
  - Swipe down in the center zone exits fullscreen.
  - Horizontal swipe seeks.
  - Double-tap skips ±N s (N is a setting).
  - Pinch switches between fit and crop.
- Audio and subtitle choices are remembered per series.
- Media segments show a "Skip intro" button. It never skips on its own.
- Player menu: speed, tracks, subtitle delay and size, rotation lock.
- Theme follows the system and uses Material You colors.

## Buffering and downloads

There is one persistent on-disk cache, keyed by item ID.

- Buffering is a background fill of the cache to the end of the episode. Playback reads from the cache.
- A download is the same fill, marked pinned.
- Buffered entries are evicted oldest-first above a size limit (default 5 GB). Pinned entries are removed only by the user.
- An interrupted fill resumes from the last byte, including after a crash or reboot.
- Two toggles, both off by default: "buffer to end on metered" and "download on metered". They follow Android's metered flag, not wifi/cellular. With buffering off on a metered network, the player uses its normal buffer.
- Downloads are original files in app-specific storage. The SD card can be selected.
- "Keep next N downloaded" is per series and off by default.
  - Anchor: the last played episode in season order, specials excluded.
  - Checked when the app opens, when an episode ends, and when the network becomes unmetered.
  - It deletes only episodes it downloaded itself, after they are marked played.
- One Downloads screen shows the queue, pause/resume, delete and storage use. It is also the offline screen: when the server cannot be reached, only downloads are browsable.

## Sync

The root cause of "the series restarts from episode 1" in other clients:

- The server marks an item Played at position 0 when a stop report has no position.
- Next Up falls back to the first unplayed episode when no episode in the series is Played.
- Offline sync that drops Played or LastPlayedDate makes this worse.

Rules:

1. Report progress every 10 s, and on pause, seek, backgrounding and stop. Every stop report carries a position.
2. Never read Next Up or Resume from the server.
3. With no connection, append events to a local queue. Replay them through `POST /UserItems/{id}/UserData` with PlaybackPositionTicks, Played and LastPlayedDate.
4. Conflicts: the newer LastPlayedDate wins.
5. Offline plays use the server's default limits: under 5 % stores position 0, over 90 % marks played and stores 0.
6. Opening a list first pushes pending events, then fetches. If the push or fetch fails, show local state marked "not synced".

## Testing

- Unit tests in `core`: sync queue, conflict rule, played limits, the keep-next-N anchor, range resume.
- A local Jellyfin server in Docker with sample media for end-to-end checks.
