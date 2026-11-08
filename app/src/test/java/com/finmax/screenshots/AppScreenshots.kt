package com.finmax.screenshots

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.tv.material3.LocalContentColor
import com.finmax.FinmaxApp
import com.finmax.MainNavigation
import com.finmax.Root
import com.finmax.data.SecureStore
import com.finmax.ui.Navigator
import com.finmax.ui.Route
import com.finmax.ui.theme.FinmaxTheme
import com.finmax.ui.theme.Fm
import coil3.imageDecoderEnabled
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.DeviceInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The real app, wired to [FakeJellyfin] and an in-memory secret store (no Android Keystore here). */
class MemorySecureStore(context: Context) : SecureStore(context) {
    private val values = HashMap<String, String>()
    override fun put(name: String, value: String?) { if (value == null) values.remove(name) else values[name] = value }
    override fun get(name: String): String? = values[name]
}

class PreviewApp : FinmaxApp() {
    override fun createSecureStore(): SecureStore = MemorySecureStore(this)
    override fun configureHttp(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder.addInterceptor(server)
    override fun deviceInfo() = DeviceInfo("preview", "Preview TV")

    // Decode on the main thread: Robolectric's native graphics can't decode on Coil's IO threads.
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader = coil3.ImageLoader.Builder(context)
        .components { add(coil3.network.okhttp.OkHttpNetworkFetcherFactory(callFactory = { http })) }
        .decoderCoroutineContext(kotlinx.coroutines.Dispatchers.Main.immediate)
        .imageDecoderEnabled(false) // Robolectric has no ImageDecoder; BitmapFactory works
        .diskCache(null as coil3.disk.DiskCache?) // fake art changes between runs
        .build()

    companion object { val server by lazy { FakeJellyfin() } }
}

/**
 * Every page of the app at TV size (960×540 dp, 1080p), driven like a user would: sign in, then
 * open each page. `./gradlew :app:testDebugUnitTest -Pscreenshots`, output in
 * `app/build/screenshots/app/`. Video, animation and the player can't be shown (no ExoPlayer here).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w960dp-h540dp-land-xhdpi", application = PreviewApp::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class AppScreenshots {
    @get:Rule val compose = createComposeRule()

    private val out = File("build/screenshots/app").apply { mkdirs() }
    private val app get() = FinmaxApp.instance
    private val lib get() = PreviewApp.server

    @Composable
    fun App(content: @Composable () -> Unit) {
        FinmaxTheme {
            CompositionLocalProvider(LocalContentColor provides Fm.Text) {
                Box(Modifier.fillMaxSize().background(Fm.Bg)) { content() }
            }
        }
    }

    /** Lets network (real threads), image decoding and animations finish. */
    private fun settle(ms: Long = 3_000) {
        repeat((ms / 250).toInt()) {
            compose.mainClock.advanceTimeBy(250)
            Thread.sleep(80)
        }
        compose.waitForIdle()
    }

    private fun shot(name: String) {
        settle()
        compose.onRoot().captureRoboImage(File(out, "$name.png").path)
    }

    private fun key(k: Key, times: Int = 1) = repeat(times) {
        compose.onRoot().performKeyInput { pressKey(k) }
        settle(600)
    }

    private fun signIn() = runBlocking {
        val server = app.sessions.findServer("demo.local").getOrThrow()
        app.sessions.login(server, "Alex", "preview").getOrThrow()
    }


    @Test fun signInFlow() {
        compose.setContent { App { Root() } }
        shot("01-sign-in-add-server")
    }

    @Test fun signInReturning() {
        signIn()
        app.sessions.deactivate()
        compose.setContent { App { Root() } }
        shot("02-sign-in-servers")
        key(Key.DirectionCenter) // the remote's OK on the focused server
        shot("03-sign-in-users")
        key(Key.DirectionRight) // Quick Connect sits right after the saved profile
        key(Key.DirectionCenter)
        shot("04-sign-in-quick-connect")
    }

    private fun go(nav: Navigator, route: Route) {
        compose.runOnUiThread { if (route is Route.Tab) nav.tab(route) else nav.push(route) }
    }

    @Test fun pages() {
        signIn()
        // Background video needs ExoPlayer, which never settles here; the artwork is what shows first anyway.
        app.settings.update { it.copy(detailVideo = com.finmax.data.DetailVideo.OFF) }
        val nav = Navigator()
        compose.setContent { App { MainNavigation(nav) } }

        shot("10-home")
        key(Key.DirectionDown)
        shot("11-home-next-slide")
        key(Key.DirectionRight, 3)
        shot("12-home-recommendations")
        key(Key.DirectionDown, 4)
        shot("13-home-recommendations-scrolled")

        go(nav, Route.Movies); shot("20-movies")
        key(Key.DirectionRight, 2); key(Key.DirectionCenter); shot("20b-movies-genre-picker")
        key(Key.Back)
        go(nav, Route.Shows); shot("21-shows")
        go(nav, Route.MyList); shot("22-my-list")
        go(nav, Route.Libraries); shot("23-libraries")
        go(nav, Route.Genre("Drama", lib.moviesView.id)); shot("24-genre")
        go(nav, Route.Library(lib.collectionsView.id, "Collections", lib.collectionsView.collectionType)); shot("25-collections")
        go(nav, Route.Search); shot("26-search")
        go(nav, Route.Settings); shot("27-settings")
        go(nav, Route.Profiles); shot("28-profiles")

        go(nav, Route.Detail(lib.lastLaugh.id)); shot("30-details-movie")
        key(Key.DirectionDown); shot("31-details-movie-cast")
        key(Key.DirectionDown); shot("32-details-movie-more")
        go(nav, Route.Detail(lib.nightShift.id)); shot("33-details-series")
        key(Key.DirectionDown, 2); shot("34-details-series-episodes")
        go(nav, Route.Detail(lib.saga.id)); shot("35-details-collection")

        if (lib.unknown.isNotEmpty()) println("FakeJellyfin unanswered: ${lib.unknown}")

        // Last: ExoPlayer polls forever here, so Compose never idles again. Drive the clock by hand.
        compose.mainClock.autoAdvance = false
        go(nav, Route.Player(lib.lastLaugh.id))
        repeat(12) { compose.mainClock.advanceTimeBy(250); Thread.sleep(80) }
        compose.onRoot().captureRoboImage(File(out, "40-player-controls.png").path)
    }
}
