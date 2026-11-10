package com.finmax.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.finmax.BuildConfig
import com.finmax.FinmaxApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** A newer build published as a GitHub release. */
data class Release(val versionName: String, val apkUrl: String, val notes: String)

/**
 * App updates from the latest GitHub release of [BuildConfig.UPDATE_REPO]: its tag (`v0.3.0`) is
 * compared with this build's version name, and its `.apk` asset is handed to Android's installer,
 * which asks the user to confirm.
 */
object Updater {
    val configured get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** Set by [check] when a newer build exists (the Settings icon shows a dot). */
    var available by mutableStateOf<Release?>(null)
        private set

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val body = FinmaxApp.instance.http.newCall(req).execute().use { r ->
            if (r.code == 404) return@withContext null // no release published yet
            check(r.isSuccessful) { "GitHub answered ${r.code}" }
            r.body!!.string()
        }
        val j = JSONObject(body)
        val tag = j.getString("tag_name").removePrefix("v")
        val assets = j.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.getString("name").endsWith(".apk") }?.getString("browser_download_url")
            ?: return@withContext null
        Release(tag, apk, j.optString("body").trim())
            .takeIf { newer(tag, BuildConfig.VERSION_NAME) }
            .also { withContext(Dispatchers.Main) { available = it } }
    }

    /** "0.10.0" > "0.9.2": compares dot-separated numbers. */
    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Streams the APK into an install session; Android then shows its own "Install update?" screen. */
    suspend fun install(context: Context, rel: Release, progress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            FinmaxApp.instance.http.newCall(Request.Builder().url(rel.apkUrl).build()).execute().use { r ->
                check(r.isSuccessful) { "Download failed (${r.code})" }
                val body = r.body!!
                val total = body.contentLength().takeIf { it > 0 }
                session.openWrite("finmax.apk", 0, total ?: -1).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            total?.let { progress(done.toFloat() / it) }
                        }
                    }
                    session.fsync(out)
                }
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val pending = PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }
}

/** The installer reports back here; when it needs the user's OK, show its confirmation screen. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
            context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
