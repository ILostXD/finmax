package com.finmax.ui.login

import com.finmax.ui.components.Ic

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.finmax.FinmaxApp
import com.finmax.data.SavedServer
import com.finmax.ui.components.FocusTile
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.Wordmark
import com.finmax.ui.components.friendlyError
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.model.api.UserDto

private sealed interface Step {
    data object Servers : Step
    data object AddServer : Step
    data class Users(val server: SavedServer) : Step
    data class Password(val server: SavedServer, val username: String) : Step
    data class QuickConnect(val server: SavedServer) : Step
}

@Composable
fun LoginFlow() {
    val sessions = FinmaxApp.instance.sessions
    var step by remember { mutableStateOf<Step>(if (sessions.servers.isEmpty()) Step.AddServer else Step.Servers) }
    val back: Step? = when (val s = step) {
        Step.Servers -> null
        Step.AddServer -> if (sessions.servers.isEmpty()) null else Step.Servers
        is Step.Users -> Step.Servers
        is Step.Password -> Step.Users(s.server)
        is Step.QuickConnect -> Step.Users(s.server)
    }
    BackHandler(enabled = back != null) { step = back!! }

    Box(Modifier.fillMaxSize()) {
        com.finmax.ui.components.BrandBackdrop()
        Column(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = Fm.SafeV + 6.dp)) {
            Wordmark(32.dp)
            Spacer(Modifier.height(22.dp))
            when (val s = step) {
                Step.Servers -> ServerList(onPick = { step = Step.Users(it) }, onAdd = { step = Step.AddServer })
                Step.AddServer -> AddServer(onFound = { step = Step.Users(it) })
                is Step.Users -> UserPicker(
                    s.server,
                    onQuickConnect = { step = Step.QuickConnect(s.server) },
                    onPassword = { step = Step.Password(s.server, it) },
                )
                is Step.Password -> PasswordLogin(s.server, s.username)
                is Step.QuickConnect -> QuickConnectLogin(s.server, onCancel = { step = Step.Users(s.server) })
            }
        }
    }
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Text(title, style = MaterialTheme.typography.displayMedium)
    Spacer(Modifier.height(8.dp))
    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Fm.TextDim)
    Spacer(Modifier.height(18.dp))
}

@Composable
private fun ServerList(onPick: (SavedServer) -> Unit, onAdd: () -> Unit) {
    val sessions = FinmaxApp.instance.sessions
    Heading("Choose a server", "Pick up where you left off, or connect to another Jellyfin server.")
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.offset(x = (-12).dp)) {
        items(sessions.servers, key = { it.id }) { server ->
            val first = server == sessions.servers.first()
            ChoiceTile(server.name, server.url, Ic.Server, onClick = { onPick(server) }, requestFocus = first)
        }
        item { ChoiceTile("Add server", "Enter an address", null, onClick = onAdd) }
    }
}

@Composable
private fun ChoiceTile(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector?,
    onClick: () -> Unit,
    imageUrl: String? = null,
    requestFocus: Boolean = false,
) {
    val req = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
    FocusTile(onClick = onClick, modifier = Modifier.width(220.dp).height(150.dp).focusRequester(req)) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Fm.SurfaceHigh), contentAlignment = Alignment.Center) {
                when {
                    imageUrl != null -> AsyncImage(imageUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    icon != null -> Icon(icon, null, tint = Fm.Accent)
                    else -> Text("+", style = MaterialTheme.typography.headlineMedium, color = Fm.Accent)
                }
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Fm.TextDim, maxLines = 1)
            }
        }
    }
}

@Composable
private fun AddServer(onFound: (SavedServer) -> Unit) {
    val sessions = FinmaxApp.instance.sessions
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val discovered = remember { mutableStateListOf<Pair<String, String>>() }

    LaunchedEffect(Unit) {
        // UDP broadcast discovery on the local network; a few seconds is plenty.
        withTimeoutOrNull(6_000) {
            runCatching {
                // The SDK's UDP discovery does blocking socket work: keep it off the main thread.
                sessions.jellyfin.discovery.discoverLocalServers().flowOn(Dispatchers.IO).collect { info ->
                    val addr = info.address ?: return@collect
                    if (discovered.none { it.second == addr }) discovered += (info.name ?: addr) to addr
                }
            }
        }
    }

    fun connect(input: String) {
        if (input.isBlank() || busy) return
        busy = true
        error = null
        scope.launch {
            sessions.findServer(input).onSuccess(onFound).onFailure { error = friendlyError(it) }
            busy = false
        }
    }

    Heading("Connect to Jellyfin", "Enter your server address, e.g. 192.168.1.20:8096 or jellyfin.example.com.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        FmTextField(address, { address = it }, "Server address", Modifier.width(460.dp), keyboardType = KeyboardType.Uri, onDone = { connect(address) }, requestFocus = true)
        Spacer(Modifier.width(16.dp))
        PillButton(if (busy) "Connecting…" else "Connect", { connect(address) }, primary = true, enabled = !busy)
    }
    error?.let { Text(it, color = Fm.Error, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 14.dp)) }
    Spacer(Modifier.height(36.dp))
    Text(
        if (discovered.isEmpty()) "Looking for servers on your network…" else "Found on your network",
        style = MaterialTheme.typography.titleMedium, color = Fm.TextDim,
    )
    Spacer(Modifier.height(14.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.offset(x = (-12).dp)) {
        items(discovered, key = { it.second }) { (name, addr) ->
            ChoiceTile(name, addr, Ic.Server, onClick = { address = addr; connect(addr) })
        }
    }
}

@Composable
private fun UserPicker(server: SavedServer, onQuickConnect: () -> Unit, onPassword: (String) -> Unit) {
    val sessions = FinmaxApp.instance.sessions
    var publicUsers by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    var qcEnabled by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(server) {
        qcEnabled = sessions.quickConnectEnabled(server)
        publicUsers = sessions.publicUsers(server)
    }
    val saved = sessions.usersOf(server.id)

    Heading("Who's watching?", "Signing in to ${server.name}")
    // One row: two rows of tiles don't fit on a TV screen. Saved profiles first, then ways to add one.
    Text(
        if (saved.isNotEmpty()) "Signed in on this TV, or add an account" else "Add an account",
        style = MaterialTheme.typography.titleMedium, color = Fm.TextDim,
    )
    Spacer(Modifier.height(12.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(20.dp), contentPadding = PaddingValues(12.dp), modifier = Modifier.offset(x = (-12).dp)) {
        items(saved, key = { "saved/" + it.userId }) { u ->
            val img = u.imageTag?.let { "${server.url.trimEnd('/')}/Users/${u.userId}/Images/Primary?tag=$it&maxWidth=120" }
            ChoiceTile(u.name, "Continue", Ic.Person, { sessions.activate(u) }, img, requestFocus = u == saved.first())
        }
        if (qcEnabled == true) {
            item { ChoiceTile("Quick Connect", "Approve from your phone", null, onQuickConnect, requestFocus = saved.isEmpty()) }
        }
        items(publicUsers.filter { p -> saved.none { it.userId.replace("-", "") == p.id.toString().replace("-", "") } }, key = { it.id }) { u ->
            val img = u.primaryImageTag?.let { "${server.url.trimEnd('/')}/Users/${u.id}/Images/Primary?tag=$it&maxWidth=120" }
            ChoiceTile(u.name ?: "User", "Password", Ic.Person, { onPassword(u.name ?: "") }, img)
        }
        item {
            ChoiceTile("Other user", "Type a username", Ic.Person, { onPassword("") }, requestFocus = saved.isEmpty() && qcEnabled == false)
        }
    }
    if (qcEnabled == false) {
        Text("Quick Connect is turned off on this server (Dashboard → General → Quick Connect).", color = Fm.TextFaint, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
private fun PasswordLogin(server: SavedServer, initialUser: String) {
    val sessions = FinmaxApp.instance.sessions
    val scope = rememberCoroutineScope()
    var user by remember { mutableStateOf(initialUser) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun submit() {
        if (busy || user.isBlank()) return
        busy = true
        error = null
        scope.launch {
            sessions.login(server, user.trim(), pass).onFailure { error = friendlyError(it) }
            busy = false
        }
    }
    Heading("Sign in", "${server.name} · ${server.url}")
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        FmTextField(user, { user = it }, "Username", Modifier.width(420.dp), requestFocus = initialUser.isEmpty())
        FmTextField(pass, { pass = it }, "Password", Modifier.width(420.dp), password = true, onDone = ::submit, requestFocus = initialUser.isNotEmpty())
        PillButton(if (busy) "Signing in…" else "Sign in", ::submit, primary = true, enabled = !busy)
        error?.let { Text(it, color = Fm.Error, style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun QuickConnectLogin(server: SavedServer, onCancel: () -> Unit) {
    val sessions = FinmaxApp.instance.sessions
    var code by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Requesting a code…") }
    var failed by remember { mutableStateOf(false) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(server, attempt) {
        failed = false
        code = null
        val result = runCatching { sessions.quickConnectStart(server) }.getOrElse {
            status = if (it is InvalidStatusException && it.status == 401) "Quick Connect is disabled on this server." else friendlyError(it)
            failed = true
            return@LaunchedEffect
        }
        code = result.code
        status = "Waiting for approval…"
        // Codes expire server-side after a few minutes; the state call then fails and we offer a new code.
        while (true) {
            delay(3_000)
            val approved = runCatching { sessions.quickConnectPoll(server, result.secret) }.getOrElse {
                android.util.Log.w("FINMAX", "Quick Connect poll failed", it)
                status = "This code has expired."
                failed = true
                return@LaunchedEffect
            }
            if (approved) {
                status = "Approved! Signing in…"
                runCatching { sessions.quickConnectFinish(server, result.secret) }.onFailure {
                    status = friendlyError(it)
                    failed = true
                }
                return@LaunchedEffect
            }
        }
    }

    Heading("Quick Connect", "On a device that's already signed in, open Jellyfin → your profile → Quick Connect, and enter this code.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.border(BorderStroke(2.dp, Fm.Accent.copy(alpha = 0.6f)), RoundedCornerShape(16.dp)).background(Fm.Surface, RoundedCornerShape(16.dp))
                .padding(horizontal = 40.dp, vertical = 24.dp),
        ) {
            Text(
                code?.chunked(3)?.joinToString(" ") ?: "· · ·",
                fontSize = 64.sp, letterSpacing = 6.sp, color = Fm.Text,
                style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.width(40.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(status, style = MaterialTheme.typography.titleLarge, color = if (failed) Fm.Error else Fm.TextDim)
            if (failed) PillButton("Get a new code", { attempt++ }, primary = true, modifier = Modifier.focusRequesterOnShow())
            PillButton("Use a password instead", onCancel, modifier = if (failed) Modifier else Modifier.focusRequesterOnShow())
        }
    }
}

@Composable
private fun Modifier.focusRequesterOnShow(): Modifier {
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(50); runCatching { req.requestFocus() } }
    return focusRequester(req)
}

/**
 * TV-friendly text field: a focusable box that only opens the on-screen keyboard when selected
 * (a plain BasicTextField pops the keyboard as soon as D-pad focus passes over it).
 */
@Composable
fun FmTextField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
    onDone: (() -> Unit)? = null,
    requestFocus: Boolean = false,
) {
    var editing by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val boxReq = remember { FocusRequester() }
    val fieldReq = remember { FocusRequester() }
    if (requestFocus) LaunchedEffect(Unit) { delay(80); runCatching { boxReq.requestFocus() } }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // The field reports "not focused" when it first appears; only a real loss of focus ends editing.
    var hadFocus by remember { mutableStateOf(false) }
    LaunchedEffect(editing) {
        hadFocus = false
        if (editing) {
            delay(30)
            runCatching { fieldReq.requestFocus() }
            keyboard?.show()
        }
    }
    val shape = RoundedCornerShape(percent = 50)
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (focused || editing) Fm.Accent else Fm.TextDim)
        Spacer(Modifier.height(6.dp))
        if (editing) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = Fm.Text, fontSize = 18.sp),
                cursorBrush = SolidColor(Fm.Accent),
                visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = {
                    editing = false
                    onDone?.invoke()
                }),
                modifier = Modifier.fillMaxWidth().focusRequester(fieldReq)
                    .onFocusChanged {
                        if (it.isFocused) hadFocus = true
                        else if (hadFocus && editing) editing = false
                    }
                    .background(Fm.Surface, shape).border(BorderStroke(2.dp, Fm.Accent), shape)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        } else {
            androidx.tv.material3.Surface(
                onClick = { editing = true },
                modifier = Modifier.fillMaxWidth().focusRequester(boxReq).onFocusChanged { focused = it.isFocused },
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Fm.Surface, focusedContainerColor = Fm.SurfaceHigh),
                border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                    border = androidx.tv.material3.Border(BorderStroke(2.dp, Fm.Outline), shape = shape),
                    focusedBorder = androidx.tv.material3.Border(BorderStroke(2.dp, Fm.Accent), shape = shape),
                ),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
            ) {
                Text(
                    when {
                        value.isEmpty() -> "Select to type"
                        password -> "•".repeat(value.length)
                        else -> value
                    },
                    color = if (value.isEmpty()) Fm.TextFaint else Fm.Text,
                    fontSize = 18.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}
