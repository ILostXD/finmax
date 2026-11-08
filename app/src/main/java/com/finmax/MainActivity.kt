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

@Composable
internal fun MainNavigation(nav: Navigator = remember { Navigator() }) {
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    CompositionLocalProvider(LocalNav provides nav) {
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
                    is Route.Player -> PlayerScreen(r)
                }
            }
        }
    }
}
