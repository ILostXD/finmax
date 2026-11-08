package com.finmax.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** One title from Seerr (Jellyseerr/Overseerr), backed by TMDB metadata. */
data class SeerrItem(
    val tmdbId: Int,
    val mediaType: String, // "movie" or "tv"
    val title: String,
    val overview: String,
    val year: String?,
    val rating: Double?,
    val posterPath: String?,
    val backdropPath: String?,
    val status: Int, // Seerr MediaStatus: 1 unknown, 2 pending, 3 processing, 4 partly available, 5 available
) {
    val poster get() = posterPath?.let { "https://image.tmdb.org/t/p/w342$it" }
    val backdrop get() = backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
    val backdropSmall get() = backdropPath?.let { "https://image.tmdb.org/t/p/w300$it" }
    val statusLabel
        get() = when (status) {
            5 -> "Available"
            4 -> "Partly available"
            3 -> "Processing"
            2 -> "Requested"
            else -> null
        }
    val requestable get() = status <= 1
}

/**
 * Minimal Seerr API client (same endpoints Wholphin uses): discover lists, search and requests.
 * Auth: the `X-Api-Key` header (Seerr → Settings → General → API Key).
 */
class SeerrClient(http: OkHttpClient, private val settings: SettingsStore) {
    private val http = http

    val isConfigured get() = settings.current.seerrUrl.isNotBlank() && !settings.seerrApiKey.isNullOrBlank()

    private fun url(path: String) = "${settings.current.seerrUrl.trim().trimEnd('/')}/api/v1/$path".toHttpUrl().newBuilder()

    private suspend fun call(req: Request.Builder): String = withContext(Dispatchers.IO) {
        val key = settings.seerrApiKey ?: error("Seerr API key is not set")
        http.newCall(req.header("X-Api-Key", key).header("Accept", "application/json").build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.code == 401 || r.code == 403 -> error("Seerr rejected the API key")
                !r.isSuccessful -> error("Seerr error ${r.code}: ${body.take(160)}")
                else -> body
            }
        }
    }

    suspend fun version(): String = JSONObject(call(Request.Builder().url(url("status").build()))).optString("version", "?")

    private suspend fun list(path: String, vararg query: Pair<String, String>): List<SeerrItem> {
        val b = url(path)
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        val results = JSONObject(call(Request.Builder().url(b.build()))).optJSONArray("results") ?: JSONArray()
        return (0 until results.length()).mapNotNull { parse(results.getJSONObject(it)) }
    }

    suspend fun trending() = list("discover/trending", "page" to "1")
    suspend fun popularMovies() = list("discover/movies", "page" to "1")
    suspend fun popularShows() = list("discover/tv", "page" to "1")
    suspend fun upcomingMovies() = list("discover/movies/upcoming", "page" to "1")
    suspend fun upcomingShows() = list("discover/tv/upcoming", "page" to "1")
    suspend fun search(query: String) = list("search", "query" to query, "page" to "1")

    /** Requests the whole title (all seasons for shows). Seerr decides whether approval is needed. */
    suspend fun request(item: SeerrItem) {
        val body = JSONObject().put("mediaType", item.mediaType).put("mediaId", item.tmdbId)
        if (item.mediaType == "tv") body.put("seasons", "all")
        call(Request.Builder().url(url("request").build()).post(body.toString().toRequestBody("application/json".toMediaType())))
    }

    private fun parse(o: JSONObject): SeerrItem? {
        val type = o.optString("mediaType")
        if (type != "movie" && type != "tv") return null // people in search results
        val date = o.optString(if (type == "movie") "releaseDate" else "firstAirDate").takeIf { it.length >= 4 }
        return SeerrItem(
            tmdbId = o.optInt("id"),
            mediaType = type,
            title = o.optString(if (type == "movie") "title" else "name"),
            overview = o.optString("overview"),
            year = date?.take(4),
            rating = o.optDouble("voteAverage").takeIf { !it.isNaN() && it > 0 },
            posterPath = o.optString("posterPath").takeIf { it.startsWith("/") },
            backdropPath = o.optString("backdropPath").takeIf { it.startsWith("/") },
            status = o.optJSONObject("mediaInfo")?.optInt("status", 1) ?: 1,
        )
    }
}
