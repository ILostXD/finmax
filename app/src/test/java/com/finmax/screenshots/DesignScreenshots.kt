package com.finmax.screenshots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.tv.material3.LocalContentColor
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.finmax.data.MediaRepository
import com.finmax.data.SavedServer
import com.finmax.data.SavedUser
import com.finmax.data.Session
import com.finmax.ui.LocalEntry
import com.finmax.ui.LocalNav
import com.finmax.ui.Navigator
import com.finmax.ui.components.Load
import com.finmax.ui.detail.Detail
import com.finmax.ui.detail.DetailState
import com.finmax.ui.home.HomeContent
import com.finmax.ui.home.HomeSection
import com.finmax.ui.home.HomeState
import com.finmax.ui.theme.FinmaxTheme
import com.finmax.ui.theme.Fm
import com.github.takahirom.roborazzi.captureRoboImage
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.BaseItemPerson
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.NameGuidPair
import org.jellyfin.sdk.model.api.PersonKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDateTime
import java.util.UUID

/**
 * Renders the real Home and details composables at TV size (960×540 dp, 1080p) with a fake
 * library, so the design can be reviewed without a device or server. Opt-in:
 * `./gradlew :app:testDebugUnitTest -Pscreenshots`, output in `app/build/screenshots/`.
 *
 * Placeholder artwork is cut from the inspiration images in `reference/inspiration/` plus palette
 * gradients; titles and people are invented.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-xhdpi")
class DesignScreenshots {
    @get:Rule val compose = createComposeRule()

    private val out = File("build/screenshots").apply { mkdirs() }
    private val art = HashMap<String, Bitmap>()

    private val repo: MediaRepository by lazy {
        val ctx = RuntimeEnvironment.getApplication()
        val jellyfin = createJellyfin {
            context = ctx
            clientInfo = ClientInfo("FINMAX", "preview")
            deviceInfo = DeviceInfo("preview", "Preview TV")
        }
        val api = jellyfin.createApi(baseUrl = "http://demo.invalid", accessToken = "preview")
        val user = SavedUser("srv", "0123456789abcdef0123456789abcdef", "Demo", "preview", null)
        MediaRepository(Session(SavedServer("srv", "Demo", "http://demo.invalid"), user, api, jellyfin))
    }

    // ---- Fake library ---------------------------------------------------------------------

    private fun photo(path: String, crop: Rect): Bitmap {
        val src = BitmapFactory.decodeFile(File("../reference/inspiration/$path").absolutePath)
        // Subject on the left, smeared edge continuing to the right: reads like real backdrop art.
        val out = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val w = (crop.width() * 720f / crop.height()).toInt()
        c.drawBitmap(src, Rect(crop.right - 6, crop.top, crop.right, crop.bottom), Rect(w - 2, 0, 1280, 720), null)
        c.drawBitmap(src, crop, Rect(0, 0, w, 720), null)
        return out
    }

    private fun field(a: Int, b: Int, glow: Int, w: Int = 1280, h: Int = 720): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP) })
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = RadialGradient(w * 0.7f, h * 0.8f, w * 0.45f, glow, 0x00000000, Shader.TileMode.CLAMP) })
        return bmp
    }

    private var n = 0
    private fun item(
        name: String,
        type: BaseItemKind = BaseItemKind.MOVIE,
        picture: Bitmap? = null,
        year: Int = 2021,
        genres: List<String> = listOf("Drama"),
        minutes: Int = 118,
        rating: Float = 7.4f,
        progress: Double? = null,
        overview: String = "A quiet drifter returns to the town that cast him out, carrying a promise he made twenty years ago and a secret nobody there is ready to hear.",
        block: (BaseItemDto) -> BaseItemDto = { it },
    ): BaseItemDto {
        val id = UUID.nameUUIDFromBytes("item${n++}".toByteArray())
        val bmp = picture ?: field(palette[n % palette.size].first, palette[n % palette.size].second, palette[n % palette.size].third)
        art[id.toString().replace("-", "")] = bmp
        art[id.toString()] = bmp
        return block(
            BaseItemDto(
                id = id, type = type, name = name, overview = overview, genres = genres, productionYear = year,
                runTimeTicks = minutes * 60L * 10_000_000, officialRating = "PG-13", communityRating = rating,
                backdropImageTags = listOf("b"), imageTags = mapOf(ImageType.PRIMARY to "p"),
                userData = UserItemDataDto(
                    playedPercentage = progress, playbackPositionTicks = if (progress != null) (minutes * 60L * 10_000_000 * progress / 100).toLong() else 0,
                    playCount = 0, isFavorite = false, played = false, key = "k", itemId = id,
                ),
            ),
        )
    }

    private val palette = listOf(
        Triple(0xFF1B3100.toInt(), 0xFF8D3F7D.toInt(), 0x88FFF2C5.toInt()),
        Triple(0xFF0E2233.toInt(), 0xFF3E7C8C.toInt(), 0x88F2C14E.toInt()),
        Triple(0xFF2B0F0F.toInt(), 0xFFB5482E.toInt(), 0x66FFE0A3.toInt()),
        Triple(0xFF101418.toInt(), 0xFF56606E.toInt(), 0x66C9D6E8.toInt()),
        Triple(0xFF1F1238.toInt(), 0xFF7D5AB5.toInt(), 0x66FFB3E0.toInt()),
    )

    private val sandborne by lazy {
        item(
            "Sandborne", picture = photo("home-dune.jpg", Rect(150, 142, 640, 700)), genres = listOf("Action", "Adventure", "Drama"),
            minutes = 155, rating = 8.2f,
            overview = "A noble family finds itself entangled in a war for a desert planet's only treasure, while its heir is haunted by visions of a dark future.",
        )
    }
    private val lastLaugh by lazy {
        item(
            "Last Laugh", picture = photo("details-joker.jpg", Rect(200, 120, 560, 460)), year = 2019,
            genres = listOf("Crime", "Thriller", "Drama"), minutes = 122, rating = 8.4f,
            overview = "A failed comedian drifts through a city that has stopped looking at him, until one night he makes it look. A character study that turns into a broader cautionary tale.",
        ) {
            it.copy(
                taglines = listOf("Put on a happy face."),
                premiereDate = LocalDateTime.of(2019, 10, 4, 0, 0),
                studios = listOf(NameGuidPair("Bronze Lantern Pictures", UUID.randomUUID())),
                people = listOf(
                    person("Joaquín Marlow", "Arthur", PersonKind.ACTOR, photo("details-joker.jpg", Rect(250, 180, 470, 470))),
                    person("Robin Hale", "Murray", PersonKind.ACTOR, null),
                    person("Zara Beetz", "Sophie", PersonKind.ACTOR, null),
                    person("Frances Conroy-Vale", "Penny", PersonKind.ACTOR, null),
                    person("Brett Cullen", "Thomas", PersonKind.ACTOR, null),
                    person("Shea Whigham", "Detective", PersonKind.ACTOR, null),
                    person("Todd Phelan", null, PersonKind.DIRECTOR, null),
                    person("Scott Silverman", null, PersonKind.WRITER, null),
                    person("Hildur Gudnadottir", null, PersonKind.COMPOSER, null),
                ),
            )
        }
    }

    private fun person(name: String, role: String?, kind: PersonKind, picture: Bitmap?): BaseItemPerson {
        val id = UUID.nameUUIDFromBytes(name.toByteArray())
        val bmp = picture ?: field(palette[name.length % palette.size].first, palette[name.length % palette.size].second, palette[name.length % palette.size].third, 400, 600)
        art[id.toString().replace("-", "")] = bmp
        art[id.toString()] = bmp
        return BaseItemPerson(name = name, id = id, role = role, type = kind, primaryImageTag = "p")
    }

    private fun episode(series: String, s: Int, e: Int, name: String, progress: Double? = null) =
        item(name, BaseItemKind.EPISODE, minutes = 48, progress = progress) { it.copy(seriesName = series, parentIndexNumber = s, indexNumber = e) }

    // ---- Harness ---------------------------------------------------------------------------

    private val blank by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

    private val previewHandler = AsyncImagePreviewHandler { request ->
        val url = request.data.toString()
        (art.entries.firstOrNull { (k, _) -> k in url }?.value ?: blank).asImage()
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        val nav = remember { Navigator() }
        CompositionLocalProvider(
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides previewHandler,
            LocalNav provides nav,
            LocalEntry provides nav.top,
        ) {
            FinmaxTheme {
                CompositionLocalProvider(LocalContentColor provides Fm.Text) {
                    Box(Modifier.fillMaxSize().background(Fm.Bg)) { content() }
                }
            }
        }
    }

    private fun shot(name: String) {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    private fun homeState(exploring: Boolean) = HomeState(repo, autoLoad = false).apply {
        featured = listOf(sandborne, lastLaugh, item("Northern Lights", year = 2023))
        sections.add(HomeSection(0, "resume", "Continue Watching", listOf(lastLaugh.withProgress(42.0), item("The Glass Orchard", progress = 70.0), item("Low Tide", progress = 15.0))))
        sections.add(HomeSection(1, "nextup", "Next Up", listOf(episode("Night Shift", 2, 4, "The Long Hallway"), episode("Copperline", 1, 7, "Signal Fires"))))
        sections.add(HomeSection(10, "latest", "New in Movies", listOf(sandborne, item("Paper Satellites"), item("Kingfisher"), item("A Room Upstairs"))))
        this.exploring = exploring
        if (exploring) {
            focused = lastLaugh
            focusedSection = "Continue Watching"
        }
    }

    private fun BaseItemDto.withProgress(p: Double) = copy(userData = userData?.copy(playedPercentage = p, playbackPositionTicks = 1))

    @Test fun home_stage() {
        val state = homeState(exploring = false)
        compose.setContent { Frame { HomeContent(state, repo) } }
        shot("home-stage")
    }

    @Test fun home_explore() {
        val state = homeState(exploring = true)
        compose.setContent { Frame { HomeContent(state, repo) } }
        shot("home-explore")
    }

    private fun detailState(item: BaseItemDto) = DetailState(repo, item.id, autoLoad = false).apply {
        this.item.value = Load.Ok(item)
        similar = listOf(sandborne, item("Paper Satellites"), item("Kingfisher"), item("A Room Upstairs"), item("Low Tide"), item("Northern Lights"))
        trailer = item("Last Laugh – Trailer", BaseItemKind.TRAILER)
    }

    @Test fun details() {
        val item = lastLaugh.withProgress(35.0)
        val state = detailState(item)
        compose.setContent { Frame { Detail(item, state, repo) } }
        shot("details")
    }

    @Test fun details_scrolled() {
        val item = lastLaugh
        val state = detailState(item)
        compose.setContent { Frame { Detail(item, state, repo) } }
        compose.mainClock.advanceTimeBy(500)
        compose.onAllNodes(hasScrollToIndexAction())[0].performScrollToIndex(1)
        shot("details-scrolled")
    }
}
