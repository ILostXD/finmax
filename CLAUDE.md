# finmax – notes for working on this repo

Native Android TV Jellyfin client (Kotlin, Compose for TV, Media3, Jellyfin SDK 1.7.1). README.md has the architecture and feature status.

## Build

- Needs JDK 17 and an Android SDK with platform 36 (`ANDROID_HOME` or `local.properties` with `sdk.dir`).
- `./gradlew :app:testDebugUnitTest` runs the subtitle sync tests (pure JVM, no device needed).
- `./gradlew :app:assembleRelease` for anything that will run on the TV; debug Compose is too slow on the target.
- `./gradlew :app:testDebugUnitTest -Pscreenshots` renders every page at 1080p into `app/build/screenshots/` (Robolectric + Roborazzi). `AppScreenshots` runs the whole app against `FakeJellyfin` (an OkHttp interceptor serving an invented library and generated art) and walks it with D-pad keys; `DesignScreenshots` renders Home and details from hand-built state. Use them to check layout from the cloud. They can't show video, animation timing or the player's picture.
- Screenshot-test constraints: Robolectric's native graphics can't draw or decode off the main thread (so art is pre-rendered and Coil decodes on Main with `imageDecoderEnabled(false)`), and ExoPlayer never lets Compose go idle (background video is turned off, and the player is captured last with a hand-driven clock).
- In cloud sessions the wrapper's distribution download can time out; a local Gradle 8.14.3 works the same. Maven Central often answers 429 there: pass an init script (`-I`) that adds `https://maven-central.storage-download.googleapis.com/maven2` to `pluginManagement` and `dependencyResolutionManagement` repositories in `beforeSettings`.

## Gotchas learned on the device

- Every Jellyfin SDK call must go through `IoApiClientFactory` (wired in `FinmaxApp`). The SDK's OkHttp client reads bodies on the caller thread, which throws `NetworkOnMainThreadException` from Compose scopes.
- SDK local discovery must be collected with `flowOn(Dispatchers.IO)`, or the app ANRs.
- `PlayerView` must stay non-focusable, or it swallows D-pad keys.
- Tall headers (details) use custom `BringIntoViewSpec`s (`NoAutoScroll`, `MinimalScroll`): the TV default "pivot" scrolling pushes them off screen.
- Write source files as UTF-8 with LF line endings.

## Test environment (owner's home network, not reachable from the cloud)

- Target: Chromecast with Google TV 4K (Android 14, armeabi-v7a, 2 GB). Over ADB, screenshots don't capture the video layer; inspect the player with `uiautomator dump`.
- Jellyfin server: 12.4 on the LAN. No Bazarr instance has been tested yet.
- Device testing needs the owner's PC. Cloud work should be limited to code, unit tests and builds.

## Design

- Branding is always lowercase: **finmax**.
- Palette (Nox Noir `#141414`, Royal Amethyst `#7E49B3`, Steel Mist `#CFCFCF`), type and shared components: `ui/theme/Theme.kt`, `ui/components/Components.kt`. Use the `Fm` tokens (`Focus`/`OnFocus` for focused fills, `Accent` for amethyst text and rings), never raw colours.
- Artwork pages use `GlassBackdrop`: sharp art on the right dissolving into a blurred, tinted copy under the text on the left (no dark gradients). Panels over art use `GlassPanelBackground`. Controls are pills (`ButtonShape`), cards are 14 dp rounded (`CardShape`).
- Logo sources with transparency are in `assets/` (`finmax_logo_black.png`, `finmax_logo_small_black.png`); the res/ wordmark, mark, banner and launcher icons are generated from them.
- Details page scrolling is explicit (`NoAutoScroll` on the column, sections snap by key order, rows inside use `MinimalScroll`).
- `AmbientVideo` (details background) must stay a `TextureView` that is never focusable, and must only hold a player while its page is the top of the back stack, so it never competes with the real player for the decoder.

## Reference

- `reference/tv-photos/`: photos of the owner's TV showing the browsing experience they like (Netflix). These are inspiration for the layout; the app has its own design.
- `reference/inspiration/`: the two layouts the redesign follows. `home-dune.jpg` drives Home (title and actions on the left over frosted glass, art on the right, a vertical list on Right); `details-joker.jpg` drives details (big title over full-bleed art; the fact column and section rail were dropped in favour of an inline facts line). Social links and site navigation in them are not part of the app.
- Wholphin (https://github.com/damontecres/Wholphin) is the functional reference.
