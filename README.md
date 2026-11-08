# FINMAX

A native Android TV client for Jellyfin: cinematic browsing, reliable ExoPlayer playback, and a subtitle experience built around fixing out-of-sync subtitles.

## Build and install

```bash
./gradlew :app:assembleRelease
```

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Use the release build on the TV. The debug build is unminified Compose and is several times slower on low-end devices like the Chromecast with Google TV. Release is signed with the local debug key for now.

```bash
./gradlew :app:testDebugUnitTest
```

## Stack

Kotlin, Jetpack Compose for TV (`tv-material`), Media3 ExoPlayer (+HLS, OkHttp data source, MediaSession), official Jellyfin Kotlin SDK 1.7.1, Coil 3, OkHttp. No DI framework and no navigation library: a small app container (`FinmaxApp`) and a back stack (`ui/Navigator.kt`) keep the dependency count down.

## Layout

| Path | What it does |
|---|---|
| `data/` | Sessions, servers and users (`SessionManager`), all library calls (`MediaRepository`), Keystore-encrypted secrets (`SecureStore`), settings, and `IoApiClient`, which moves every SDK request off the main thread (the SDK's OkHttp backend reads bodies on the caller thread). |
| `playback/` | Device codec/passthrough detection and the Jellyfin `DeviceProfile`; `PlayerController` owns ExoPlayer, the play decision, track mapping, progress reporting, segments and episode navigation. |
| `subtitles/` | SRT/VTT/ASS parser and timing model, `SyncAnalyzer` (alignment, unit tested), `SyncAssistant`, `SubtitleSources` (server subtitles, remote search, remembered corrections), `BazarrClient`. |
| `ui/` | Screens (login, home, browse, search, details, player, settings, profiles) and shared components and theme. |

## Design

The look is built from the brand card in `reference/brand/` (palette, the finmax wordmark and "f" mark) and the two layouts in `reference/inspiration/`.

- **Palette** (`ui/theme/Theme.kt`): Chlorophyll Void `#1B3100` is the base everything sits in; Taut Orchid `#8D3F7D` marks primary actions and the focus glow; Ionized Parchment `#FFF2C5` is the text colour and the focus fill.
- **Type**: Bricolage Grotesque, the logo's typeface (variable, bundled in `res/font`, OFL). Titles use the display optical size at weight 600 like the logo; reading text uses the text optical size; labels are small tracked capitals.
- **Home** is a vertical carousel of random titles: artwork on the left, title, facts and actions on the right. Up/Down moves through it (it also advances every 10 s); Up on the first slide reaches the top bar, and so does Back. Right from the last button slides the artwork aside and opens the recommendations panel: Continue Watching, Next Up, new arrivals, My List, "Because you watched", collections and genre picks as one numbered vertical list. The focused title is described over its own artwork. Left or Back closes it.
- **Details** show the artwork first, then fade in the title's local trailer or, if it has none, short muted clips of the film itself from random points (Settings → Details pages; off, trailers only, or trailers and clips, with optional sound). The header has the title, actions and a glass fact column (premiere, director, writers, music, genre, studio, runtime with end time) with a Watch Trailer tile. Below the fold come numbered sections: episodes, cast, more like this and file details.
- **Screenshots without a TV**: `./gradlew :app:testDebugUnitTest -Pscreenshots` renders Home and details with a fake library into `app/build/screenshots/`.

## Feature status

Tested on the target device (Chromecast with Google TV 4K, Android 14) against the Jellyfin 12.4 server.

The redesigned Home and details screens (see Design) haven't been tried on the TV yet; the behaviour below was verified with the previous layout.

**Implemented and verified on the TV**
- Server discovery (UDP) and manual address, Quick Connect sign-in, remembered sessions, token encrypted with the Android Keystore.
- Home: featured titles, Continue Watching, Next Up, Recently Added per library, My List, "Because you watched" recommendations, collections, random genre picks, recently watched, libraries.
- Movies and TV Shows grids with sort, filter (unwatched, watched, My List, in progress) and genre; paged loading.
- Search with an on-screen TV keyboard.
- Details: logo, ratings, critic score, tagline, cast and crew, tech badges, resume progress, seasons and episodes, More like this, collection contents, My List, mark watched.
- Playback: Direct Play, falling back to server Direct Stream/Transcode when the server says so or the device fails to decode. Resume, progress reporting, skip recap/intro/credits from Jellyfin media segments, previous/next episode, D-pad seek, media keys, captions key.
- Playback info panel: method (including "video untouched, audio converted"), codecs, resolution, tracks, renderer, transcode reasons from the server session.
- Subtitles: delay ±step/±1 s/reset, live and remembered per title and subtitle; sync assistant (see below); style (size, background, position).

**Implemented, not yet exercised on this server**
- Password login, profile and server switching, sign-out.
- Audio track switching (direct play maps tracks in place; transcoded streams restart at the same position).
- Up Next card with countdown and "Still watching?" after 3 autoplays.
- Remote subtitle search and preview through Jellyfin's subtitle providers. Your user returned 403: enable **Allow subtitle management** for it in Dashboard → Users, and install a provider plugin such as OpenSubtitles.
- Bazarr: provider search, download, and audio sync (ffsubsync). The API was verified against Bazarr's source, but no Bazarr instance was available to test against.
- Image subtitles (PGS/VobSub): embedded for direct play, burned in when transcoding.

**Planned or not supported**
- Music, Live TV, photos and books libraries are hidden, not faked.
- Playlists, a per-user home layout editor, PIN-protected profiles, trickplay thumbnails, refresh-rate switching, MPV backend and theme music (all Wholphin features) aren't built yet.
- On-device audio-based sync: too heavy for TV hardware. Bazarr's server-side ffsubsync covers it.
- ASS styling: ASS/SSA text is rendered as plain text so it can be retimed.

## Subtitle sync assistant

FINMAX downloads every text subtitle from Jellyfin as SRT and renders it itself, so timing can be corrected live without restarting playback. The correction is linear: `shown = t × scale + offset`.

"Fix subtitle sync…" in the player's subtitle panel:
1. Reads the file name, frame rate, runtime and the selected subtitle.
2. Compares dialogue timing against a reference track. Embedded tracks are trusted. Another external file is used only as a labelled relative guide.
3. Classifies the problem:
   - **constant offset**: a delay fixes it
   - **frame-rate mismatch**: needs a speed correction, e.g. 23.976↔25 fps
   - **different cut**: offsets jump between sections, so it says no delay can fix it
   - **unknown**
4. Ranks alternatives: other tracks, server providers (a hash match counts strongly), and Bazarr. It uses release-name and edition matching and, when a trusted reference exists, measured alignment.
5. Nothing changes until you choose Preview and then Apply. Downloads happen only on Apply.
6. **Sync to a line**: press OK as a listed line is spoken. One point fixes the delay; two points far apart also fix drift.

## Known limitations

- On this TV no AC3/E-AC3 output is advertised, so Dolby audio is converted to AAC by the server. Video stays untouched.
- When the file's default audio track isn't the language you want (one episode defaulted to Hindi), set **Settings → Preferred audio language**.
- Screenshots over ADB don't capture the video layer on this device; player UI checks used the accessibility tree.
