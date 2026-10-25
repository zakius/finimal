# finimal

A Jellyfin client for Android that goes straight to your movie and TV libraries. It prefers a few extra taps over convenience features that break.

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
- No Google Play Services, so it runs on Huawei/EMUI devices.

Not included: Android TV, background audio, picture-in-picture, Quick Connect, and home rows.

## Install

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

## Build

You need JDK 21 and the Android SDK.

```sh
./gradlew :core:test assembleDebug
```

Debug APKs go to `app/build/outputs/apk/debug/`, one per ABI. Local release builds are unsigned. CI signs the release builds when a `v*` tag is pushed.

## License

GPL-3.0. See [LICENSE](LICENSE).
