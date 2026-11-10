package com.finmax

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.finmax.ui.LocalEntry
import com.finmax.ui.LocalNav
import com.finmax.ui.Navigator
import com.finmax.ui.Route
import com.finmax.ui.detail.DetailScreen
import com.finmax.ui.home.HomeScreen
import com.finmax.ui.library.BrowseScreen
import com.finmax.ui.discover.DiscoverScreen
import com.finmax.ui.login.LoginFlow
import com.finmax.ui.login.ProfilesScreen
import com.finmax.ui.player.PlayerScreen
import com.finmax.ui.search.SearchScreen
import com.finmax.ui.settings.SettingsScreen
import com.finmax.ui.theme.Fm
import com.finmax.ui.theme.FinmaxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FinmaxTheme {
                CompositionLocalProvider(androidx.tv.material3.LocalContentColor provides Fm.Text) {
                    Box(Modifier.fillMaxSize().background(Fm.Bg)) { Root() }
                }
            }
        }
    }

    /** Last remote input, for the in-app screensaver. */
    val lastInput = androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        lastInput.longValue = System.currentTimeMillis()
        // The key that wakes the screensaver only wakes it.
        if (com.finmax.ui.components.ScreensaverState.active) {
            if (event.action == android.view.KeyEvent.ACTION_UP) com.finmax.ui.components.ScreensaverState.active = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
internal fun Root() {
    val session by FinmaxApp.instance.sessions.session.collectAsState()
    if (session == null) {
        LoginFlow()
    } else {
        // A new session (user/server switch) gets a fresh navigator and fresh screen state.
        key(session) { MainNavigation() }
    }
}

/** After a while without input (not in the player), the library screensaver covers the app. */
@Composable
internal fun ScreensaverHost(activity: MainActivity, nav: Navigator) {
    val minutes = FinmaxApp.instance.settings.state.collectAsState().value.screensaverMin
    val last = activity.lastInput.longValue
    androidx.compose.runtime.LaunchedEffect(last, minutes, nav.top) {
        // Only a key press ends it (MainActivity.dispatchKeyEvent), so "Show it now" can start it.
        if (minutes <= 0 || nav.top.route is Route.Player) return@LaunchedEffect
        kotlinx.coroutines.delay(minutes * 60_000L)
        com.finmax.ui.components.ScreensaverState.active = true
    }
    androidx.compose.animation.AnimatedVisibility(
        com.finmax.ui.components.ScreensaverState.active, enter = fadeIn(tween(1200)), exit = fadeOut(tween(300)),
    ) { com.finmax.ui.components.Screensaver() }
}

@Composable
internal fun MainNavigation(nav: Navigator = remember { Navigator() }) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    CompositionLocalProvider(LocalNav provides nav) {
      Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = nav.top,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
            label = "nav",
        ) { entry ->
            CompositionLocalProvider(LocalEntry provides entry) {
                when (val r = entry.route) {
                    Route.Home -> HomeScreen()
                    Route.Movies -> BrowseScreen.Movies()
                    Route.Shows -> BrowseScreen.Shows()
                    Route.MyList -> BrowseScreen.MyList()
                    Route.Discover -> DiscoverScreen()
                    Route.Search -> SearchScreen()
                    Route.Settings -> SettingsScreen()
                    Route.Profiles -> ProfilesScreen()
                    is Route.Library -> BrowseScreen.Library(r)
                    is Route.Genre -> BrowseScreen.Genre(r)
                    is Route.Detail -> DetailScreen(r.id)
                    is Route.Person -> com.finmax.ui.detail.PersonScreen(r.id)
                    is Route.SeerrTitle -> com.finmax.ui.discover.SeerrTitleScreen(r.item, r.requestNow)
                    Route.SeerrSearch -> com.finmax.ui.discover.SeerrSearchScreen()
                    is Route.SeerrGenre -> com.finmax.ui.discover.SeerrGenreScreen(r.genre)
                    is Route.Player -> PlayerScreen(r)
                }
            }
        }
        if (activity != null) ScreensaverHost(activity, nav)
      }
    }
}
