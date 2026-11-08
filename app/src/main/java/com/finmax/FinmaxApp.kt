package com.finmax

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.finmax.data.MediaRepository
import com.finmax.data.SecureStore
import com.finmax.data.SessionManager
import com.finmax.data.SettingsStore
import com.finmax.subtitles.BazarrClient
import com.finmax.subtitles.SubtitleSources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.jellyfin.sdk.android.androidDevice
import org.jellyfin.sdk.createJellyfin
import org.jellyfin.sdk.model.ClientInfo
import java.util.concurrent.TimeUnit

/** Process-wide singletons. Plain constructor wiring; small enough not to need a DI framework. */
class FinmaxApp : Application(), SingletonImageLoader.Factory {
    lateinit var secure: SecureStore private set
    lateinit var settings: SettingsStore private set
    lateinit var sessions: SessionManager private set
    lateinit var http: OkHttpClient private set
    lateinit var subtitles: SubtitleSources private set
    lateinit var bazarr: BazarrClient private set

    /** Survives screen disposal; used for final progress reports and other fire-and-forget calls. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var repoCache: Pair<Any, MediaRepository>? = null

    /** Repository for the active session; screens are only shown while a session exists. */
    val repo: MediaRepository
        get() {
            val s = sessions.session.value ?: error("No active session")
            repoCache?.let { (key, r) -> if (key === s) return r }
            return MediaRepository(s).also { repoCache = s to it }
        }

    override fun onCreate() {
        super.onCreate()
        instance = this
        secure = SecureStore(this)
        settings = SettingsStore(this, secure)
        http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        val okHttpFactory = org.jellyfin.sdk.api.okhttp.OkHttpFactory(http)
        val jellyfin = createJellyfin {
            context = this@FinmaxApp
            apiClientFactory = com.finmax.data.IoApiClientFactory(okHttpFactory)
            socketConnectionFactory = okHttpFactory
            clientInfo = ClientInfo(name = "FINMAX", version = BuildConfig.VERSION_NAME)
            deviceInfo = androidDevice(this@FinmaxApp)
        }
        sessions = SessionManager(this, jellyfin, secure)
        sessions.restore()
        subtitles = SubtitleSources(this, http)
        bazarr = BazarrClient(http, settings)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader = ImageLoader.Builder(context)
        .components { add(OkHttpNetworkFetcherFactory(callFactory = { http })) }
        .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(256L * 1024 * 1024).build() }
        .crossfade(250)
        .build()

    companion object {
        lateinit var instance: FinmaxApp private set
    }
}
