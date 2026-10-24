# plainfin design

A Jellyfin client for Android that goes straight to the library. It prefers a few extra taps over convenience features that break.

## Scope

- Android phone and tablet, portrait and landscape. Minimum API 26. No Google Play Services: it must run on EMUI.
- One server, one user. Username/password login; the token is stored.
  - A server URL without a scheme tries https first, then http if https can't connect.
  - Login errors say "Wrong username or password" (HTTP 401). Anything else gets the same sentence as a list that fails to load (can't reach the server, a server error with its status, or a generic message), with the raw error in small text. Can't reach the server also names the URL tried.
- Movies and TV shows only. Other library types are hidden.
- Out of scope: Android TV, background audio, picture-in-picture, Quick Connect, home rows, Next Up, Continue Watching.
- Desktop is a maybe. Keep `core` free of Android imports so a JVM desktop app with libmpv could reuse it.

## Stack

- Kotlin, Jetpack Compose, Media3 with the Jellyfin FFmpeg audio decoder, libass-android for ASS/SSA subtitles, jellyfin-sdk-kotlin, WorkManager.
- License GPL-3.0. Findroid (GPL-3.0) code may be copied. jellyfin-android is GPL-2.0 without "or later", so it is a reference only.
- Distribution: GitHub Actions builds one signed APK per ABI (arm64-v8a, armeabi-v7a, x86_64, x86) on each tag and attaches them to a release. Updates come through Obtainium.

## Navigation

Libraries → poster grid → series → seasons → episodes.

- Grid: sort by name, date added or release date; an "unplayed only" toggle; search.
- Search opens with the field focused and the keyboard up, once; coming back to it does not reopen the keyboard.
- An empty list says why in one centred line (no results for the query, nothing unplayed, empty library, no movie or TV libraries).
- Episodes: thumbnail, played mark, progress bar.
- Movie posters: played mark, progress bar, download state.
- Tap: resume from the saved position minus 5 s. The item menu (long-press on an episode or movie, or the ⋮ button on an episode): start over, mark played/unplayed, download, delete download. When the item's progress is not synced, the menu says so and offers "Sync now", which pushes all pending events and toasts the outcome.
- A list that fails to load says why in plain words (can't reach the server, a server error with its status, or a generic message), with the raw error in small text, a Retry button and a Downloads button.
- End of an episode: a "Play next" button. The next episode comes from season order, never from the server's Next Up.

## Playback

- Direct play by default. Transcode only when the device cannot decode the file. Buffering to the end is best-effort during a transcode, and the UI says so.
- Portrait: the video sits on top with episode info below. Swipe up or rotate to enter fullscreen.
- Fullscreen gestures, each one can be turned off:
  - Vertical swipe on the side zones: brightness and volume. Which side controls which is a setting.
  - Swipe down in the center zone exits fullscreen.
  - Horizontal swipe seeks.
  - Double-tap skips ±N s. N is the "skip step" setting, shared with the on-screen rewind and forward buttons.
  - Pinch switches between fit and crop.
- Audio and subtitle choices are remembered per series.
- Media segments show a "Skip intro" button. It never skips on its own.
- On-screen controls: back, menu, fullscreen; rewind and forward by the double-tap step, play/pause, and next episode when there is one.
- Player menu: speed, tracks, subtitle size, rotation lock. The current choice is ticked. Speed carries over to the next episode until the app is closed; it is not saved. Subtitle delay is not built yet: Media3 has no subtitle offset, so it needs its own cue timing. Subtitle size applies to text subtitles; ASS keeps its authored size.
- Text subtitles (SRT, VTT, embedded text tracks) are white with a black outline and no background box. The system caption style is ignored. ASS keeps its authored style.
- A white spinner shows while the item loads and while the player buffers. It does not block taps or gestures.
- An item that fails to load and is not downloaded says why like a list does, with Retry and Back.
- A finished download waits at most 2 s for the server before it plays offline.
- A playback error shows a short sentence for common causes (network, timeout, HTTP status, missing file, unsupported format) and a Retry button. Other errors show a generic sentence with the Media3 error code in small text.
- Theme follows the system and uses Material You colors.

## Settings

- A whole row toggles its switch. A setting that depends on a gesture turned off is greyed out.
- Log out asks first. When progress is not synced yet, the dialog says how many items, and that they are sent after logging back in to the same server.
- The app version shows at the bottom.

## Buffering and downloads

There is one persistent on-disk cache. The key is the item ID plus the media source ETag, so a file replaced on the server never mixes with bytes cached from the old one. A finished download keeps playing its own version.

- Buffering is a background fill of the cache to the end of the episode. Playback reads from the cache.
- A download is the same fill, marked pinned. Downloads run one at a time in the order requested. Pause and delete act on one episode; the rest of the queue carries on. So does a file the server refuses (HTTP 4xx, e.g. deleted there): that download shows "failed", and Resume tries it again.
- A queued download fetches its item again when it starts, so a file replaced on the server since queuing downloads under its new key.
- Playing an unfinished download moves it to the front, and the worker fills from just before the playhead first (again after a seek into an uncached part), then the rest. The player reads the cache without writing it; the worker is the only writer.
- Buffered entries are evicted oldest-first above a size limit (default 5 GB; the setting steps through 1, 2, 5, 10, 20, 50, 100 GB). Pinned entries are removed only by the user.
- An interrupted fill resumes from the last byte, including after a crash or reboot.
- Two toggles, both off by default: "buffer to end on metered" and "download on metered". They follow Android's metered flag, not wifi/cellular. With buffering off on a metered network, the player uses its normal buffer.
- Downloads are original files in app-specific storage. The SD card can be selected. Switching applies at once and asks first: it deletes every download and the buffer cache instead of moving them (bytes left on the old volume would be orphaned, and the downloads would silently fetch again).
  - A selected SD card missing at start leaves downloads waiting: the queue and buffering stop, the Downloads screen and queued items say "waiting for SD card", and playback streams. The card is checked again at the next start. Switching to internal storage stays possible, as the way out of a lost card.
- "Keep next N downloaded" is per series and off by default.
  - Anchor: the last played episode in season order, specials excluded.
  - Checked when the app opens and whenever playback stops, once a network is up.
  - It deletes only episodes it downloaded itself, after they are marked played.
  - Changing N runs the check at once. Turning it off deletes its played and unfinished downloads; its finished, unplayed ones stay as regular downloads.
- Deleting a download asks first, naming the item. A dialog, not undo: the bytes go at once.
- "Download all" (series) and "Download season" say how many episodes are new, and their total size when the server lists every file's size, before queuing. With nothing new, a toast says so; when the items can't be fetched, it says why instead.
- One Downloads screen shows the queue, pause/resume, delete and storage use. It is also the offline screen: when the server cannot be reached, only downloads are browsable.
  - Unfinished downloads come first, in the order they will download; finished ones follow, grouped by series.
  - A queued item shows its place in the queue ("queued #2"). When the current network blocks the queue (none, or metered with "download on metered" off), it says so instead. The toast after queuing does the same.
  - Storage use: total downloads, buffer cache against its limit, and free space on the download volume.
- A running download shows a notification; tapping it opens the Downloads screen. The notification permission (Android 13+) is asked when the user queues downloads or turns "keep next N" on, not at start; Android stops asking after two denials. Denying it does not stop downloads.

## Sync

The root cause of "the series restarts from episode 1" in other clients:

- The server marks an item Played at position 0 when a stop report has no position.
- Next Up falls back to the first unplayed episode when no episode in the series is Played.
- Offline sync that drops Played or LastPlayedDate makes this worse.

Rules:

1. Report progress every 10 s, and on pause, seek, backgrounding and stop. Every stop report carries a position.
2. Never read Next Up or Resume from the server.
3. With no connection, append events to a local queue. Replay them through `POST /UserItems/{id}/UserData` with PlaybackPositionTicks, Played and LastPlayedDate. Server state for every pending item comes from one list request. One failing item does not hold up the rest; an item the server no longer has (HTTP 404) is dropped from the queue. An unreachable server stops the replay at once instead of timing out once per item.
4. Conflicts: the newer LastPlayedDate wins.
5. Offline plays use the server's default limits: under 5 % stores position 0, over 90 % marks played and stores 0.
6. Opening a list first pushes pending events, then fetches. If the push or fetch fails, show local state marked "not synced". A list visited before in this process shows its last result while this runs.
7. Read play state only from list endpoints (`/Items?ids=`). On server 12.3.0, `/Items/{id}` and `/UserItems/{id}/UserData` kept returning a state from before a media file was replaced and rescanned, while list endpoints and the database were correct. A restart cleared it.
8. The SDK's `LocalDateTime` is in the device's zone, not UTC.
9. Every local play-state change is also written into the download's stored item, and housekeeping refreshes downloaded items from the server, so offline playback resumes from the latest position.

## Testing

- Unit tests in `core`: sync queue, conflict rule, played limits, the keep-next-N anchor, eviction. Resuming an interrupted fill relies on Media3's `CacheWriter`, which skips cached ranges.
- A local Jellyfin server in Docker with sample media for end-to-end checks.
