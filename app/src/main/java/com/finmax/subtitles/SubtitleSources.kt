package com.finmax.subtitles

import android.content.Context
import com.finmax.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jellyfin.sdk.api.client.extensions.subtitleApi
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.RemoteSubtitleInfo
import java.util.UUID

/** Text subtitle codecs Jellyfin can convert to SRT on the fly (so FINMAX can render and retime them). */
val TEXT_SUB_CODECS = setOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "mov_text", "ttml", "text", "smi", "sami", "microdvd", "sub")

val MediaStream.isTextSubtitle get() = (codec?.lowercase() in TEXT_SUB_CODECS) || isTextSubtitleStream

/** Stable identity of a subtitle for remembering its sync correction between sessions. */
fun MediaStream.syncKey(): String =
    if (isExternal) "ext:" + (path?.substringAfterLast('/')?.substringAfterLast('\\') ?: "$index") else "emb:$index:${codec}:${language}"

/**
 * Fetches subtitle text from Jellyfin (any text track, embedded or external, converted to SRT by the
 * server), searches/previews/downloads remote subtitles via the server's subtitle providers, and
 * remembers accepted sync corrections per media + subtitle.
 */
class SubtitleSources(context: Context, private val http: OkHttpClient) {
    // Embedded subtitles: the server reads the whole file before answering (minutes from a cloud mount).
    private val patient = http.newBuilder().readTimeout(20, java.util.concurrent.TimeUnit.MINUTES).build()
    private val prefs = context.getSharedPreferences("finmax_subsync", Context.MODE_PRIVATE)
    private val cache = object : LinkedHashMap<String, List<Cue>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Cue>>?) = size > 8
    }

    private suspend fun fetch(session: Session, path: String, client: OkHttpClient = http): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(session.server.url.trimEnd('/') + path).header("Authorization", session.authHeader).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("Server returned ${r.code} for subtitle")
            r.body!!.string()
        }
    }

    suspend fun cues(session: Session, itemId: UUID, mediaSourceId: String, streamIndex: Int): List<Cue> {
        val key = "$itemId/$mediaSourceId/$streamIndex"
        synchronized(cache) { cache[key] }?.let { return it }
        val text = fetch(session, "/Videos/$itemId/$mediaSourceId/Subtitles/$streamIndex/0/Stream.srt", patient)
        val cues = withContext(Dispatchers.Default) { SubtitleParser.parse(text, "srt") }
        synchronized(cache) { cache[key] = cues }
        return cues
    }

    fun invalidate(itemId: UUID) = synchronized(cache) { cache.keys.removeAll { it.startsWith("$itemId/") } }

    /** Requires a subtitle provider plugin on the server (e.g. OpenSubtitles); returns empty otherwise. */
    suspend fun searchRemote(session: Session, itemId: UUID, language3: String): List<RemoteSubtitleInfo> =
        session.api.subtitleApi.searchRemoteSubtitles(itemId = itemId, language = language3).content

    /** Downloads a remote subtitle's text for preview only; nothing is saved on the server. */
    suspend fun previewRemote(session: Session, info: RemoteSubtitleInfo): List<Cue> {
        val id = info.id ?: error("Subtitle has no id")
        val text = fetch(session, "/Providers/Subtitles/Subtitles/" + java.net.URLEncoder.encode(id, "UTF-8"))
        return withContext(Dispatchers.Default) { SubtitleParser.parse(text, info.format) }
    }

    /** Saves a remote subtitle next to the media on the server; it appears as a new external track. */
    suspend fun downloadRemote(session: Session, itemId: UUID, info: RemoteSubtitleInfo) {
        session.api.subtitleApi.downloadRemoteSubtitles(itemId = itemId, subtitleId = info.id ?: error("Subtitle has no id"))
    }

    fun rememberedTiming(itemId: UUID, key: String): Timing? =
        prefs.getString("$itemId|$key", null)?.split(',')?.takeIf { it.size == 2 }?.let {
            Timing(it[0].toLong(), it[1].toDouble())
        }

    fun rememberTiming(itemId: UUID, key: String, timing: Timing) {
        val k = "$itemId|$key"
        if (timing.isIdentity) prefs.edit().remove(k).apply() else prefs.edit().putString(k, "${timing.offsetMs},${timing.scale}").apply()
    }
}
