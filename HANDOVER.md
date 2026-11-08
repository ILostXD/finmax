# Handover: FINMAX redesign

Branch: `claude/app-redesign-netflix-style-faxh8n` (pushed). Read `CLAUDE.md` first; it has build commands, design rules and gotchas.

## Done (cloud session, not yet tried on the TV)
- New brand: palette (Chlorophyll Void / Taut Orchid / Ionized Parchment) in `ui/theme/Theme.kt`, Bricolage Grotesque font (titles weight 600 like the logo), logo/banner/icon from `reference/brand/`.
- Home (`ui/home/HomeScreen.kt`): vertical carousel of random titles (Up/Down, auto-advance 10 s, Back/Up-on-first → top bar). Right from the last button slides art aside and opens a numbered vertical recommendations panel; Left/Back closes. Inspired by `reference/inspiration/home-dune.jpg`.
- Details (`ui/detail/DetailScreen.kt`): artwork, then local trailer or muted movie clips fade in (`ui/components/AmbientVideo.kt`, setting under Settings → Details pages). Big title, glass fact column, Watch Trailer tile, numbered sections below. Inspired by `details-joker.jpg`.
- Fixes: sign-in negative-padding crash, sign-in tiles off screen, detail sections snap on focus.
- Previews: `./gradlew :app:testDebugUnitTest -Pscreenshots` → `app/build/screenshots/app/` (fake server, 25 pages).

## Next: verify on the TV
1. `./gradlew :app:assembleRelease` then `adb install -r app/build/outputs/apk/release/app-release.apk`.
2. Check what previews couldn't: background video on details (starts ~2.5 s, fades in, stops when opening the player, no decoder conflict), carousel slide animations, Right/Left panel transitions and focus landing, smoothness on the 2 GB Chromecast.
3. Check focus edge cases: Back on Home focuses top bar, Up on first slide, returning from details restores focus (also inside the panel).
4. Fix what's off; update README "Feature status" once verified.
