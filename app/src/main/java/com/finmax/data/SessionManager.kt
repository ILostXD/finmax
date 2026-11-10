package com.finmax.data

import android.content.Context
import org.jellyfin.sdk.Jellyfin
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.quickConnectApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.api.client.extensions.userApi
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.QuickConnectResult
import org.jellyfin.sdk.model.api.UserDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SavedServer(val id: String, val name: String, val url: String)

data class SavedUser(
    val serverId: String,
    val userId: String,
    val name: String,
    val token: String,
    val imageTag: String?,
)

/** An authenticated connection to one user on one server. */
class Session(val server: SavedServer, val user: SavedUser, val api: ApiClient, private val jellyfin: Jellyfin) {
    val userId: UUID = UUID.fromString(user.userId.withDashes())

    /** Header for raw HTTP calls (ExoPlayer, subtitle downloads) outside the SDK client. */
    val authHeader: String
        get() = AuthorizationHeaderBuilder.buildHeader(
            clientName = jellyfin.clientInfo!!.name,
            clientVersion = jellyfin.clientInfo!!.version,
            deviceId = jellyfin.deviceInfo!!.id,
            deviceName = jellyfin.deviceInfo!!.name,
            accessToken = user.token,
        )
}

internal fun String.withDashes(): String =
    if (length == 32) "${substring(0, 8)}-${substring(8, 12)}-${substring(12, 16)}-${substring(16, 20)}-${substring(20)}" else this

/**
 * Owns saved servers/users, the active session, and the login flows (password + Quick Connect).
 * Access tokens are persisted only through [SecureStore].
 */
class SessionManager(context: Context, val jellyfin: Jellyfin, private val secure: SecureStore) {
    private val prefs = context.getSharedPreferences("finmax_sessions", Context.MODE_PRIVATE)
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    var servers: List<SavedServer> = loadServers()
        private set
    var users: List<SavedUser> = loadUsers()
        private set

    /** Restores the last active user, if any. Token validity is checked lazily by the first API call. */
    fun restore(): Boolean {
        val serverId = prefs.getString("activeServer", null) ?: return false
        val userId = prefs.getString("activeUser", null) ?: return false
        val user = users.firstOrNull { it.serverId == serverId && it.userId == userId } ?: return false
        activate(user)
        return true
    }

    fun apiFor(url: String, token: String? = null): ApiClient = jellyfin.createApi(baseUrl = url, accessToken = token)

    /**
     * Resolves what the user typed (host, host:port, full URL) to a reachable Jellyfin server,
     * trying the SDK's candidate addresses (http/https, default ports) and keeping the best one.
     */
    suspend fun findServer(input: String): Result<SavedServer> = runCatching {
        val candidates = jellyfin.discovery.getRecommendedServers(input.trim(), RecommendedServerInfoScore.OK)
        val best = candidates.maxByOrNull { it.score.ordinal }
            ?: error("No Jellyfin server answered at \"$input\". Check the address and that the server is running.")
        val info = best.systemInfo.getOrNull() ?: error("Server found but its info could not be read.")
        SavedServer(id = info.id ?: best.address, name = info.serverName ?: best.address, url = best.address)
    }

    suspend fun publicUsers(server: SavedServer): List<UserDto> =
        runCatching { apiFor(server.url).userApi.getPublicUsers().content }.getOrDefault(emptyList())

    suspend fun login(server: SavedServer, username: String, password: String): Result<Unit> = runCatching {
        val api = apiFor(server.url)
        val result = try {
            api.userApi.authenticateUserByName(AuthenticateUserByName(username = username, pw = password)).content
        } catch (e: InvalidStatusException) {
            if (e.status == 401) error("Incorrect username or password.") else throw e
        }
        save(server, result)
    }

    suspend fun quickConnectEnabled(server: SavedServer): Boolean =
        runCatching { apiFor(server.url).quickConnectApi.getQuickConnectEnabled().content }.getOrDefault(false)

    suspend fun quickConnectStart(server: SavedServer): QuickConnectResult =
        apiFor(server.url).quickConnectApi.initiateQuickConnect().content

    /** @return true when approved, false while still pending. Throws when expired/unknown. */
    suspend fun quickConnectPoll(server: SavedServer, secret: String): Boolean =
        apiFor(server.url).quickConnectApi.getQuickConnectState(secret).content.authenticated

    suspend fun quickConnectFinish(server: SavedServer, secret: String) {
        val result = apiFor(server.url).userApi.authenticateWithQuickConnect(QuickConnectDto(secret = secret)).content
        save(server, result)
    }

    private fun save(server: SavedServer, result: AuthenticationResult) {
        val dto = result.user ?: error("Server returned no user")
        val user = SavedUser(
            serverId = server.id,
            userId = dto.id.toString(),
            name = dto.name ?: "User",
            token = result.accessToken ?: error("Server returned no access token"),
            imageTag = dto.primaryImageTag,
        )
        servers = servers.filterNot { it.id == server.id } + server
        users = users.filterNot { it.serverId == user.serverId && it.userId == user.userId } + user
        persist()
        activate(user)
    }

    fun activate(user: SavedUser) {
        val server = servers.first { it.id == user.serverId }
        _session.value = Session(server, user, apiFor(server.url, user.token), jellyfin)
        prefs.edit().putString("activeServer", server.id).putString("activeUser", user.userId).apply()
    }

    /** Leaves the current session without forgetting it (used for switching user/server). */
    fun deactivate() {
        _session.value = null
        prefs.edit().remove("activeServer").remove("activeUser").apply()
    }

    suspend fun signOut(user: SavedUser) {
        val server = servers.firstOrNull { it.id == user.serverId }
        if (server != null) runCatching { apiFor(server.url, user.token).sessionApi.reportSessionEnded() }
        users = users.filterNot { it.serverId == user.serverId && it.userId == user.userId }
        secure.put("token_${user.serverId}_${user.userId}", null)
        setPin(user, null)
        persist()
        if (_session.value?.user?.userId == user.userId) deactivate()
    }

    fun removeServer(server: SavedServer) {
        usersOf(server.id).forEach { secure.put("token_${it.serverId}_${it.userId}", null); setPin(it, null) }
        users = users.filterNot { it.serverId == server.id }
        servers = servers.filterNot { it.id == server.id }
        persist()
        if (_session.value?.server?.id == server.id) deactivate()
    }

    fun usersOf(serverId: String) = users.filter { it.serverId == serverId }

    // ---- Profile PINs (this device only; the server knows nothing of them) --------------------

    private fun pinKey(u: SavedUser) = "pin_${u.serverId}_${u.userId}"
    private fun hash(u: SavedUser, pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest("${u.userId}:$pin".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun hasPin(u: SavedUser) = secure.get(pinKey(u)) != null
    fun checkPin(u: SavedUser, pin: String) = secure.get(pinKey(u)).let { it == null || it == hash(u, pin) }
    fun setPin(u: SavedUser, pin: String?) = secure.put(pinKey(u), pin?.let { hash(u, it) })

    private fun persist() {
        prefs.edit().putString(
            "servers",
            JSONArray(servers.map { JSONObject().put("id", it.id).put("name", it.name).put("url", it.url) }).toString(),
        ).putString(
            "users",
            JSONArray(
                users.map {
                    JSONObject().put("serverId", it.serverId).put("userId", it.userId).put("name", it.name)
                        .put("imageTag", it.imageTag ?: "")
                },
            ).toString(),
        ).apply()
        users.forEach { secure.put("token_${it.serverId}_${it.userId}", it.token) }
    }

    private fun loadServers(): List<SavedServer> = runCatching {
        val arr = JSONArray(prefs.getString("servers", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            SavedServer(o.getString("id"), o.getString("name"), o.getString("url"))
        }
    }.getOrDefault(emptyList())

    private fun loadUsers(): List<SavedUser> = runCatching {
        val arr = JSONArray(prefs.getString("users", "[]"))
        (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val serverId = o.getString("serverId")
            val userId = o.getString("userId")
            val token = secure.get("token_${serverId}_$userId") ?: return@mapNotNull null
            SavedUser(serverId, userId, o.getString("name"), token, o.optString("imageTag").ifEmpty { null })
        }
    }.getOrDefault(emptyList())
}
