package com.finmax.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SegmentAction { ASK, AUTO, OFF }

/** Shadow under subtitle text (without the background box). */
enum class SubShadow(val label: String) {
    NONE("None"),
    SOFT("Soft glow"),
    DROP("Drop shadow"), // Netflix's: offset down-right, so letters lift off bright scenes
}

/** Which player plays: mpv draws styled (ASS/SSA) subtitles properly, ExoPlayer is lighter and smoother. */
enum class PlayerEngine(val label: String) {
    AUTO("Automatic"),
    EXO("ExoPlayer"),
    MPV("mpv"),
}

/** What plays behind a details page once it has settled. */
enum class DetailVideo(val label: String) {
    OFF("Off"),
    TRAILERS("Trailers only"),
    CLIPS("Trailers and movie clips"),
}

data class AppSettings(
    val maxBitrateMbps: Int = 0, // 0 = unlimited, let the server decide by profile
    val autoplayNext: Boolean = true,
    val autoplayCountdownSec: Int = 10,
    val skipIntro: SegmentAction = SegmentAction.ASK,
    val skipCredits: SegmentAction = SegmentAction.ASK,
    val resumeRewindSec: Int = 0,
    val subtitleScale: Float = 1f,
    val subtitleBackground: Boolean = false,
    val subtitleBottomPct: Int = 8,
    val subtitleDelayStepMs: Int = 100,
    val preferredSubtitleLang: String = "",
    val preferredAudioLang: String = "",
    val homeContinue: Boolean = true,
    val homeNextUp: Boolean = true,
    val homeLatest: Boolean = true,
    val homeFavorites: Boolean = true,
    val homeCollections: Boolean = true,
    val homeGenres: Boolean = true,
    val bazarrUrl: String = "",
    val seerrUrl: String = "",
    val detailVideo: DetailVideo = DetailVideo.CLIPS,
    val detailVideoSound: Boolean = true,
    /** Home hero: title and buttons on the right over mirrored art (true) or on the left. */
    val heroRight: Boolean = true,
    /** The TV switches to the film's frame rate while it plays. */
    val matchFrameRate: Boolean = true,
    /** Next Up episodes join Continue Watching in one row. */
    val homeMerge: Boolean = false,
    val homePlaylists: Boolean = true,
    /** Home row groups top to bottom ([HomeRows] keys); groups missing here keep their default place. */
    val homeOrder: List<String> = HomeRows.keys,
    /** Minutes of no input before the screensaver; 0 = off. */
    val screensaverMin: Int = 5,
    /** Home groups without their own switch that the user turned off. */
    val homeHidden: Set<String> = emptySet(),
    val playerEngine: PlayerEngine = PlayerEngine.AUTO,
    val subtitleShadow: SubShadow = SubShadow.SOFT,
) {
    /** Whether a Home row group ([HomeRows] key) is shown. */
    fun rowOn(k: String) = when (k) {
        "resume" -> homeContinue
        "nextup" -> homeNextUp && !homeMerge
        "latest" -> homeLatest
        "fav" -> homeFavorites
        "collections" -> homeCollections
        "genrepicks" -> homeGenres
        "playlists" -> homePlaylists
        else -> k !in homeHidden
    }

    fun toggleRow(k: String) = when (k) {
        "resume" -> copy(homeContinue = !homeContinue)
        "nextup" -> copy(homeNextUp = !homeNextUp)
        "latest" -> copy(homeLatest = !homeLatest)
        "fav" -> copy(homeFavorites = !homeFavorites)
        "collections" -> copy(homeCollections = !homeCollections)
        "genrepicks" -> copy(homeGenres = !homeGenres)
        "playlists" -> copy(homePlaylists = !homePlaylists)
        else -> copy(homeHidden = if (k in homeHidden) homeHidden - k else homeHidden + k)
    }
}

/** Home row groups that can be reordered, in their default order. */
object HomeRows {
    val labels = linkedMapOf(
        "genres" to "Genres", "nextwatch" to "Your Next Watch", "resume" to "Continue Watching", "nextup" to "Next Up",
        "latest" to "Recently added", "fav" to "My List", "playlists" to "Playlists", "collections" to "Collections",
        "genrepicks" to "Genre picks", "watched" to "Watched Recently", "libraries" to "Your Libraries",
    )
    val keys = labels.keys.toList()

    /** [saved] in order, then any group it doesn't know yet, at its default place. */
    fun normalized(saved: List<String>): List<String> {
        val out = saved.filter { it in labels }.distinct().toMutableList()
        keys.forEachIndexed { i, k -> if (k !in out) out.add(i.coerceAtMost(out.size), k) }
        return out
    }
}

/** Non-secret app preferences. Secrets (Bazarr API key, tokens) live in [SecureStore]. */
class SettingsStore(context: Context, private val secure: SecureStore) {
    private val prefs = context.getSharedPreferences("finmax_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val current get() = _state.value

    var seerrApiKey: String?
        get() = secure.get("seerr_key")
        set(value) = secure.put("seerr_key", value?.takeIf { it.isNotBlank() })

    var bazarrApiKey: String?
        get() = secure.get("bazarr_key")
        set(value) = secure.put("bazarr_key", value?.takeIf { it.isNotBlank() })

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        _state.value = s
        prefs.edit()
            .putInt("maxBitrate", s.maxBitrateMbps)
            .putBoolean("autoplayNext", s.autoplayNext)
            .putInt("countdown", s.autoplayCountdownSec)
            .putString("skipIntro", s.skipIntro.name)
            .putString("skipCredits", s.skipCredits.name)
            .putInt("resumeRewind", s.resumeRewindSec)
            .putFloat("subScale", s.subtitleScale)
            .putBoolean("subBg", s.subtitleBackground)
            .putInt("subBottom", s.subtitleBottomPct)
            .putInt("subStep", s.subtitleDelayStepMs)
            .putString("subLang", s.preferredSubtitleLang)
            .putString("audioLang", s.preferredAudioLang)
            .putBoolean("hContinue", s.homeContinue)
            .putBoolean("hNextUp", s.homeNextUp)
            .putBoolean("hLatest", s.homeLatest)
            .putBoolean("hFav", s.homeFavorites)
            .putBoolean("hColl", s.homeCollections)
            .putBoolean("hGenres", s.homeGenres)
            .putString("bazarrUrl", s.bazarrUrl)
            .putString("seerrUrl", s.seerrUrl)
            .putString("detailVideo", s.detailVideo.name)
            .putBoolean("detailVideoSound2", s.detailVideoSound)
            .putBoolean("heroRight", s.heroRight)
            .putBoolean("matchFps", s.matchFrameRate)
            .putBoolean("hMerge", s.homeMerge)
            .putBoolean("hPlaylists", s.homePlaylists)
            .putString("hOrder", s.homeOrder.joinToString(","))
            .putInt("saverMin", s.screensaverMin)
            .putStringSet("hHidden", s.homeHidden)
            .putString("engine", s.playerEngine.name)
            .putString("subShadow", s.subtitleShadow.name)
            .apply()
    }

    private fun load(): AppSettings {
        val d = AppSettings()
        fun seg(k: String, def: SegmentAction) =
            runCatching { SegmentAction.valueOf(prefs.getString(k, def.name)!!) }.getOrDefault(def)
        return AppSettings(
            maxBitrateMbps = prefs.getInt("maxBitrate", d.maxBitrateMbps),
            autoplayNext = prefs.getBoolean("autoplayNext", d.autoplayNext),
            autoplayCountdownSec = prefs.getInt("countdown", d.autoplayCountdownSec),
            skipIntro = seg("skipIntro", d.skipIntro),
            skipCredits = seg("skipCredits", d.skipCredits),
            resumeRewindSec = prefs.getInt("resumeRewind", d.resumeRewindSec),
            subtitleScale = prefs.getFloat("subScale", d.subtitleScale),
            subtitleBackground = prefs.getBoolean("subBg", d.subtitleBackground),
            subtitleBottomPct = prefs.getInt("subBottom", d.subtitleBottomPct),
            subtitleDelayStepMs = prefs.getInt("subStep", d.subtitleDelayStepMs),
            preferredSubtitleLang = prefs.getString("subLang", d.preferredSubtitleLang)!!,
            preferredAudioLang = prefs.getString("audioLang", d.preferredAudioLang)!!,
            homeContinue = prefs.getBoolean("hContinue", d.homeContinue),
            homeNextUp = prefs.getBoolean("hNextUp", d.homeNextUp),
            homeLatest = prefs.getBoolean("hLatest", d.homeLatest),
            homeFavorites = prefs.getBoolean("hFav", d.homeFavorites),
            homeCollections = prefs.getBoolean("hColl", d.homeCollections),
            homeGenres = prefs.getBoolean("hGenres", d.homeGenres),
            bazarrUrl = prefs.getString("bazarrUrl", d.bazarrUrl)!!,
            seerrUrl = prefs.getString("seerrUrl", d.seerrUrl)!!,
            detailVideo = runCatching { DetailVideo.valueOf(prefs.getString("detailVideo", d.detailVideo.name)!!) }.getOrDefault(d.detailVideo),
            // v2 key: the default became on, and the old key held the old default.
            detailVideoSound = prefs.getBoolean("detailVideoSound2", d.detailVideoSound),
            heroRight = prefs.getBoolean("heroRight", d.heroRight),
            matchFrameRate = prefs.getBoolean("matchFps", d.matchFrameRate),
            homeMerge = prefs.getBoolean("hMerge", d.homeMerge),
            homePlaylists = prefs.getBoolean("hPlaylists", d.homePlaylists),
            homeOrder = HomeRows.normalized(prefs.getString("hOrder", null)?.split(',') ?: d.homeOrder),
            screensaverMin = prefs.getInt("saverMin", d.screensaverMin),
            homeHidden = prefs.getStringSet("hHidden", d.homeHidden)!!.toSet(),
            subtitleShadow = runCatching { SubShadow.valueOf(prefs.getString("subShadow", d.subtitleShadow.name)!!) }.getOrDefault(d.subtitleShadow),
            playerEngine = runCatching { PlayerEngine.valueOf(prefs.getString("engine", d.playerEngine.name)!!) }.getOrDefault(d.playerEngine),
        )
    }
}
