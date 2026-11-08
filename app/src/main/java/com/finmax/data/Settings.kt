package com.finmax.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SegmentAction { ASK, AUTO, OFF }

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
    val detailVideoSound: Boolean = false,
)

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
            .putBoolean("detailVideoSound", s.detailVideoSound)
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
            detailVideoSound = prefs.getBoolean("detailVideoSound", d.detailVideoSound),
        )
    }
}
