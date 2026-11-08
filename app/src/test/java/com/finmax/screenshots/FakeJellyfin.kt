package com.finmax.screenshots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemDtoQueryResult
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.RecommendationDto
import org.jellyfin.sdk.model.api.RecommendationType
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A small invented Jellyfin library served from inside OkHttp, so the whole app (SDK calls, Coil
 * image loading) runs unchanged in the screenshot tests. Artwork is generated: titled posters,
 * gradient backdrops, and two photographic backdrops cut from `reference/inspiration/`.
 */
class FakeJellyfin : Interceptor {
    val serverId = "f1a2b3c4d5e6f7a8b9c0d1e2f3a4b5c6"
    val userId: UUID = UUID.fromString("0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d")
    val unknown = ConcurrentHashMap.newKeySet<String>()

    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val items = LinkedHashMap<UUID, BaseItemDto>()
    private val art = HashMap<UUID, Art>()
    private val people = HashMap<UUID, Art>()
    private val jpegCache = ConcurrentHashMap<String, ByteArray>()

    private sealed interface Art
    private class Photo(val file: String, val crop: Rect) : Art
    private class Field(val a: Int, val b: Int, val glow: Int) : Art

    private val palettes = listOf(
        Field(0xFF1B3100.toInt(), 0xFF8D3F7D.toInt(), 0xAAFFF2C5.toInt()),
        Field(0xFF0E2233.toInt(), 0xFF3E7C8C.toInt(), 0x99F2C14E.toInt()),
        Field(0xFF2B0F0F.toInt(), 0xFFB5482E.toInt(), 0x88FFE0A3.toInt()),
        Field(0xFF101418.toInt(), 0xFF56606E.toInt(), 0x88C9D6E8.toInt()),
        Field(0xFF1F1238.toInt(), 0xFF7D5AB5.toInt(), 0x88FFB3E0.toInt()),
        Field(0xFF06261F.toInt(), 0xFF2F8F6B.toInt(), 0x88E8FFB0.toInt()),
        Field(0xFF2A1A05.toInt(), 0xFFC98A2B.toInt(), 0x88FFF0C0.toInt()),
    )

    // ---- Library ------------------------------------------------------------------------------

    private fun id(key: String): UUID = UUID.nameUUIDFromBytes(key.toByteArray())

    val moviesView = view("Movies", CollectionType.MOVIES)
    val showsView = view("Shows", CollectionType.TVSHOWS)
    val collectionsView = view("Collections", CollectionType.BOXSETS)

    private fun view(name: String, type: CollectionType) = BaseItemDto(
        id = id("view/$name"), type = BaseItemKind.COLLECTION_FOLDER, name = name, collectionType = type,
        imageTags = mapOf(ImageType.PRIMARY to "p"),
    ).also { items[it.id] = it; art[it.id] = palettes[name.length % palettes.size] }

    private fun userData(id: UUID, progress: Double? = null, played: Boolean = false, fav: Boolean = false, minutes: Int = 0) = UserItemDataDto(
        playedPercentage = progress,
        playbackPositionTicks = if (progress != null) (minutes * 600_000_000L * progress / 100).toLong() else 0,
        playCount = if (played) 1 else 0, isFavorite = fav, played = played, key = id.toString(), itemId = id,
    )

    private var paletteIndex = 0
    private fun movie(
        name: String, year: Int, genres: List<String>, minutes: Int, rating: Float, overview: String,
        photo: Photo? = null, progress: Double? = null, played: Boolean = false, fav: Boolean = false,
        officialRating: String = "PG-13", extra: (BaseItemDto) -> BaseItemDto = { it },
    ): BaseItemDto {
        val id = id("movie/$name")
        art[id] = photo ?: palettes[paletteIndex++ % palettes.size]
        val item = extra(
            BaseItemDto(
                id = id, type = BaseItemKind.MOVIE, name = name, overview = overview, genres = genres, productionYear = year,
                premiereDate = LocalDateTime.of(year, 1 + name.length % 12, 1 + name.length % 27, 0, 0),
                runTimeTicks = minutes * 600_000_000L, officialRating = officialRating, communityRating = rating,
                criticRating = rating * 10 + 4, parentId = moviesView.id,
                backdropImageTags = listOf("b"), imageTags = mapOf(ImageType.PRIMARY to "p"),
                userData = userData(id, progress, played, fav, minutes),
            ),
        )
        items[id] = item
        return item
    }

    private fun person(name: String, role: String?, kind: PersonKind, photo: Photo? = null): BaseItemPerson {
        val pid = id("person/$name")
        people[pid] = photo ?: palettes[name.length % palettes.size]
        return BaseItemPerson(name = name, id = pid, role = role, type = kind, primaryImageTag = "p")
    }

    val sandborne = movie(
        "Sandborne", 2021, listOf("Science Fiction", "Adventure", "Drama"), 155, 8.2f,
        "A noble family is handed stewardship of a desert planet whose spice is the most valuable substance in the universe, and its heir is haunted by visions of the war to come.",
        photo = Photo("home-dune.jpg", Rect(236, 196, 640, 700)), fav = true,
    ) {
        it.copy(
            taglines = listOf("Beyond fear, destiny awaits."),
            studios = listOf(NameGuidPair("Dunewright Studios", id("studio/dw"))),
            people = listOf(
                person("Teo Calvert", "Paul", PersonKind.ACTOR, Photo("home-dune.jpg", Rect(230, 200, 560, 700))),
                person("Rebecca Lindqvist", "Jessica", PersonKind.ACTOR), person("Oscar Ilunga", "Leto", PersonKind.ACTOR),
                person("Zendra Coleman", "Chani", PersonKind.ACTOR), person("Jason Mamaro", "Duncan", PersonKind.ACTOR),
                person("Denis Villard", null, PersonKind.DIRECTOR), person("Jon Spaihtsman", null, PersonKind.WRITER),
                person("Hans Zimmerling", null, PersonKind.COMPOSER),
            ),
        )
    }
    val lastLaugh = movie(
        "Last Laugh", 2019, listOf("Crime", "Thriller", "Drama"), 122, 8.4f,
        "A failed comedian drifts through a city that has stopped looking at him, until one night he makes it look. A character study that turns into a broader cautionary tale.",
        photo = Photo("details-joker.jpg", Rect(200, 120, 560, 460)), progress = 35.0, officialRating = "R",
    ) {
        it.copy(
            taglines = listOf("Put on a happy face."),
            premiereDate = LocalDateTime.of(2019, 10, 4, 0, 0),
            studios = listOf(NameGuidPair("Bronze Lantern Pictures", id("studio/bl"))),
            people = listOf(
                person("Joaquín Marlow", "Arthur", PersonKind.ACTOR, Photo("details-joker.jpg", Rect(250, 180, 470, 470))),
                person("Robin Hale", "Murray", PersonKind.ACTOR), person("Zara Beetz", "Sophie", PersonKind.ACTOR),
                person("Frances Conroy-Vale", "Penny", PersonKind.ACTOR), person("Brett Cullen", "Thomas", PersonKind.ACTOR),
                person("Shea Whigham", "Detective", PersonKind.ACTOR),
                person("Todd Phelan", null, PersonKind.DIRECTOR), person("Scott Silverman", null, PersonKind.WRITER),
                person("Hildur Gudnadottir", null, PersonKind.COMPOSER),
            ),
        )
    }
    val lastLaughTrailer = BaseItemDto(id = id("trailer/lastlaugh"), type = BaseItemKind.TRAILER, name = "Last Laugh – Trailer").also { items[it.id] = it }

    private val plain = "When an ordinary week comes apart at the seams, two strangers have one night to decide what they owe each other."
    val movies = listOf(
        sandborne, lastLaugh,
        movie("The Glass Orchard", 2022, listOf("Drama", "Mystery"), 109, 7.1f, plain, progress = 70.0),
        movie("Low Tide", 2020, listOf("Thriller"), 98, 6.8f, plain, progress = 15.0),
        movie("Paper Satellites", 2023, listOf("Science Fiction", "Romance"), 114, 7.6f, plain, fav = true),
        movie("Kingfisher", 2018, listOf("Adventure", "Family"), 101, 7.0f, plain, played = true),
        movie("A Room Upstairs", 2021, listOf("Horror"), 93, 6.4f, plain, officialRating = "R"),
        movie("Northern Lights", 2023, listOf("Drama", "Romance"), 126, 7.8f, plain),
        movie("Salt & Ember", 2017, listOf("Action", "Adventure"), 132, 6.9f, plain),
        movie("The Ninth Floor", 2024, listOf("Mystery", "Thriller"), 117, 7.3f, plain),
        movie("Quiet Hours", 2016, listOf("Drama"), 104, 7.5f, plain, played = true),
        movie("Velvet Static", 2022, listOf("Music", "Drama"), 111, 7.2f, plain, fav = true),
        movie("Harbour Lights", 2015, listOf("Crime", "Drama"), 119, 6.7f, plain),
        movie("Under Glass", 2020, listOf("Science Fiction"), 97, 6.6f, plain),
        movie("Long Way Home", 2019, listOf("Family", "Comedy"), 92, 7.0f, plain),
        movie("Cinder Road", 2024, listOf("Action", "Thriller"), 128, 7.4f, plain),
    )

    private fun series(name: String, year: Int, genres: List<String>, seasons: Int, overview: String, fav: Boolean = false): BaseItemDto {
        val sid = id("series/$name")
        art[sid] = palettes[paletteIndex++ % palettes.size]
        val s = BaseItemDto(
            id = sid, type = BaseItemKind.SERIES, name = name, overview = overview, genres = genres, productionYear = year,
            status = "Continuing", childCount = seasons, officialRating = "TV-14", communityRating = 8.0f, parentId = showsView.id,
            backdropImageTags = listOf("b"), imageTags = mapOf(ImageType.PRIMARY to "p"),
            studios = listOf(NameGuidPair("Northside Network", id("studio/nn"))),
            userData = userData(sid, fav = fav).copy(unplayedItemCount = 6),
        )
        items[sid] = s
        repeat(seasons) { si ->
            val season = BaseItemDto(
                id = id("season/$name/${si + 1}"), type = BaseItemKind.SEASON, name = "Season ${si + 1}", indexNumber = si + 1,
                seriesId = sid, seriesName = name, imageTags = mapOf(ImageType.PRIMARY to "p"),
            )
            items[season.id] = season
            art[season.id] = art[sid]!!
            listOf("Pilot", "The Long Hallway", "Signal Fires", "Second Shift", "Glasswork", "Open Water", "The Quiet Part", "Last Call")
                .forEachIndexed { ei, title ->
                    val eid = id("ep/$name/${si + 1}/${ei + 1}")
                    art[eid] = palettes[(ei + si + name.length) % palettes.size]
                    items[eid] = BaseItemDto(
                        id = eid, type = BaseItemKind.EPISODE, name = title, indexNumber = ei + 1, parentIndexNumber = si + 1,
                        seriesId = sid, seriesName = name, seasonId = season.id, runTimeTicks = (44 + ei) * 600_000_000L,
                        premiereDate = LocalDateTime.of(year + si, 3, 1, 0, 0).plusWeeks(ei.toLong()),
                        overview = "Things at the depot get complicated when an old friend turns up with a favour to ask and no time to explain.",
                        imageTags = mapOf(ImageType.PRIMARY to "p"), parentBackdropItemId = sid, parentBackdropImageTags = listOf("b"),
                        userData = userData(eid, progress = if (si == 0 && ei == 3) 40.0 else null, played = si == 0 && ei < 3, minutes = 47),
                    )
                }
        }
        return s
    }

    val nightShift = series("Night Shift", 2022, listOf("Drama", "Crime"), 2,
        "Four night workers at a city bus depot keep the lights on, and each other's secrets, until a missing driver turns the routine inside out.", fav = true)
    val copperline = series("Copperline", 2024, listOf("Mystery", "Thriller"), 1,
        "In a mining town that has lost its mine, a radio operator picks up a signal that should not exist.")
    val shows = listOf(nightShift, copperline, series("Margins", 2021, listOf("Comedy"), 3, plain), series("The Orchardists", 2020, listOf("Drama"), 2, plain))

    val saga = BaseItemDto(
        id = id("set/sandborne"), type = BaseItemKind.BOX_SET, name = "Sandborne Collection", overview = "Both chapters of the desert saga.",
        productionYear = 2021, backdropImageTags = listOf("b"), imageTags = mapOf(ImageType.PRIMARY to "p"), childCount = 2,
    ).also { items[it.id] = it; art[it.id] = art[sandborne.id]!! }

    private fun episodes(series: BaseItemDto, season: Int? = null) =
        items.values.filter { it.type == BaseItemKind.EPISODE && it.seriesId == series.id && (season == null || it.parentIndexNumber == season) }

    private val nextUp get() = listOf(episodes(nightShift, 1)[3], episodes(copperline, 1)[0], episodes(shows[2], 1)[0])
    private val resume get() = listOf(lastLaugh, movies[2], episodes(nightShift, 1)[3], movies[3])

    // ---- HTTP -------------------------------------------------------------------------------

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val url = req.url
        val seg = url.pathSegments.filter { it.isNotEmpty() }
        val path = "/" + seg.joinToString("/")
        fun q(name: String) = url.queryParameter(name)
        fun list(xs: List<BaseItemDto>) = ok(req, json.encodeToString(ListSerializer(BaseItemDto.serializer()), xs))
        fun result(xs: List<BaseItemDto>): Response {
            val start = q("startIndex")?.toInt() ?: 0
            val limit = q("limit")?.toInt() ?: xs.size
            return ok(req, json.encodeToString(BaseItemDtoQueryResult.serializer(), BaseItemDtoQueryResult(xs.drop(start).take(limit), xs.size, start)))
        }
        fun <T> obj(s: KSerializer<T>, v: T) = ok(req, json.encodeToString(s, v))

        return when {
            seg.size >= 4 && seg[0] == "Items" && seg[2] == "Images" -> image(req, UUID.fromString(seg[1].withDashes()), seg[3])
            path == "/System/Info/Public" -> obj(PublicSystemInfo.serializer(), PublicSystemInfo(serverName = "Living Room", version = "10.11.0", id = serverId, startupWizardCompleted = true, productName = "Jellyfin Server"))
            path == "/Users/Public" -> obj(ListSerializer(UserDto.serializer()), listOf(user("Alex"), user("Sam")))
            path == "/Users/AuthenticateByName" -> obj(AuthenticationResult.serializer(), AuthenticationResult(user = user("Alex"), accessToken = "preview-token", serverId = serverId))
            path == "/QuickConnect/Enabled" -> ok(req, "true")
            path == "/QuickConnect/Initiate" -> obj(QuickConnectResult.serializer(), QuickConnectResult(authenticated = false, secret = "s", code = "482913", deviceId = "d", deviceName = "Preview TV", appName = "FINMAX", appVersion = "0.1.0", dateAdded = LocalDateTime.now()))
            path == "/QuickConnect/Connect" -> obj(QuickConnectResult.serializer(), QuickConnectResult(authenticated = false, secret = "s", code = "482913", deviceId = "d", deviceName = "Preview TV", appName = "FINMAX", appVersion = "0.1.0", dateAdded = LocalDateTime.now()))
            path == "/UserViews" -> result(listOf(moviesView, showsView, collectionsView))
            path == "/UserItems/Resume" -> result(resume)
            path == "/Shows/NextUp" -> result(q("seriesId")?.let { sid -> nextUp.filter { it.seriesId.toString().replace("-", "") == sid.replace("-", "") } } ?: nextUp)
            path == "/Items/Latest" -> list(if (q("parentId")?.replace("-", "") == showsView.id.toString().replace("-", "")) shows else movies.reversed().take(10))
            path == "/Movies/Recommendations" -> obj(
                ListSerializer(RecommendationDto.serializer()),
                listOf(RecommendationDto(items = listOf(movies[4], movies[7], movies[9], movies[11], movies[15]), recommendationType = RecommendationType.SIMILAR_TO_RECENTLY_PLAYED, baselineItemName = "Kingfisher", categoryId = id("rec"))),
            )
            path == "/Genres" -> result(movies.flatMap { it.genres.orEmpty() }.distinct().sorted().map { BaseItemDto(id = id("genre/$it"), type = BaseItemKind.GENRE, name = it) })
            seg.size == 3 && seg[0] == "Items" && seg[2] == "Similar" -> result(movies.drop(2).take(8))
            seg.size == 3 && seg[0] == "Items" && seg[2] == "LocalTrailers" -> list(if (UUID.fromString(seg[1].withDashes()) == lastLaugh.id) listOf(lastLaughTrailer) else emptyList())
            seg.size == 3 && seg[0] == "Shows" && seg[2] == "Seasons" -> result(items.values.filter { it.type == BaseItemKind.SEASON && it.seriesId == UUID.fromString(seg[1].withDashes()) })
            seg.size == 3 && seg[0] == "Shows" && seg[2] == "Episodes" -> {
                val s = items[UUID.fromString(seg[1].withDashes())]!!
                val season = q("seasonId")?.let { items[UUID.fromString(it.withDashes())]?.indexNumber }
                result(episodes(s, season))
            }
            path == "/Items" -> result(query(url))
            seg.size == 2 && seg[0] == "UserFavoriteItems" -> ok(req, json.encodeToString(UserItemDataDto.serializer(), userData(UUID.fromString(seg[1].withDashes()), fav = true)))
            path.startsWith("/Sessions") -> Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(204).message("No Content").body(ByteArray(0).toResponseBody(null)).build()
            else -> {
                unknown += "${req.method} $path"
                Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").body("".toResponseBody(null)).build()
            }
        }
    }

    private fun query(url: okhttp3.HttpUrl): List<BaseItemDto> {
        url.queryParameter("ids")?.let { ids -> return ids.split(",").mapNotNull { items[UUID.fromString(it.withDashes())] } }
        val types = url.queryParameter("includeItemTypes")?.split(",").orEmpty()
        val parent = url.queryParameter("parentId")?.let { UUID.fromString(it.withDashes()) }
        var xs: List<BaseItemDto> = when {
            parent == saga.id -> listOf(sandborne, movies[4])
            types.isEmpty() -> movies + shows
            else -> buildList {
                if ("Movie" in types) addAll(movies)
                if ("Series" in types) addAll(shows)
                if ("BoxSet" in types) add(saga)
                if ("Episode" in types) addAll(items.values.filter { it.type == BaseItemKind.EPISODE })
            }
        }
        val filters = url.queryParameter("filters").orEmpty()
        if ("IsFavorite" in filters) xs = xs.filter { it.userData?.isFavorite == true }
        if ("IsPlayed" in filters) xs = xs.filter { it.userData?.played == true }
        if ("IsResumable" in filters) xs = xs.filter { (it.userData?.playedPercentage ?: 0.0) > 0 }
        url.queryParameter("genres")?.let { g -> xs = xs.filter { g in it.genres.orEmpty() } }
        url.queryParameter("searchTerm")?.let { t -> xs = xs.filter { it.name.orEmpty().contains(t, ignoreCase = true) } }
        if (url.queryParameter("sortBy")?.startsWith("SortName") == true) xs = xs.sortedBy { it.name }
        return xs
    }

    private fun user(name: String) = UserDto(
        name = name, serverId = serverId, id = if (name == "Alex") userId else id("user/$name"),
        hasPassword = true, hasConfiguredPassword = true, hasConfiguredEasyPassword = false,
    )

    private fun ok(req: Request, body: String) = Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
        .body(body.toResponseBody("application/json; charset=utf-8".toMediaType())).build()

    // ---- Images -------------------------------------------------------------------------------

    private fun image(req: Request, id: UUID, type: String): Response {
        // Rendered up front on the main thread: Robolectric's native graphics can't draw on OkHttp's threads.
        val bytes = jpegCache["$id/$type"]
            ?: return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(404).message("Not Found").body("".toResponseBody(null)).build()
        return Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(bytes.toResponseBody("image/jpeg".toMediaType())).build()
    }

    private fun render(id: UUID, type: String) {
        val item = items[id]
        jpegCache.getOrPut("$id/$type") {
            val a = art[id] ?: people[id] ?: palettes[id.hashCode().mod(palettes.size)]
            val bmp = when {
                people.containsKey(id) -> portrait(a, null)
                type == "Primary" && item?.type == BaseItemKind.EPISODE -> landscape(a, 960, 540)
                type == "Primary" && item?.type == BaseItemKind.COLLECTION_FOLDER -> landscape(a, 960, 540)
                type == "Primary" -> portrait(a, item?.name)
                else -> landscape(a, 1280, 720)
            }
            ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
        }
    }

    private val sources = HashMap<String, Bitmap>()
    private fun source(file: String) = sources.getOrPut(file) { BitmapFactory.decodeFile(File("../reference/inspiration/$file").absolutePath) }

    private fun landscape(a: Art, w: Int, h: Int, title: String? = null): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        when (a) {
            is Photo -> {
                val src = source(a.file)
                val pw = (a.crop.width() * h.toFloat() / a.crop.height()).toInt()
                c.drawBitmap(src, Rect(a.crop.right - 6, a.crop.top, a.crop.right, a.crop.bottom), Rect(pw - 2, 0, w, h), null)
                c.drawBitmap(src, a.crop, Rect(0, 0, pw, h), null)
            }
            is Field -> paintField(c, a, w, h)
        }
        title?.let { drawTitle(c, it, w, h, w / 10f) }
        return out
    }

    private fun portrait(a: Art, title: String?): Bitmap {
        val w = 400
        val h = 600
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        when (a) {
            is Photo -> {
                val src = source(a.file)
                val cw = (a.crop.height() * 2f / 3f).toInt().coerceAtMost(a.crop.width())
                val left = a.crop.left + (a.crop.width() - cw) / 2
                c.drawBitmap(src, Rect(left, a.crop.top, left + cw, a.crop.bottom), Rect(0, 0, w, h), null)
            }
            is Field -> paintField(c, a, w, h)
        }
        if (title != null) {
            c.drawRect(0f, h * 0.55f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, h * 0.55f, 0f, h.toFloat(), 0x00000000, 0xCC000000.toInt(), Shader.TileMode.CLAMP) })
            drawTitle(c, title, w, h, 46f)
        }
        return out
    }

    private fun paintField(c: Canvas, f: Field, w: Int, h: Int) {
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), f.a, f.b, Shader.TileMode.CLAMP) })
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = RadialGradient(w * 0.68f, h * 0.78f, maxOf(w, h) * 0.42f, f.glow, 0x00000000, Shader.TileMode.CLAMP) })
        // A horizon and a sun: enough shape to read as artwork rather than a swatch.
        c.drawRect(0f, h * 0.72f, w.toFloat(), h.toFloat(), Paint().apply { color = Color.argb(70, 0, 0, 0) })
        c.drawCircle(w * 0.3f, h * 0.42f, minOf(w, h) * 0.13f, Paint().apply { color = Color.argb(60, 255, 242, 197); isAntiAlias = true })
    }

    private fun drawTitle(c: Canvas, title: String, w: Int, h: Int, size: Float) {
        val p = Paint().apply {
            color = Color.WHITE; textSize = size; isAntiAlias = true
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val lines = mutableListOf<String>()
        var line = ""
        title.uppercase().split(" ").forEach { word ->
            val t = if (line.isEmpty()) word else "$line $word"
            if (p.measureText(t) > w * 0.84f && line.isNotEmpty()) { lines += line; line = word } else line = t
        }
        lines += line
        lines.reversed().forEachIndexed { i, l -> c.drawText(l, w * 0.08f, h - h * 0.07f - i * size * 1.1f, p) }
    }

    init {
        items.keys.forEach { id -> render(id, "Primary"); render(id, "Backdrop") }
        people.keys.forEach { render(it, "Primary") }
    }
}

private fun String.withDashes(): String =
    if (length == 32) "${substring(0, 8)}-${substring(8, 12)}-${substring(12, 16)}-${substring(16, 20)}-${substring(20)}" else this
