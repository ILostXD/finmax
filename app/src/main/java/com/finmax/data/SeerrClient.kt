package com.finmax.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TMDB_IMG = "https://image.tmdb.org/t/p/"

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
    /** The Jellyfin item, once it's in the library (Seerr's mediaInfo.jellyfinMediaId). */
    val jellyfinId: String? = null,
) {
    val key get() = "$mediaType/$tmdbId"
    val poster get() = posterPath?.let { "${TMDB_IMG}w342$it" }
    val backdrop get() = backdropPath?.let { "${TMDB_IMG}w1280$it" }
    val backdropMedium get() = backdropPath?.let { "${TMDB_IMG}w780$it" }
    val backdropSmall get() = backdropPath?.let { "${TMDB_IMG}w300$it" }
    val statusLabel get() = seerrStatusLabel(status)
    val isMovie get() = mediaType == "movie"
}

/** Seerr's MediaStatus in the app's words ("Processing" means requested and downloading). */
fun seerrStatusLabel(status: Int) = when (status) {
    5 -> "In your library"
    4 -> "Partly in your library"
    3 -> "Requested · downloading"
    2 -> "Requested"
    else -> null
}

/** One page of a Seerr list; [hasMore] says whether asking for the next page is worth it. */
class SeerrPage(val items: List<SeerrItem>, val hasMore: Boolean)

/** A genre tile from Seerr's genre slider: TMDB genre id, name and a backdrop to show on it. */
data class SeerrGenre(val id: Int, val name: String, val backdrop: String?, val mediaType: String)

data class SeerrPerson(val name: String, val role: String?, val photo: String?)

data class SeerrSeason(val number: Int, val name: String, val episodes: Int, val status: Int)

/** The extra facts for a title's page, and its current request status. */
data class SeerrDetails(
    val tagline: String?,
    val runtimeMin: Int?,
    val genres: List<String>,
    val cast: List<SeerrPerson>,
    val director: String?,
    val seasons: List<SeerrSeason>,
    val status: Int,
    val jellyfinId: String?,
    val certification: String?,
)

/**
 * Seerr API client (the endpoints Wholphin and Jellyseerr's own web app use): discover lists,
 * genres, search, a title's details and requests. Auth: the `X-Api-Key` header.
 */
class SeerrClient(private val http: OkHttpClient, private val settings: SettingsStore) {
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

    private suspend fun get(path: String, vararg query: Pair<String, String>): String {
        val b = url(path)
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return call(Request.Builder().url(b.build()))
    }

    suspend fun version(): String = JSONObject(get("status")).optString("version", "?")

    private suspend fun page(path: String, page: Int, vararg query: Pair<String, String>): SeerrPage {
        val o = JSONObject(get(path, "page" to page.toString(), *query))
        val results = o.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).mapNotNull { parse(results.getJSONObject(it)) }
        return SeerrPage(items, page < o.optInt("totalPages", 1))
    }

    suspend fun trending(p: Int = 1) = page("discover/trending", p)
    suspend fun popularMovies(p: Int = 1) = page("discover/movies", p)
    suspend fun popularShows(p: Int = 1) = page("discover/tv", p)
    suspend fun upcomingMovies(p: Int = 1) = page("discover/movies/upcoming", p)
    suspend fun upcomingShows(p: Int = 1) = page("discover/tv/upcoming", p)
    suspend fun genre(g: SeerrGenre, p: Int = 1) =
        page(if (g.mediaType == "movie") "discover/movies/genre/${g.id}" else "discover/tv/genre/${g.id}", p)
    suspend fun search(query: String, p: Int = 1) = page("search", p, "query" to query)
    suspend fun similar(item: SeerrItem) = page("${item.mediaType}/${item.tmdbId}/recommendations", 1).items

    suspend fun genres(mediaType: String): List<SeerrGenre> {
        val arr = JSONArray(get("discover/genreslider/${if (mediaType == "movie") "movie" else "tv"}"))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { g ->
            SeerrGenre(g.optInt("id"), g.optString("name"), g.optJSONArray("backdrops")?.optString(0)?.takeIf { it.startsWith("/") }?.let { "${TMDB_IMG}w780$it" }, mediaType)
        }
    }

    /** Recent requests (anyone's), newest first, with their titles' artwork. */
    suspend fun requests(take: Int = 20): List<SeerrItem> = coroutineScope {
        val arr = JSONObject(get("request", "take" to take.toString(), "skip" to "0", "sort" to "added", "filter" to "all"))
            .optJSONArray("results") ?: JSONArray()
        (0 until arr.length()).mapNotNull { arr.getJSONObject(it).optJSONObject("media") }
            .map { m -> m.optString("mediaType") to m.optInt("tmdbId") }
            .filter { (t, id) -> (t == "movie" || t == "tv") && id > 0 }
            .distinct()
            .map { (t, id) -> async { runCatching { parseDetail(JSONObject(get("$t/$id")), t) }.getOrNull() } }
            .awaitAll().filterNotNull()
    }

    suspend fun details(item: SeerrItem): SeerrDetails {
        val o = JSONObject(get("${item.mediaType}/${item.tmdbId}"))
        fun list(arr: JSONArray?) = (0 until (arr?.length() ?: 0)).map { arr!!.getJSONObject(it) }
        val credits = o.optJSONObject("credits")
        val info = o.optJSONObject("mediaInfo")
        val seasonStatus = list(info?.optJSONArray("seasons")).associate { it.optInt("seasonNumber") to it.optInt("status", 1) }
        // US certification, e.g. "PG-13" / "TV-MA", where TMDB has one.
        val cert = if (item.isMovie) {
            list(o.optJSONObject("releases")?.optJSONArray("results")).firstOrNull { it.optString("iso_3166_1") == "US" }
                ?.optJSONArray("release_dates")?.let { d -> (0 until d.length()).map { d.getJSONObject(it).optString("certification") }.firstOrNull { it.isNotBlank() } }
        } else {
            list(o.optJSONObject("contentRatings")?.optJSONArray("results")).firstOrNull { it.optString("iso_3166_1") == "US" }?.optString("rating")
        }
        return SeerrDetails(
            tagline = o.optString("tagline").takeIf { it.isNotBlank() },
            runtimeMin = o.optInt("runtime").takeIf { it > 0 } ?: o.optJSONArray("episodeRunTime")?.optInt(0)?.takeIf { it > 0 },
            genres = list(o.optJSONArray("genres")).map { it.optString("name") }.filter { it.isNotBlank() }.take(3),
            cast = list(credits?.optJSONArray("cast")).take(16).map {
                SeerrPerson(it.optString("name"), it.optString("character").takeIf { c -> c.isNotBlank() },
                    it.optString("profilePath").takeIf { p -> p.startsWith("/") }?.let { p -> "${TMDB_IMG}w185$p" })
            },
            director = list(credits?.optJSONArray("crew")).firstOrNull { it.optString("job") == "Director" }?.optString("name"),
            seasons = list(o.optJSONArray("seasons")).map {
                SeerrSeason(it.optInt("seasonNumber"), it.optString("name"), it.optInt("episodeCount"), seasonStatus[it.optInt("seasonNumber")] ?: 1)
            }.filter { it.number > 0 }, // season 0 is specials
            status = info?.optInt("status", 1) ?: 1,
            jellyfinId = info?.optString("jellyfinMediaId")?.takeIf { it.isNotBlank() && it != "null" },
            certification = cert?.takeIf { it.isNotBlank() },
        )
    }

    /** Requests a movie, or a show's [seasons] (null = all). Seerr decides whether approval is needed. */
    suspend fun request(item: SeerrItem, seasons: List<Int>? = null) {
        val body = JSONObject().put("mediaType", item.mediaType).put("mediaId", item.tmdbId)
        if (!item.isMovie) body.put("seasons", seasons?.let { JSONArray(it) } ?: "all")
        call(Request.Builder().url(url("request").build()).post(body.toString().toRequestBody("application/json".toMediaType())))
    }

    private fun parse(o: JSONObject): SeerrItem? {
        val type = o.optString("mediaType")
        if (type != "movie" && type != "tv") return null // people in search results
        return parseDetail(o, type)
    }

    private fun parseDetail(o: JSONObject, type: String): SeerrItem {
        val date = o.optString(if (type == "movie") "releaseDate" else "firstAirDate").takeIf { it.length >= 4 }
        val info = o.optJSONObject("mediaInfo")
        return SeerrItem(
            tmdbId = o.optInt("id"),
            mediaType = type,
            title = o.optString(if (type == "movie") "title" else "name"),
            overview = o.optString("overview"),
            year = date?.take(4),
            rating = o.optDouble("voteAverage").takeIf { !it.isNaN() && it > 0 },
            posterPath = o.optString("posterPath").takeIf { it.startsWith("/") },
            backdropPath = o.optString("backdropPath").takeIf { it.startsWith("/") },
            status = info?.optInt("status", 1) ?: 1,
            jellyfinId = info?.optString("jellyfinMediaId")?.takeIf { it.isNotBlank() && it != "null" },
        )
    }
}
