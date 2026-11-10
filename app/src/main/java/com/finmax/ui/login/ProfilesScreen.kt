package com.finmax.ui.login

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.data.SavedUser
import com.finmax.ui.components.GhostButton
import com.finmax.ui.components.Ic
import com.finmax.ui.components.PopoverItem
import com.finmax.ui.components.focusMemory
import com.finmax.ui.components.popoverBlur
import com.finmax.ui.library.SidePanel
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.launch

/**
 * "Who's watching?": every signed-in profile (across servers) as a big avatar. Picking one
 * switches to it (after its PIN, if it has one). "Manage profiles" turns the tiles into editors
 * (PIN, sign out); "Servers" lists the saved servers.
 */
@Composable
fun ProfilesScreen() {
    val app = FinmaxApp.instance
    val sessions = app.sessions
    val current = sessions.session.value
    val scope = rememberCoroutineScope()
    val nav = com.finmax.ui.LocalNav.current
    var version by remember { mutableIntStateOf(0) } // bump to re-read saved lists after changes
    var pad by remember { mutableStateOf<PinAsk?>(null) }
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedUser?>(null) }
    var serversOpen by remember { mutableStateOf(false) }
    val users = remember(version) { sessions.servers.flatMap { sessions.usersOf(it.id) } }
    val manyServers = sessions.servers.size > 1

    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().popoverBlur(editing != null || serversOpen, 380.dp)) {
            com.finmax.ui.components.BrandBackdrop()
            com.finmax.ui.components.Wordmark(
                20.dp, Modifier.padding(start = Fm.SafeH, top = com.finmax.ui.components.TopBarTop + 10.dp),
            )
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (managing) "Manage profiles" else "Who's watching?", style = MaterialTheme.typography.displayMedium)
                Spacer(Modifier.height(44.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.Top) {
                    users.forEach { u ->
                        val isCurrent = u.userId == current?.user?.userId && u.serverId == current?.server?.id
                        ProfileTile(
                            name = u.name,
                            caption = sessions.servers.firstOrNull { it.id == u.serverId }?.name?.takeIf { manyServers },
                            image = avatarUrl(sessions, u),
                            locked = remember(version) { sessions.hasPin(u) },
                            current = isCurrent,
                            managing = managing,
                            modifier = Modifier.focusMemory("u/${u.serverId}/${u.userId}", initial = isCurrent),
                        ) {
                            when {
                                managing -> editing = u
                                isCurrent -> nav.pop()
                                sessions.hasPin(u) -> pad = PinAsk.Unlock(u)
                                else -> sessions.activate(u)
                            }
                        }
                    }
                    if (!managing) AddTile { sessions.deactivate() }
                }
                Spacer(Modifier.height(48.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    GhostButton(if (managing) "Done" else "Manage profiles", { managing = !managing }, icon = if (managing) Ic.Check else Ic.Settings)
                    GhostButton("Servers", { serversOpen = true }, icon = Ic.Server)
                }
            }
        }

        editing?.let { u ->
            androidx.activity.compose.BackHandler { editing = null }
            val locked = remember(version) { sessions.hasPin(u) }
            SidePanel(u.name, width = 370.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val first = remember { androidx.compose.ui.focus.FocusRequester() }
                    LaunchedEffect(Unit) { kotlinx.coroutines.delay(60); runCatching { first.requestFocus() } }
                    PopoverItem(
                        if (locked) "Change PIN" else "Lock with a PIN",
                        { editing = null; pad = if (locked) PinAsk.Verify(u, then = PinAsk.New(u)) else PinAsk.New(u) },
                        icon = Ic.Lock, modifier = Modifier.fillMaxWidth().focusRequester(first),
                    )
                    if (locked) PopoverItem("Remove PIN", { editing = null; pad = PinAsk.Verify(u, then = null) }, modifier = Modifier.fillMaxWidth())
                    PopoverItem(
                        "Sign out",
                        {
                            editing = null
                            // A locked profile needs its PIN before it can be removed from this TV.
                            if (locked) pad = PinAsk.SignOut(u) else scope.launch { sessions.signOut(u); version++ }
                        },
                        icon = Ic.Logout, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (serversOpen) {
            androidx.activity.compose.BackHandler { serversOpen = false }
            SidePanel("Servers", width = 390.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    sessions.servers.forEach { s ->
                        key(s.id + version) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
                            Text(s.url, style = MaterialTheme.typography.bodySmall, color = Fm.TextFaint)
                            PopoverItem("Forget this server", { sessions.removeServer(s); version++ }, icon = Ic.Delete, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    PopoverItem("Add a server", { sessions.deactivate() }, icon = Ic.Add, modifier = Modifier.fillMaxWidth().focusMemory("addserver", initial = true))
                }
            }
        }
    }

    pad?.let { ask ->
        androidx.activity.compose.BackHandler { pad = null }
        PinPad(ask.title, ask.subtitle) { pin ->
            when (ask) {
                is PinAsk.Unlock -> if (sessions.checkPin(ask.user, pin)) { pad = null; sessions.activate(ask.user); true } else false
                is PinAsk.SignOut -> if (sessions.checkPin(ask.user, pin)) { pad = null; scope.launch { sessions.signOut(ask.user); version++ }; true } else false
                is PinAsk.Verify -> if (sessions.checkPin(ask.user, pin)) {
                    if (ask.then == null) { sessions.setPin(ask.user, null); version++ }
                    pad = ask.then
                    true
                } else false
                is PinAsk.New -> { pad = PinAsk.Confirm(ask.user, pin); true }
                is PinAsk.Confirm -> if (pin == ask.pin) { sessions.setPin(ask.user, pin); version++; pad = null; true } else {
                    pad = PinAsk.New(ask.user, mismatch = true); true
                }
            }
        }
    }
}

private fun avatarUrl(sessions: com.finmax.data.SessionManager, u: SavedUser): String? {
    val server = sessions.servers.firstOrNull { it.id == u.serverId } ?: return null
    return u.imageTag?.let { "${server.url.trimEnd('/')}/Users/${u.userId}/Images/Primary?tag=$it&maxWidth=300" }
}

/** Round, like the profile picture in the top bar. */
private val TileShape = CircleShape

/** Avatar square (photo, or initial on an amethyst gradient), name below; focus rings and lifts it. */
@Composable
private fun ProfileTile(
    name: String, caption: String?, image: String?, locked: Boolean, current: Boolean, managing: Boolean,
    modifier: Modifier, onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = modifier.size(150.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(TileShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Fm.Surface, focusedContainerColor = Fm.Surface, pressedContainerColor = Fm.Surface),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(BorderStroke(3.dp, Fm.Focus), shape = TileShape),
            ),
            glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(Fm.Amethyst.copy(alpha = 0.6f), 22.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        ) {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(Fm.Amethyst, Color(0xFF3B2160), Fm.Noir)),
                ),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.take(1).uppercase(), style = MaterialTheme.typography.displayLarge, color = Color.White.copy(alpha = 0.92f))
                if (image != null) AsyncImage(image, name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                if (managing) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Icon(Ic.Settings, "Edit", Modifier.size(34.dp), tint = Color.White)
                    }
                }
                if (locked) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(8.dp).size(28.dp).background(Fm.Noir.copy(alpha = 0.8f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Ic.Lock, "Locked", Modifier.size(15.dp), tint = Fm.Accent) }
                }
            }
        }
        Text(
            name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (focused) Fm.Text else Fm.TextDim, modifier = Modifier.padding(top = 18.dp),
        )
        val sub = listOfNotNull(caption, "Watching now".takeIf { current && !managing }).joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelMedium, color = if (current) Fm.Accent else Fm.TextFaint, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun AddTile(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(150.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            modifier = Modifier.size(150.dp).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(TileShape),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f), focusedContainerColor = Fm.Focus, pressedContainerColor = Fm.Focus),
            border = ClickableSurfaceDefaults.border(
                border = androidx.tv.material3.Border(BorderStroke(1.5.dp, Color.White.copy(alpha = 0.18f)), shape = TileShape),
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Ic.Add, null, Modifier.size(44.dp), tint = if (focused) Fm.OnFocus else Fm.TextDim)
            }
        }
        Text("Add profile", style = MaterialTheme.typography.titleMedium, color = if (focused) Fm.Text else Fm.TextDim, modifier = Modifier.padding(top = 18.dp))
    }
}

/** One step of the PIN flows on this screen. */
private sealed class PinAsk(val title: String, val subtitle: String) {
    class Unlock(val user: SavedUser) : PinAsk("Enter ${user.name}'s PIN", "This profile is locked")
    class SignOut(val user: SavedUser) : PinAsk("Enter ${user.name}'s PIN", "to sign this profile out")
    class Verify(val user: SavedUser, val then: PinAsk?) : PinAsk("Enter the current PIN", "")
    class New(val user: SavedUser, mismatch: Boolean = false) :
        PinAsk("Choose a 4-digit PIN", if (mismatch) "The PINs didn't match. Try again." else "Asked when someone switches to ${user.name}")
    class Confirm(val user: SavedUser, val pin: String) : PinAsk("Enter it again", "")
}
