package com.finmax.ui.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.finmax.FinmaxApp
import com.finmax.ui.components.PillButton
import com.finmax.ui.components.focusMemory
import com.finmax.ui.theme.Fm
import kotlinx.coroutines.launch

/** Switch between signed-in users and servers, add accounts, sign out. */
@Composable
fun ProfilesScreen() {
    val app = FinmaxApp.instance
    val sessions = app.sessions
    val current = sessions.session.value
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) } // bump to re-read saved lists after changes

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 96.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Text("Profiles & servers", style = MaterialTheme.typography.displayMedium)
            Text("Signed in as ${current?.user?.name} on ${current?.server?.name}", color = Fm.TextDim, style = MaterialTheme.typography.bodyLarge)
        }
        sessions.servers.forEach { server ->
            item(key = server.id + version) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(server.name, style = MaterialTheme.typography.titleLarge)
                    Text(server.url, style = MaterialTheme.typography.bodyMedium, color = Fm.TextFaint)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        sessions.usersOf(server.id).forEach { u ->
                            val isCurrent = u.userId == current?.user?.userId && u.serverId == current.server.id
                            PillButton(
                                if (isCurrent) "${u.name} (current)" else u.name,
                                { if (!isCurrent) sessions.activate(u) },
                                primary = isCurrent,
                                modifier = Modifier.focusMemory("u/${u.serverId}/${u.userId}", initial = isCurrent),
                            )
                        }
                        PillButton("Add account", { sessions.deactivate() }, icon = Icons.Filled.Add)
                        Spacer(Modifier.width(24.dp))
                        PillButton("Forget server", { sessions.removeServer(server); version++ }, icon = Icons.Filled.Delete)
                    }
                }
            }
        }
        item {
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PillButton("Add another server", { sessions.deactivate() }, icon = Icons.Filled.Add)
                if (current != null) {
                    PillButton("Sign out ${current.user.name}", { scope.launch { sessions.signOut(current.user) } }, icon = Icons.AutoMirrored.Filled.Logout)
                }
            }
        }
    }
}
