# finimal design

A Jellyfin client for Android, Linux and Windows that goes straight to the library. It prefers a few extra taps over convenience features that break.

## Scope

- Android phone and tablet, portrait and landscape, and Android TV and TV boxes. Minimum API 26. No Google Play Services: it must run on EMUI.
- One server, several users. Username/password login; each account's token is stored.
  - Accounts: the avatar at the top right of Libraries opens the list, to switch or "Add account" (a login for the same server, whose fields are locked). At a cold start with two or more accounts, "Who's watching?" comes first; with one, it opens. Logged out with accounts left, the picker shows, never an account picked for the user.
  - A session the server ends keeps the account and its PIN; logging in again restores it. The download notification never opens over "Who's watching?".
- An account may have a PIN of 4 to 8 digits, asked when switching to it, also from the picker after a restart. Jellyfin has no login PIN, so it is ours: a salted SHA-256 beside the token, a household lock rather than security. Only the account itself sets, changes or removes it.
  - Per account: play state, the unsynced queue, lists, downloads and their queue, "keep next N", the per-series tracks, "Direct play up to", subtitle size and skip step. Per device: everything describing the hardware or the network (decoders, learned codecs, audio delay, gestures, interface, buffer, storage, metered).
  - Logging in to another server replaces every account. A failed "Add account" leaves the current one logged in.
  - Settings from before accounts move to the account that was logged in, once; its downloads too.
  - The form has the server and, beside it, the port, prefilled with Jellyfin's 8096 (`serverCandidates` in `core`). Without a scheme it tries https, then http, on that port; with 8096 left as it is, https on 443 last, for a server behind a reverse proxy. A port in the server field wins; an empty port means the scheme's default; a written scheme is tried as written. A certificate problem (untrusted, expired, wrong name) never falls back: the password would go out in clear text. It says "The server's certificate isn't trusted". Logging in again shows the saved address split back into the two fields.
  - Login stores URL, token and user in one write. The password field has Show/Hide; editing a field clears the old error.
  - Login errors say "Wrong username or password" (HTTP 401). Anything else gets the same sentence as a list that fails to load (can't reach the server, a server error with its status, or a generic message), with the raw error in small text. Can't reach the server also names the addresses tried.
- Movies and TV shows only. Other library types are hidden.
- Out of scope: background audio, picture-in-picture, Quick Connect, home rows, Next Up, Continue Watching.
- Desktop: Linux and Windows. See [Desktop](#desktop).

## TV

- The TV interface is picked on Android TV (TV UI mode) and on devices with no touchscreen, which covers boxes on a plain Android launcher. Settings → Interface overrides it (Auto, Touch, TV), for boxes that claim a touchscreen.
- One app for both: the activity is in the Android TV launcher (`LEANBACK_LAUNCHER`, a banner) and the normal one; touchscreen and leanback are not required features.
- The TV interface keeps Android TV's 48/27 dp overscan margins, shows bigger posters and hides touch-only things: the gesture settings, the fullscreen button and rotation lock.
- D-pad focus shows as a stronger highlight on rows and buttons and a border on posters. Back puts focus on the item it left. A long press on OK opens a movie's menu.
- Player keys: left and right skip by the skip step while the controls are hidden; OK plays or pauses and brings the controls up with Play focused; up and down bring them up; Back hides them first. Media keys (play/pause, fast-forward, rewind, next) work at any time. Buttons that appear on their own (Skip intro, Play next, Retry) take focus, so OK presses them.

## Stack

- Kotlin, Jetpack Compose, Media3 with the Jellyfin FFmpeg audio decoder, libass-android for ASS/SSA subtitles, jellyfin-sdk-kotlin, WorkManager.
- License GPL-3.0. Findroid (GPL-3.0) code may be copied. jellyfin-android is GPL-2.0 without "or later", so it is a reference only.
- Distribution: GitHub Actions builds one signed APK per ABI (arm64-v8a, armeabi-v7a, x86_64, x86) on each tag and attaches them to a release. Updates come through Obtainium.
- Desktop: Compose for Desktop, libmpv through JNA, jpackage. On each tag CI builds and checks the Linux Flatpak, .deb (Ubuntu 26.04, Debian 13) and .rpm (Fedora), and the Windows .msi and .zip in a full and a small flavour, and attaches them to the release.

## Desktop

- One UI for both: the screens, `Server`, `Prefs` and navigation are in the `shared` Kotlin Multiplatform module (Android and desktop JVM). Each app supplies a `Platform`: storage folders, settings store (SharedPreferences, or a JSON file under `$XDG_CONFIG_HOME`/`%APPDATA%`), the Jellyfin device identity, downloads, background housekeeping. Android-only settings rows (interface mode, touch gestures, metered networks, SD card, direct play codecs and learned codecs) hide on the desktop; it shows the buffer cache limit, audio delay, skip step, "Direct play up to" and the account.
- Each app has its own player. The desktop one embeds libmpv (JNA) and renders through mpv's software renderer into Skia bitmaps, so our Compose controls draw over the video. libmpv is never called on the UI thread: commands are async, properties arrive as observed events, frames render on their own thread. If the software renderer turns out too slow for 4K, the next step is mpv's OpenGL render API.
- mpv decodes everything, so the desktop only transcodes when "Direct play up to" caps the height (for bandwidth).
- Streams go through the same scratch and loopback proxy as on Android, buffering to the end. No downloads on the desktop yet.
- Keyboard: Space/K play-pause, Left/Right (J/L) skip by the skip step, Up/Down volume by 5, M mute, F or double-click fullscreen, N next episode, Page Down/Up next/previous chapter, Esc leaves fullscreen, then the player. The media play/pause and next keys work too. A click plays or pauses, the wheel sets the volume, and the mouse's back button is Esc. Mouse movement shows the controls; they and the cursor hide after 3 s of playing. Volume, like speed, carries over to the next episode until the app is closed.
- The window opens at 1280×800, centred, the first time. Its size and place, or that it was maximized, are saved 500 ms after it settles, never fullscreen; a saved place on a monitor that is gone opens centred again. Leaving fullscreen returns to maximized when it was maximized before.
- The screen stays on while playing: the portal's Inhibit on Linux, with org.freedesktop.ScreenSaver as the fallback, and SetThreadExecutionState on Windows.
- On Linux the window scale is Xft.dpi / 96, rounded to a whole number, unless `sun.java2d.uiScale` or `J2D_UISCALE` is set.
- The Jellyfin device name is the computer's name (COMPUTERNAME, or the hostname), else "Desktop". Settings live in a JSON file under `$XDG_CONFIG_HOME` or `%APPDATA%`; data and cache under the XDG folders or `%LOCALAPPDATA%\finimal`. There is no background housekeeping: unsynced progress is pushed while the app runs.
- Linux ships as a .deb and an .rpm that run on the distribution's Java 21 or newer and libmpv, and as a Flatpak: libmpv 0.41 with FFmpeg, libass and libplacebo built in the manifest (after Celluloid's), on the freedesktop runtime, around the jpackage app image. Library bumps come as manifest updates through x-checker-data. AWT is X11 only on Java 21, so it runs on XWayland under Wayland.
- Windows: the full build carries libmpv-2.dll (shinchiro's builds, which mpv.io links) and its Java. The small build has neither: its launcher (`packaging/windows/Launcher.cs`) finds Java 21+ and libmpv on the machine, or downloads Temurin to `%LOCALAPPDATA%\finimal\java` and libmpv to `%APPDATA%\finimal`, each checked against a pinned SHA-256; closing its progress window cancels. A libmpv-2.dll in `%APPDATA%\finimal` takes precedence, so mpv can be updated without a new finimal.
- `finimal --check-player` plays a generated clip headless and exits 0 when libmpv works: the packaging check in CI, which also proves it fails without libmpv.

## Navigation

Libraries → poster grid → series → seasons → episodes. A series with one season lists its episodes on the series screen.

- Grid: sort by name, date added or release date; an "unplayed only" toggle; search.
- Search opens with the field focused and the keyboard up, once; coming back to it does not reopen the keyboard.
- An empty list says why in one centred line (no results for the query, nothing unplayed, empty library, no movie or TV libraries).
- Series screen: a button at the top plays where to pick up: "Continue S2E5 · Title · 23 min left", or "Play" for an episode not started. It is the keep-next anchor on local state (the unfinished episode last played, else the first unplayed one after it, else the first), never the server's Next Up. Nothing shows once every episode is played.
- A season opened for the first time scrolls to and highlights its up-next episode, when anything in it was played.
- Series and season actions sit in a ⋮ menu in the top bar: "Download all" / "Download season", and "Mark season played/unplayed" (asks first; it drops resume points). A bulk mark gives each episode its own LastPlayedDate a millisecond apart, so the anchor lands on the last one marked played or the first one marked unplayed.
- Episodes: thumbnail, played mark, progress bar.
- Movie posters: played mark, progress bar, download state.
- Tap: resume from the saved position minus 5 s. "Start over" saves position 0 locally, then opens the player, so a restored back stack (process death, activity recreation) never restarts the item again. The item menu (long-press on an episode or movie, or the ⋮ button on an episode): start over, mark played/unplayed, download, delete download. When the item's progress is not synced, the menu says so and offers "Sync now", which pushes all pending events and toasts the outcome.
- A list that fails because the server can't be reached retries by itself when a different network comes up.
- A list that fails to load says why in plain words (can't reach the server, a server error with its status, or a generic message), with the raw error in small text, a Retry button and a Downloads button.
- End of an episode: a "Play next" button. The next episode comes from season order, never from the server's Next Up.

## Playback

- Direct play by default. A file starts as a transcode when it is taller than "Direct play up to" (Auto, 4K, 1080p, 720p), when "Direct play codecs" rules its codec out (Auto checks the device's hardware decoders, including 10-bit; "H.264 only"; "All" trusts the file), or when its codec was learned as choppy at that pixel rate or above. A direct play switches to a transcode when the device cannot decode the file: a decoder error, a decode failure mid-stream, or a video track no decoder supports (Media3 would play the audio over black). Desktop: mpv decodes everything, so only "Direct play up to" applies. The transcode asks for the series' remembered audio language. Each player has its own play session ID, sent with every report, so leaving ends the server's transcode at once.
- When direct play drops frames (two batches of 50 within 30 s at normal speed), a "Choppy? Transcode" button shows for 15 s. It never switches on its own. Pressing it records the codec's pixel rate (width × height × frame rate); later files of that codec at that rate or above start as a transcode. Settings lists the learned codecs with "Forget", and shows the device's hardware decoders ("H.264 1080p, HEVC 10-bit 4K").
- The player takes audio focus: it pauses for calls and other players and ducks under navigation prompts.
- The screen stays on only while playing; paused, ended or failed, it times out.
- On resuming, "Resumed at 23:10 · Start over" shows for 5 s. Buffering to the end is best-effort during a transcode, and the UI says so. Desktop: the note says the transcode is for "Direct play up to".
- Portrait: the video sits on top with episode info below. Swipe up or rotate to enter fullscreen.
- Fullscreen gestures, each one can be turned off:
  - Vertical swipe on the side zones: brightness and volume. Which side controls which is a setting.
  - Swipe down in the center zone exits fullscreen.
  - Horizontal swipe seeks.
  - Double-tap skips ±N s. N is the "skip step" setting, shared with the on-screen rewind and forward buttons.
  - Pinch switches between fit and crop.
  - Press and hold plays at 2x until release; the chosen speed comes back after.
- Audio and subtitle choices are remembered per series.
- Media segments show a button, never acting on their own: "Skip intro", "Skip recap", and during the credits "Play next" when there is a next episode.
- "Play next" keeps forced fullscreen and the rotation lock, and leaving through "Play next" in the credits reports the episode watched to the end.
- Chapters come from the server's list for the item (a single one counts as none), so a transcode has them too. The seek bar marks each start, and the menu lists them with the current one ticked. Desktop: Page Down and Page Up go to the next and previous chapter; more than 5 s into a chapter, Page Up goes back to its start, as in mpv.
- On-screen controls: back, title (fullscreen only), menu, fullscreen; rewind and forward by the double-tap step, play/pause, and next episode when there is one.
- Player menu: speed, tracks, subtitle size, wireless audio delay, chapters, rotation lock (not on TV). Desktop: speed, audio, subtitles, subtitle size, audio delay, chapters. The current choice is ticked. Speed carries over to the next episode until the app is closed; it is not saved. Subtitle delay is not built yet: Media3 has no subtitle offset, so it needs its own cue timing. Subtitle size applies to text subtitles; ASS keeps its authored size.
- A transcode carries no subtitles (the server drops embedded ones). Embedded text subtitles come as files instead, like external ones, extracted by the server with their styling; a transcoded download fetches them too. A picture subtitle (PGS, VobSub) in the series' remembered language, with no text one in that language, is burned into the video.
- Text subtitles (SRT, VTT, embedded text tracks) are white with a black outline and no background box. The system caption style is ignored. ASS keeps its authored style.
- A white spinner shows while the item loads and while the player buffers. It does not block taps or gestures.
- An item that fails to load and is not downloaded says why like a list does, with Retry and Back.
- A finished download waits at most 2 s for the server before it plays offline.
- A lost connection doesn't stop playback at once: it plays out the buffer, then shows "Reconnecting…" over the last frame and reloads the item at the same position, on a backoff (1, 2, 4, 8, then every 10 s) and at once when a network comes up. Server errors (5xx) count; 4xx do not. After 60 s it gives up and shows the error. Desktop: mpv reconnects on its own for a while, then ends the file early or fails its load; a transcode just stalls. Any of these (an end over 10 s before the duration, a failed load while the server doesn't answer, or 10 s of buffering while it doesn't) starts the same backoff, and a minute later "Can't reach the server." with Retry. A load error while the server answers is the file's and shows at once. Reconnected means the reload loaded with the server answering; a slow connection then buffers as usual, and a user's pause is kept. An early end that comes again right where the file was reloaded, with the server answering, is the file's real end. An early end is never reported as watched. Java has no network-change event, so the desktop waits for the next backoff step.
- A playback error shows a short sentence for common causes (network, timeout, HTTP status, missing file, unsupported format) with Retry and Back, in place of the play buttons. Other errors show a generic sentence with the Media3 error code in small text.
- Wireless audio delay (−200 to 500 ms, also in Settings) holds video back to match Bluetooth headphones and speakers. On Android it applies only while a wireless output is connected, so the speaker and wired outputs stay in sync. It works by reporting the audio clock that much behind, which the video follows. Headphones connected mid-play apply it at once (a seek in place). Desktop: "Audio delay", always applied (mpv's audio-delay).
- Theme follows the system and uses Material You colors.

## Settings

- A whole row toggles its switch. A setting that depends on a gesture turned off is greyed out.
- Log out removes the current account from the device and asks first. When progress is not synced yet, the dialog says how many items, and that they are sent after logging back in to the same server.
- The app version shows at the bottom.

## Buffering and downloads

There is one persistent on-disk store, the scratch (`core`'s `Scratch`), shared by streaming, buffering and downloads, and plain JVM so a desktop app can use it too. The key is the item ID plus the media source ETag, so a file replaced on the server never mixes with bytes from the old one. A finished download keeps playing its own version.

- An entry is a folder per key: a `length` file, then the file in 4 MiB chunks `0`, `1`…. A chunk is written as `<n>.part` and renamed once whole and synced, so a crash leaves at most one `.part`, which is fetched again. No index file to go stale, and no sparse file (slow on exFAT SD cards).
- One filler thread per entry is the only writer. It fetches what the open demands ask for, with HTTP ranges from chunk boundaries, nearest need first: the newest reader (64 MB ahead of what it has sent), then buffer-to-end, then a download from byte 0. A seek elsewhere drops the fetch nobody wants any more. Chunks already on disk are never fetched again.
- The player reads through a loopback HTTP server (`ScratchServer`, `127.0.0.1` with a random token in the path, since other apps can reach loopback ports). Readers never fetch: they wait for the filler and read the chunk being written as its bytes land. When the filler's last try failed (no network, server down), a reader fails at once so the player shows "Reconnecting…"; the player's retry is a new request, which wakes the filler.
- Buffering is a buffer-to-end demand while the player is open.
- Each account has its own download records (`downloads/<userId>/`), so it sees, queues and deletes only its own; the queue runs for the current account, and its notification names only that account's items. The bytes are shared: an item another account also downloaded is stored once, pinned by any account's record, and deleted with the last one. The Downloads screen counts the other accounts' bytes as one total, without titles.
- A download is a whole-file demand on the same entry, marked pinned. Downloads run one at a time in the order requested. Pause and delete act on one episode; the rest of the queue carries on. So does a file the server refuses (HTTP 4xx, e.g. deleted there): that download shows "failed", and Resume tries it again. A finished file whose length differs from the size the server lists (a captive portal or proxy page) is removed and fails the same way. When a chunk can't be written, the buffer is evicted and the write tried again; if that doesn't help, buffering and downloading stop for that entry (the reader still gets chunks while there is room), and the download pauses as "storage full" and the queue stops until Resume or the next start.
- A queued download fetches its item again when it starts, so a file replaced on the server since queuing downloads under its new key.
- Playing an unfinished download needs nothing special: the reader's demand comes first, so the part being watched lands first, and the download takes the rest. What was buffered while watching counts towards the download, and an item fully buffered becomes a finished download at once.
- Buffered entries are evicted oldest-first above a size limit (default 5 GB; the setting steps through 1, 2, 5, 10, 20, 50, 100 GB), counting buffered bytes only. The limit drops further to keep max(2 GB, 5 % of the volume) free. Pinned entries are removed only by the user. An entry is pinned by its download record's file name, so a record that no longer decodes (renamed to `.corrupt`) still keeps its bytes.
- An interrupted fill resumes at the first missing chunk, including after a crash or reboot. A buffer fill ends with the player.
- Two toggles, both off by default: "buffer to end on metered" and "download on metered". They follow Android's metered flag, not wifi/cellular. With buffering off on a metered network, the player uses its normal buffer.
- Downloads live in app-specific storage. The SD card can be selected. Switching applies at once and asks first: it deletes every download and the buffer instead of moving them (bytes left on the old volume would be orphaned, and the downloads would silently fetch again).
  - A selected SD card missing at start leaves downloads waiting: the queue and buffering stop, the Downloads screen and queued items say "waiting for SD card", and playback streams. The card is checked again at the next start. Switching to internal storage stays possible, as the way out of a lost card.
- Where data lives:
  - The scratch (downloads and buffer) is in app-specific storage on the selected volume, not the system cache directory: Android may delete cache files at any time, and a download must stay until the user deletes it. The buffer part is held to its own limit instead.
  - Download records, their subtitles and unsynced play state are app files. Subtitles fetched only to stream are deleted by housekeeping.
  - Saved lists and thumbnails are in the system cache directory: they can be fetched again, so "Clear cache" and low storage may remove them.
  - Nothing is backed up or copied to a new phone: downloads are too large, and records without their bytes or login would only mislead.
- "Keep next N downloaded" is per series and off by default.
  - Anchor: the last played episode in season order, specials excluded. Among equal LastPlayedDates (a season marked played on the server) the later episode wins.
  - Checked when the app opens and whenever playback stops, once a network is up.
  - It deletes only episodes it downloaded itself, after they are marked played.
  - Changing N runs the check at once. Turning it off deletes its played and unfinished downloads; its finished, unplayed ones stay as regular downloads.
- Deleting a download asks first, naming the item. A dialog, not undo: the bytes go at once.
- "Download all" (series) and "Download season" say how many episodes are new, and their total size when the server lists every file's size, before queuing. With nothing new, a toast says so; when the items can't be fetched, it says why instead.
- One Downloads screen shows the queue, pause/resume, delete and storage use. It is also the offline screen: when the server cannot be reached, downloads play and every other list shows its last saved copy, if any; a non-downloaded item then fails to open with the usual error.
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
4. Conflicts: the newer LastPlayedDate wins. Local dates are on the server's clock (offset from the HTTP `Date` header, saved for offline plays), so a phone clock that runs fast can't beat a later play elsewhere.
5. Offline plays use the server's default limits: under 5 % stores position 0, over 90 % marks played and stores 0.
6. Opening a list first pushes pending events, then fetches. If the push or fetch fails, show local state marked "not synced". A list visited before shows its last result while this runs: from memory, or after a restart from a copy on disk (search results excepted; logging out deletes them). If the fetch fails, that copy stays up under a line saying why, with Retry. The copy is display only: it never feeds sync or keep-next.
7. Read play state only from list endpoints (`/Items?ids=`). On server 12.3.0, `/Items/{id}` and `/UserItems/{id}/UserData` kept returning a state from before a media file was replaced and rescanned, while list endpoints and the database were correct. A restart cleared it.
8. The SDK's `LocalDateTime` is in the device's zone, not UTC.
9. Unsynced state is kept per server and user, in its own file: progress kept over a logout waits for that account and never reaches another. One flush runs at a time.
10. Resume guard: within 30 s of starting, without a seek, a report that would move a resume point back under 5 % reports the start position instead. A player leaving before it loaded reports nothing.
11. The played flag a report carries is the latest this process recorded (until a newer server state is fetched), so "Mark played" during playback isn't undone by the player's load-time copy.
12. Every local play-state change is also written into the download's stored item, and housekeeping refreshes downloaded items from the server, so offline playback resumes from the latest position.

## Testing

- Unit tests in `core`: sync queue, conflict rule, played limits, the keep-next-N anchor, eviction, and the scratch against a local origin server (streaming, ranges, seeks pre-empting the fill, resuming after a dropped connection or a crash's `.part`, refusals, wrong sizes, servers ignoring ranges, an unreachable server, players hanging up mid-body).
- Downloads and the buffer moved from Media3's cache to the scratch in 0.7. There is no migration: the old cache is deleted at start, and existing downloads fetch again.
- A local Jellyfin server in Docker with sample media for end-to-end checks.

## Start-up

- Release APKs embed the baseline profile at `assets/art-profile/baseline.prof` (`tools/release.sh`), so Android 15+ compiles the app when it is installed. `app/src/main/baseline-prof.txt` covers finimal and the libraries its launch and first list load run through.
- The Libraries list from the last visit is read from disk at app start, off the main thread, so the first frame usually shows it instead of a spinner.
- Only what the first frame needs runs before it. WorkManager starts on first use, emoji2's start-up step is removed, and the WorkManager calls, the codec-list read and the queue kick wait for MainActivity's first frame (`App.afterFirstFrame`). The first composition reads login state from the preferences, not from the API client.
- The splash goes without Android's fade-out, and the starting window follows the system's light or dark mode.
- Measuring: install with `adb install --no-incremental` (an incremental install skips compilation), then take the median `am start -W` TotalTime of at least 10 cold starts.
