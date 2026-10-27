# finimal

A Jellyfin client for Android, Linux and Windows that goes straight to your movie and TV libraries. It prefers a few extra taps over convenience features that break.

## Why

Other clients sometimes restart a series from episode 1. The cause is the server's Next Up and Resume logic, combined with stop reports that carry no position. finimal never reads Next Up or Resume. It keeps play state locally, pushes it before every list load, and the newer change wins. [docs/design.md](docs/design.md#sync) has the full rules.

## Features

- Libraries → poster grid → series → seasons → episodes, with sort, search and an unplayed-only filter.
- A Continue button on each series, from this device's play state, never the server's Next Up.
- Direct play through Media3, with an FFmpeg audio decoder and libass for ASS/SSA subtitles. The app transcodes only when the device can't decode the file.
- Buffers to the end of the episode on disk, so a weak connection doesn't stall playback.
- Downloads with a queue, pause and resume, SD card storage, and "keep next N episodes downloaded" per series.
- Offline playback and progress sync once the server is reachable again. Lists open instantly from a saved copy, even offline.
- Playback rides out network drops: it reconnects and continues from the same spot.
- A wireless audio delay setting to match Bluetooth headphones and speakers.
- Fullscreen gestures for brightness, volume, seek, double-tap skip and pinch to crop. Each one can be turned off.
- A "Skip intro" button from the server's media segments. It never skips on its own.
- Android TV and TV boxes: the remote drives everything, and the player takes D-pad and media keys. Picked on its own on Android TV and on boxes without a touchscreen; Settings can override it.
- No Google Play Services, so it runs on Huawei/EMUI devices.

Not included: background audio, picture-in-picture, Quick Connect, and home rows.

## Install

### Android

Download the APK for your device from [Releases](https://github.com/zakius/finimal/releases):

| File | Devices |
|------|---------|
| `finimal-*-arm64-v8a.apk` | Almost every phone and tablet from the last several years |
| `finimal-*-armeabi-v7a.apk` | Older or low-end 32-bit ARM devices, many TV boxes |
| `finimal-*-x86_64.apk` | Emulators, Chromebooks and x86 tablets |
| `finimal-*-x86.apk` | Old 32-bit x86 devices |

If you are not sure, try `arm64-v8a` first. When Android says the app isn't compatible, use `armeabi-v7a`.

For updates, add `https://github.com/zakius/finimal` to [Obtainium](https://github.com/ImranR98/Obtainium). It picks the APK that matches your device; if it asks, choose with the table above.

Android 8.0 (API 26) or later is required.

### Linux

The desktop app has the same screens and plays through mpv. [Releases](https://github.com/zakius/finimal/releases) has three packages for x86_64:

| File | For |
|------|-----|
| `finimal_*_amd64.deb` | Debian 13, Ubuntu 24.04 and newer: `sudo apt install ./finimal_*_amd64.deb` |
| `finimal-*.x86_64.rpm` | Fedora: `sudo dnf install ./finimal-*.x86_64.rpm` |
| `finimal-*-x86_64.flatpak` | Any distribution with Flatpak: `flatpak install --user finimal-*-x86_64.flatpak` |

The .deb and .rpm run on your distribution's Java (21 or newer) and libmpv, so their updates come with the system's. The Flatpak carries its own.

### Windows

[Releases](https://github.com/zakius/finimal/releases) has `finimal-*-x64.msi`, an installer for your user that needs no administrator rights, and `finimal-*-windows-x64.zip`, the same app to unpack and run (`finimal.exe`). Both are 64-bit.

These two are small: finimal runs on your Java 21 or newer and on the `libmpv-2.dll` in `%APPDATA%\finimal`. Whatever is missing, the first start downloads, about 80 MB: [Eclipse Temurin](https://adoptium.net)'s Java to `%LOCALAPPDATA%\finimal` and mpv to `%APPDATA%\finimal`. For a computer without internet access, `finimal-*-x64-full.msi` and `finimal-*-windows-x64-full.zip` include both.

To use a newer mpv, put its `libmpv-2.dll` in `%APPDATA%\finimal`.

### On the desktop

In the player: Space or K plays and pauses, Left and Right (or J and L) skip, Up, Down and the mouse wheel set the volume, M mutes, F or a double-click toggles fullscreen, N plays the next episode and Esc leaves fullscreen, then the player. Right-click an episode or movie for its menu. There are no downloads on the desktop yet.

## Build

You need JDK 21 and the Android SDK.

```sh
./gradlew :core:test assembleDebug
```

Debug APKs go to `app/build/outputs/apk/debug/`, one per ABI. `tools/release.sh` builds the signed release APKs into `dist/`, with the baseline profile embedded so Android 15+ compiles the app at install; it reads the key from `keystore.properties`. CI runs it when a `v*` tag is pushed.

The desktop app (Linux now, Windows later) shares the Android app's screens and plays through libmpv:

```sh
./gradlew :desktop:run          # needs libmpv installed (libmpv2 on Debian and Ubuntu)
./gradlew :desktop:test         # player tests against the installed libmpv
tools/flatpak.sh                # the Linux Flatpak into dist/, built in Docker; libmpv is built inside
```

## License

GPL-3.0. See [LICENSE](LICENSE).
