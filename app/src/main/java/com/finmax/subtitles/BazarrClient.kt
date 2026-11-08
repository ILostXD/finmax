package com.finmax.subtitles

import com.finmax.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** A Bazarr-side reference to the video playing in FINMAX. */
sealed interface BazarrMedia {
    val subtitles: List<BazarrSubtitle>
    data class Movie(val radarrId: Int, override val subtitles: List<BazarrSubtitle>) : BazarrMedia
    data class Episode(val seriesId: Int, val episodeId: Int, override val subtitles: List<BazarrSubtitle>) : BazarrMedia
}

data class BazarrSubtitle(val path: String?, val code2: String, val name: String, val forced: Boolean, val hi: Boolean)

data class BazarrCandidate(
    val provider: String,
    val subtitleId: String,
    val language: String,
    val releaseInfo: List<String>,
    val score: Int,
    val hearingImpaired: Boolean,
    val forced: Boolean,
    val originalFormat: Boolean,
    val matches: List<String>,
    val dontMatches: List<String>,
)

/**
 * Optional Bazarr integration (API verified against bazarr/api in the Bazarr repo):
 *  - auth: `X-API-KEY` header
 *  - GET  /api/movies, /api/series, /api/episodes?seriesid[]=        locate the media
 *  - GET  /api/providers/movies?radarrid= | /api/providers/episodes?episodeid=   manual search
 *  - POST /api/providers/movies | /api/providers/episodes             download a chosen result
 *  - PATCH /api/subtitles?action=sync                                  audio-based sync (ffsubsync) on the server
 * Bazarr writes files next to the video; Jellyfin sees them after an item refresh/library scan.
 */
class BazarrClient(http: OkHttpClient, private val settings: SettingsStore) {
    // Provider searches fan out to many sites and Bazarr's sync runs ffsubsync: both can be slow.
    private val http = http.newBuilder().readTimeout(180, TimeUnit.SECONDS).build()

    val isConfigured get() = settings.current.bazarrUrl.isNotBlank() && !settings.bazarrApiKey.isNullOrBlank()

    private fun url(path: String): HttpUrl.Builder =
        "${settings.current.bazarrUrl.trim().trimEnd('/')}/api/$path".toHttpUrl().newBuilder()

    private suspend fun call(req: Request.Builder): String = withContext(Dispatchers.IO) {
        val key = settings.bazarrApiKey ?: error("Bazarr API key is not set")
        http.newCall(req.header("X-API-KEY", key).build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.code == 401 -> error("Bazarr rejected the API key")
                !r.isSuccessful -> error("Bazarr error ${r.code}: ${body.take(200)}")
                else -> body
            }
        }
    }

    private suspend fun get(b: HttpUrl.Builder) = call(Request.Builder().url(b.build()))

    suspend fun version(): String =
        JSONObject(get(url("system/status"))).getJSONObject("data").optString("bazarr_version", "unknown")

    private fun JSONObject.subs(): List<BazarrSubtitle> = optJSONArray("subtitles").objects().map {
        BazarrSubtitle(
            path = it.optString("path").ifEmpty { null }.takeIf { p -> p != "null" },
            code2 = it.optString("code2"),
            name = it.optString("name"),
            forced = it.optBoolean("forced"),
            hi = it.optBoolean("hi"),
        )
    }

    suspend fun findMovie(imdbId: String?, title: String?, year: Int?): BazarrMedia.Movie? {
        val data = JSONObject(get(url("movies"))).optJSONArray("data").objects()
        val hit = data.firstOrNull { imdbId != null && it.optString("imdbId").equals(imdbId, true) }
            ?: data.firstOrNull { it.optString("title").equals(title, true) && it.optString("year") == year?.toString() }
        return hit?.let { BazarrMedia.Movie(it.getInt("radarrId"), it.subs()) }
    }

    suspend fun findEpisode(imdbId: String?, tvdbId: String?, seriesTitle: String?, season: Int, episode: Int): BazarrMedia.Episode? {
        val series = JSONObject(get(url("series"))).optJSONArray("data").objects()
        val s = series.firstOrNull { tvdbId != null && it.optInt("tvdbId").toString() == tvdbId }
            ?: series.firstOrNull { imdbId != null && it.optString("imdbId").equals(imdbId, true) }
            ?: series.firstOrNull { it.optString("title").equals(seriesTitle, true) }
            ?: return null
        val seriesId = s.getInt("sonarrSeriesId")
        val eps = JSONObject(get(url("episodes").addQueryParameter("seriesid[]", seriesId.toString())))
            .optJSONArray("data").objects()
        val e = eps.firstOrNull { it.optInt("season") == season && it.optInt("episode") == episode } ?: return null
        return BazarrMedia.Episode(seriesId, e.getInt("sonarrEpisodeId"), e.subs())
    }

    suspend fun search(media: BazarrMedia): List<BazarrCandidate> {
        val b = when (media) {
            is BazarrMedia.Movie -> url("providers/movies").addQueryParameter("radarrid", media.radarrId.toString())
            is BazarrMedia.Episode -> url("providers/episodes").addQueryParameter("episodeid", media.episodeId.toString())
        }
        return JSONObject(get(b)).optJSONArray("data").objects().map {
            BazarrCandidate(
                provider = it.optString("provider"),
                subtitleId = it.optString("subtitle"),
                language = it.optString("language"),
                releaseInfo = it.optJSONArray("release_info").strings(),
                score = it.optInt("score"),
                hearingImpaired = it.optString("hearing_impaired").equals("true", true),
                forced = it.optString("forced").equals("true", true),
                originalFormat = it.optString("original_format").equals("true", true),
                matches = it.optJSONArray("matches").strings(),
                dontMatches = it.optJSONArray("dont_matches").strings(),
            )
        }
    }

    suspend fun download(media: BazarrMedia, c: BazarrCandidate) {
        val form = FormBody.Builder()
            .add("hi", c.hearingImpaired.toString().replaceFirstChar { it.uppercase() })
            .add("forced", c.forced.toString().replaceFirstChar { it.uppercase() })
            .add("original_format", c.originalFormat.toString().replaceFirstChar { it.uppercase() })
            .add("provider", c.provider)
            .add("subtitle", c.subtitleId)
        val path = when (media) {
            is BazarrMedia.Movie -> { form.add("radarrid", media.radarrId.toString()); "providers/movies" }
            is BazarrMedia.Episode -> {
                form.add("seriesid", media.seriesId.toString()).add("episodeid", media.episodeId.toString())
                "providers/episodes"
            }
        }
        call(Request.Builder().url(url(path).build()).post(form.build()))
    }

    /** Asks Bazarr to sync an external subtitle file to the video's audio track using ffsubsync. */
    suspend fun syncToAudio(media: BazarrMedia, sub: BazarrSubtitle) {
        val path = sub.path ?: error("Only external subtitle files can be synced by Bazarr")
        val b = url("subtitles")
            .addQueryParameter("action", "sync")
            .addQueryParameter("language", sub.code2)
            .addQueryParameter("path", path)
            .addQueryParameter("type", if (media is BazarrMedia.Movie) "movie" else "episode")
            .addQueryParameter("id", (if (media is BazarrMedia.Movie) media.radarrId else (media as BazarrMedia.Episode).episodeId).toString())
            .addQueryParameter("forced", sub.forced.toString().replaceFirstChar { it.uppercase() })
            .addQueryParameter("hi", sub.hi.toString().replaceFirstChar { it.uppercase() })
        call(Request.Builder().url(b.build()).patch(ByteArray(0).toRequestBody()))
    }
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).map { getJSONObject(it) }
private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { getString(it) }
