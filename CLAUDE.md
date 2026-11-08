# FINMAX – notes for working on this repo

Native Android TV Jellyfin client (Kotlin, Compose for TV, Media3, Jellyfin SDK 1.7.1). README.md has the architecture and feature status.

## Build

- Needs JDK 17 and an Android SDK with platform 36 (`ANDROID_HOME` or `local.properties` with `sdk.dir`).
- `./gradlew :app:testDebugUnitTest` runs the subtitle sync tests (pure JVM, no device needed).
- `./gradlew :app:assembleRelease` for anything that will run on the TV; debug Compose is too slow on the target.

## Gotchas learned on the device

- Every Jellyfin SDK call must go through `IoApiClientFactory` (wired in `FinmaxApp`). The SDK's OkHttp client reads bodies on the caller thread, which throws `NetworkOnMainThreadException` from Compose scopes.
- SDK local discovery must be collected with `flowOn(Dispatchers.IO)`, or the app ANRs.
- `PlayerView` must stay non-focusable, or it swallows D-pad keys.
- Tall headers (Home spotlight, details) use custom `BringIntoViewSpec`s (`NoAutoScroll`, `MinimalScroll`): the TV default "pivot" scrolling pushes them off screen.
- Write source files as UTF-8 with LF line endings.

## Test environment (owner's home network, not reachable from the cloud)

- Target: Chromecast with Google TV 4K (Android 14, armeabi-v7a, 2 GB). Over ADB, screenshots don't capture the video layer; inspect the player with `uiautomator dump`.
- Jellyfin server: 12.4 on the LAN. No Bazarr instance has been tested yet.
- Device testing needs the owner's PC. Cloud work should be limited to code, unit tests and builds.

## Reference

- `reference/tv-photos/`: photos of the owner's TV showing the browsing experience they like (Netflix). These are inspiration for the layout; the app has its own design.
- Wholphin (https://github.com/damontecres/Wholphin) is the functional reference.
