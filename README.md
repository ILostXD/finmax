<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/finmax_logo_white.png">
  <img src="assets/finmax_logo_black.png" alt="finmax" width="420">
</picture>

**A Jellyfin client for Android TV.**

[![Latest release](https://img.shields.io/github/v/release/ILostXD/finmax?style=flat-square&color=7E49B3&label=release)](https://github.com/ILostXD/finmax/releases/latest)
[![License](https://img.shields.io/github/license/ILostXD/finmax?style=flat-square&color=000000)](LICENSE)
![Android TV 8.0+](https://img.shields.io/badge/Android%20TV-8.0%2B-000000?style=flat-square&logo=androidtv)
![Jellyfin 10.10+](https://img.shields.io/badge/Jellyfin-10.10%2B-7E49B3?style=flat-square&logo=jellyfin&logoColor=white)

[Download](https://github.com/ILostXD/finmax/releases/latest) · [Installation](#installation) · [Features](#features) · [Building](#building)

<br>

<img src="docs/screenshots/home.jpg" alt="finmax home screen" width="88%">

</div>

> [!NOTE]
> finmax is in alpha. Please report problems in [Issues](https://github.com/ILostXD/finmax/issues).

## Features

**Browsing**
- Featured carousel and Netflix-style rows, with previews that play when a card stays focused and continue in the player without restarting.
- Details pages with background clips, full cast and crew, seasons and episodes, and a page for each cast member.
- Continue Watching and Next Up, optionally merged into one row. Home rows can be reordered and hidden.
- Movies and shows with sorting, filters and genres; collections, playlists and My List.

**Playback**
- Direct play wherever the device can decode the file; the server converts only what it can't.
- Two engines: ExoPlayer, and mpv for styled ASS/SSA subtitles, selected automatically.
- Refresh-rate matching, episode queue, skipping intros, recaps and credits, trickplay thumbnails.
- AC3, E-AC3 and DTS audio decoded on the device when the TV has no decoder for it.

**Subtitles**
- Delay adjustment during playback, remembered per title and track.
- A sync assistant that detects constant offsets, frame-rate mismatches and different cuts, and suggests better-matching subtitles from Jellyfin or Bazarr.
- Size, position, background and drop shadow options.

**Also included**
- Discover: browse, search and request titles through Seerr or Jellyseerr.
- Profiles with optional PIN locks.
- Screensaver, in the app and as the Android TV screensaver.
- In-app updates from GitHub releases.
- Quick Connect sign-in and local server discovery.

## Screenshots

<table>
<tr>
<td><img src="docs/screenshots/details.jpg" alt="Movie details"></td>
<td><img src="docs/screenshots/home-rows.jpg" alt="Home rows"></td>
</tr>
<tr>
<td><img src="docs/screenshots/series.jpg" alt="Series details"></td>
<td><img src="docs/screenshots/episodes.jpg" alt="Episodes"></td>
</tr>
<tr>
<td><img src="docs/screenshots/cast.jpg" alt="Cast and similar titles"></td>
<td><img src="docs/screenshots/settings.jpg" alt="Subtitle settings"></td>
</tr>
</table>

## Installation

1. Install **Downloader** (by AFTVnews) from the Google Play Store on the TV.
2. Allow it to install apps: *Settings → Apps → Security & restrictions → Unknown sources*.
3. In Downloader, enter:
   ```
   https://github.com/ILostXD/finmax/releases/latest/download/finmax.apk
   ```
4. Install the app, open it and sign in to your Jellyfin server.

New versions can be installed from *Settings → About & updates*.

With ADB:

```bash
adb install -r finmax.apk
```

## Requirements

- Android TV or Google TV, Android 8.0 or newer
- Jellyfin server 10.10 or newer
- Optional: Seerr or Jellyseerr for Discover, Bazarr for subtitle downloads

## Building

Requires JDK 17 and the Android SDK (platform 36).

```bash
./gradlew :app:assembleRelease
```

```bash
./gradlew :app:testDebugUnitTest
```

Use release builds on TV hardware; debug builds are considerably slower.

## Built with

Kotlin, Jetpack Compose for TV, Media3, [libmpv-android](https://github.com/jarnedemeulemeester/libmpv-android), the [Jellyfin Kotlin SDK](https://github.com/jellyfin/jellyfin-sdk-kotlin), Coil and OkHttp. [Wholphin](https://github.com/damontecres/Wholphin) served as the functional reference.

finmax is not affiliated with Jellyfin, Netflix or Seerr.

## License

[GPL-3.0](LICENSE)
