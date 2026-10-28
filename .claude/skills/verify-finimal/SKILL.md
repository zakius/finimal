---
name: verify-finimal
description: Launch and drive the finimal Jellyfin client's Linux desktop app (Compose + libmpv) headless on its own Xvfb, logged in to the local test Jellyfin, and capture screenshot, OCR and server-side evidence. Use to prove a change works in the real app (browse, play, search, mark played, accounts), not just in unit tests.
---

# Verify finimal (desktop, headless)

The primary surface is the desktop app in `desktop/` (shared screens from `shared/`, logic in `core/`). One helper, `finimal-verify` in this directory, does launch, drive, evidence and cleanup. Run it from anywhere; it finds the checkout from its own path.

```sh
V=.claude/skills/verify-finimal/finimal-verify   # from the repo root
```

## Environment this skill assumes

- JDK: `~/toolchain/jdk-21.0.12+8` (no `java` on PATH). The helper sets `JAVA_HOME`, and `ANDROID_HOME=~/Android/Sdk` when the checkout has no `local.properties` (Gradle configures `:shared`'s Android target even for the desktop).
- `Xvfb`, `xdotool`, ImageMagick (`import`, `convert`), `tesseract`, `curl`, `jq`, system `libmpv.so.2`.
- Test server: Docker container `plainfin-test-jellyfin` at `http://localhost:8097`, users `test`/`test` and `test2`/`test`. Libraries `Movies` (`HEVC Test (2021)`, `Subs Test (2022)`, `Test Movie (2020)`) and `Shows` (`Dette er en test show`, seasons 1 and 2). Override with `FV_SERVER=<url>`.
- Never touch the `jellyfin` container (the user's real server) or the `finimal-win` / `oganesson-win` containers.
- Other sessions build on this machine. Before you call a failure real, run `uptime`. At load above 8, a failure counts only if a rerun at low load also fails.

## Launch

```sh
$V build            # once, and after every source change: builds the jars, records the java command line
$V launch           # or: $V launch test2 test
$V launch --logged-out   # no seeded login: the app opens on the Login screen (ready = "Password" seen)
```

`build` runs `:desktop:printRunCommand` from `run-command.init.gradle.kts` (an init script, so the repo's build files stay untouched). It writes the exact command `./gradlew :desktop:run` would execute to `desktop/build/verify-run-command.txt`. `launch` builds first if that file is missing.

`launch` creates a run under `~/.cache/finimal-verify/<run-id>/` (`FV_ROOT` overrides it; not `/tmp`, a RAM tmpfs here). It starts its own `Xvfb` on the first free display from `:77` (1600x1000). It logs in to the server with curl and seeds `settings.json` with the keys a UI login writes (`baseUrl`, `token`, dashed `userId`, `accounts`, `deviceId`). Then it starts the app with private `XDG_CONFIG_HOME`, `XDG_DATA_HOME` and `XDG_CACHE_HOME` and `FINIMAL_MPV_LOG`. It prints `ready` only after OCR sees the `Movies` library on the Libraries screen, so ready means usable, not just a window. A failed launch leaves `launch-failed.png` and `app.log` in the evidence dir. Cold start takes about 5 s at low load.

Each launch is a separate run, so two runs can coexist. Commands act on the last launched run; set `FV_RUN=<run-id>` to address another one. The server is shared, so two runs that change the same item's played state interfere.

To verify the login itself, use `launch --logged-out` and drive the Login screen (`features/login.md`). After that login, `api` and `doctor` use the token the app saved.

## Doctor

```sh
$V doctor
```

Read-only. Checks that Xvfb and the app are alive and are the PIDs this run started, that the app runs this checkout's `desktop/build/libs/desktop.jar`, that its window is mapped, that the server answers, and that the token is valid. It also reports a stale jar (a `.kt` file newer than the jar: run `build`, then `stop` and `launch`). Exit code 0 means the instance is worth driving. Run it first whenever anything looks off.

## Drive

Compose Desktop exposes no accessibility tree on Linux, so the handle is the visible text: the helper OCRs the screen (tesseract, two passes: light text on dark surfaces, then dark text on light buttons such as `Log in`) and clicks the centre of the first line that contains the text. One OCR takes well under a second. Use labels from `shared/src/commonMain/kotlin/com/zakius/finimal/Screens.kt` and `desktop/src/main/kotlin/com/zakius/finimal/desktop/Player.kt`.

## Helper commands

```
finimal-verify build                 build jars, record the java command line
finimal-verify launch [user] [pass]  new run: Xvfb, seeded login, app; waits for the Libraries screen
finimal-verify launch --logged-out   new run with no login; waits for the Login screen
finimal-verify restart               quit and start the app again on the same settings (cold-start paths); wait for a label after it
finimal-verify doctor                read-only health check of the current run
finimal-verify text                  OCR the screen: "x y text" per line
finimal-verify wait <text> [secs]    wait until OCR sees <text> (default 30 s); exit 1 on timeout
finimal-verify click <text>          left-click the first OCR line containing <text>
finimal-verify rclick <text>         right-click it (item menu on movies and episodes)
finimal-verify xy <x> <y> [button]   click at screen coordinates (icon buttons have no text)
finimal-verify move <x> <y>          move the mouse without clicking (shows the player controls)
finimal-verify key <keysym>...       xdotool key, e.g. Escape, space, Page_Down, Right
finimal-verify type <text>           type into the focused field
finimal-verify shot <name>           evidence/<name>.png plus evidence/<name>.txt (its OCR)
finimal-verify api <path>            GET <server><path> as the run's user (side-effect checks)
finimal-verify env                   the run's PIDs, display, user id, token, evidence dir
finimal-verify stop                  kill this run's app and Xvfb, delete scratch, keep evidence
```

Driving rules:

- Text matching is case-sensitive and substring-based. OCR reads `0` as `O` and adds stray marks (`‘Subs Test (2022)`, `<€ Movies` for the back arrow). Match a distinctive fragment such as `Subs Test`, not a full label with digits.
- Icon-only buttons have no text. With the 1280x800 window that a fresh config opens at (160,100), the Libraries bar has Settings (gear) at `1364 132` and the account avatar at `1412 132`. A screen's back arrow sits at `188 132`; `key Escape` also goes back.
- In the player, a click toggles pause; `move` shows the controls without a click. Use keys: `space` pause, `Left`/`Right` seek, `Page_Down`/`Page_Up` chapters, `Escape` leaves. The bottom line reads `0:21 / 2:00  (buffered 2:00)`; OCR often drops the time over video, so wait for `buffered`.
- After a click, `wait` for the next screen's text instead of sleeping a fixed time.

## Evidence

Everything goes to `~/.cache/finimal-verify/<run-id>/evidence/`, and `stop` keeps it: `app.log` (stdout and stderr of the app), `mpv.log` (mpv's verbose log once something plays), and the `shot` pairs of `<name>.png` and `<name>.txt`.

Proof standards:

- Drive the real user path: clicks and keys in the app, never seeded settings or API writes in place of the action under test. `launch`'s seeded login is setup, not proof of login.
- Capture the action and the resulting state: a `shot` before and after the action, not only the final screen.
- Verify the side effect on the server with `api` (for example `/Users/<USER_ID>/Items/<id>` and its `.UserData`) next to what the screen shows, and save that output into the evidence dir.
- Mocks have no place here. For outages, put a throttling proxy between the app and the server (`FV_SERVER=http://localhost:8098`); `docker pause` does not work because the server buffers the whole file in seconds.
- Name the feature and sub-feature ID from `features/` with each artifact.

## Cleanup

```sh
$V stop
```

Kills only the app and Xvfb PIDs recorded at launch (never by name; a hook blocks `pkill -f`), deletes the run's `scratch/` (settings, caches, OCR temp files) and `run.env`, and lists the evidence it kept. Run it after every attempt, failed ones too. Then restore any server state the journey changed (for example **Mark played** again), as the feature file says.

## Secondary surface: Android

The Android app (`app/`) shares the screens. Verify it only on the AVD `plainfin`, addressed by serial (`adb -s emulator-<port>`), never on the physical phone. Build with `./gradlew assembleDebug`; APKs go to `app/build/outputs/apk/debug/`. From the emulator, the test server is `http://10.0.2.2:8097`. This skill has no Android helper yet; the feature map describes the desktop paths only.
