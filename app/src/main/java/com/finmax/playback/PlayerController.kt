package com.finmax.playback

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.finmax.FinmaxApp
import com.finmax.data.Session
import com.finmax.subtitles.CueTrack
import com.finmax.subtitles.Timing
import com.finmax.subtitles.isTextSubtitle
import com.finmax.subtitles.syncKey
import com.finmax.ui.components.friendlyError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.api.client.extensions.mediaSegmentsApi
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.sessionApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaSegmentDto
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.TranscodingInfo
import java.util.UUID

private const val TICKS_PER_MS = 10_000L

/** Request for one playback attempt. */
data class PlayRequest(
    val itemId: UUID,
    val startMs: Long?,
    val mediaSourceId: String? = null,
    val audioIndex: Int? = null,
    /** Jellyfin subtitle stream index; -1 = off; null = automatic choice. */
    val subtitleIndex: Int? = null,
    val forceTranscode: Boolean = false,
    /** Queue = this playlist's items; otherwise an episode's queue is its show. */
    val playlistId: UUID? = null,
)

/**
 * Owns one ExoPlayer instance for the player screen: asks Jellyfin how to play (PlaybackInfo with
 * this device's profile), builds the stream, maps Jellyfin audio/subtitle streams onto player
 * tracks, reports progress, and drives the FINMAX subtitle renderer (text subtitles with timing).
 */
@OptIn(UnstableApi::class)
class PlayerController(context: Context, private val app: FinmaxApp, private val session: Session, adopted: ExoPlayer? = null, mpv: Boolean = false) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repo = app.repo

    /** The player was a background video's, already playing the requested item. */
    val adoptedPlayer = adopted != null

    /** Set while [player] is a Home preview's player that is already playing the requested item. */
    private var adopting = adopted != null

    val player: Player = if (mpv && adopted == null) MpvPlayer(context, session.authHeader) else adopted?.apply {
        // Turn the muted-capable, subtitle-less preview player into a full one.
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        setHandleAudioBecomingNoisy(true)
        volume = 1f
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        playWhenReady = true
    } ?: ExoPlayer.Builder(
        context,
        object : DefaultRenderersFactory(context) {
            // FFmpeg is for audio only: software video decoding would swamp this device.
            override fun buildVideoRenderers(
                ctx: Context, mode: Int, selector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
                fallback: Boolean, handler: android.os.Handler, listener: androidx.media3.exoplayer.video.VideoRendererEventListener,
                joiningMs: Long, out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
            ) = super.buildVideoRenderers(ctx, EXTENSION_RENDERER_MODE_OFF, selector, fallback, handler, listener, joiningMs, out)
        }.setEnableDecoderFallback(true)
            // The device decodes none of AC3, E-AC3, DTS or TrueHD; FFmpeg does (see DeviceCapabilities).
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON),
    )
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                OkHttpDataSource.Factory(app.http).setDefaultRequestProperties(mapOf("Authorization" to session.authHeader)),
            ),
        )
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .build()

    /** mpv draws every embedded subtitle itself (styled ASS included); finmax only draws external ones. */
    val isMpv = player is MpvPlayer

    /**
     * Set when this title should play in mpv instead (an ASS/SSA subtitle is chosen and the engine
     * setting is Automatic): the position to continue from. The screen restarts on mpv.
     */
    var switchToMpvAt by mutableStateOf<Long?>(null); private set
    private val mediaSession = MediaSession.Builder(context, player).setId("finmax-${System.nanoTime()}").build()

    // ---- Observable state -------------------------------------------------------------------
    var item by mutableStateOf<BaseItemDto?>(null); private set
    var source by mutableStateOf<MediaSourceInfo?>(null); private set
    var method by mutableStateOf(PlayMethod.DIRECT_PLAY); private set
    var audioIndex by mutableStateOf<Int?>(null); private set
    var subtitleIndex by mutableStateOf<Int?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(true); private set
    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(false); private set
    var ended by mutableStateOf(false); private set
    var speed by mutableFloatStateOf(1f); private set
    var segments by mutableStateOf<List<MediaSegmentDto>>(emptyList()); private set
    /** What plays around this title: the show's episodes in order, or the playlist's items. */
    var queue by mutableStateOf<List<BaseItemDto>>(emptyList()); private set
    var queueIsPlaylist by mutableStateOf(false); private set
    var nextEpisode by mutableStateOf<BaseItemDto?>(null); private set
    var previousEpisode by mutableStateOf<BaseItemDto?>(null); private set
    var transcodeInfo by mutableStateOf<TranscodingInfo?>(null); private set
    var notice by mutableStateOf<String?>(null)

    /** Text subtitle rendering state (FINMAX renderer). Image subtitles use ExoPlayer's own renderer. */
    var cues by mutableStateOf<CueTrack?>(null); private set
    var cuesLoading by mutableStateOf(false); private set
    var timing by mutableStateOf(Timing()); private set

    /** When set, the renderer shows these instead (sync assistant preview); nothing is persisted. */
    var previewCues by mutableStateOf<CueTrack?>(null)
    var previewTiming by mutableStateOf<Timing?>(null)

    private var playSessionId: String? = null
    private var request: PlayRequest? = null
    private var progressJob: Job? = null
    private var cueJob: Job? = null
    private var triedFallback = false
    private var reportedStart = false

    val streams: List<MediaStream> get() = source?.mediaStreams.orEmpty()
    val audioStreams get() = streams.filter { it.type == MediaStreamType.AUDIO }
    val subtitleStreams get() = streams.filter { it.type == MediaStreamType.SUBTITLE }
    val currentSubtitle get() = subtitleStreams.firstOrNull { it.index == subtitleIndex }
    val positionMs get() = player.currentPosition
    val durationMs get() = player.duration.takeIf { it != C.TIME_UNSET } ?: ((item?.runTimeTicks ?: 0) / TICKS_PER_MS)

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (reportedStart) reportProgress()
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    loading = false
                    if (!reportedStart) reportStart()
                }
                if (state == Player.STATE_ENDED) ended = true
            }

            override fun onTracksChanged(tracks: Tracks) = applyTrackOverrides(tracks)

            override fun onPlayerError(e: PlaybackException) {
                val req = request ?: return
                // Device couldn't decode the original file: let the server transcode instead.
                if (method == PlayMethod.DIRECT_PLAY && !triedFallback) {
                    triedFallback = true
                    notice = "This file couldn't be played directly. Switching to a converted stream…"
                    start(req.copy(startMs = player.currentPosition.coerceAtLeast(0), forceTranscode = true, audioIndex = audioIndex, subtitleIndex = subtitleIndex ?: -1))
                } else {
                    error = "Playback failed: ${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}"
                }
            }

            override fun onPlaybackParametersChanged(p: androidx.media3.common.PlaybackParameters) { speed = p.speed }
        })
        isPlaying = player.isPlaying // an adopted player is already running
    }

    fun start(req: PlayRequest) {
        val previous = request
        request = req
        error = null
        loading = true
        ended = false
        scope.launch {
            try {
                if (previous != null && reportedStart) reportStopped(player.currentPosition)
                reportedStart = false
                val it = repo.item(req.itemId)
                item = it
                val settings = app.settings.current
                val maxBitrate = if (settings.maxBitrateMbps == 0) 140_000_000 else settings.maxBitrateMbps * 1_000_000
                val profile = buildFinmaxProfile(DeviceCapabilities.get(app), maxBitrate)

                // Pick initial tracks before asking the server so it can plan the stream around them.
                val sourceGuess = it.mediaSources?.firstOrNull { s -> s.id == req.mediaSourceId } ?: it.mediaSources?.firstOrNull()
                val audio = req.audioIndex ?: preferredAudio(sourceGuess)
                val sub = when (req.subtitleIndex) {
                    -1 -> null
                    null -> preferredSubtitle(sourceGuess)
                    else -> req.subtitleIndex
                }
                val subStream = sourceGuess?.mediaStreams?.firstOrNull { s -> s.type == MediaStreamType.SUBTITLE && s.index == sub }
                // Only image subtitles involve the server (embed/burn-in); text ones are rendered by FINMAX.
                val serverSub = sub?.takeIf { subStream != null && !subStream.isTextSubtitle }

                val info = session.api.mediaInfoApi.getPostedPlaybackInfo(
                    req.itemId,
                    PlaybackInfoDto(
                        userId = session.userId,
                        maxStreamingBitrate = maxBitrate,
                        startTimeTicks = (req.startMs ?: 0) * TICKS_PER_MS,
                        audioStreamIndex = audio,
                        subtitleStreamIndex = serverSub ?: -1,
                        mediaSourceId = req.mediaSourceId,
                        deviceProfile = profile,
                        enableDirectPlay = !req.forceTranscode,
                        enableDirectStream = true,
                        enableTranscoding = true,
                        allowVideoStreamCopy = true,
                        allowAudioStreamCopy = true,
                        autoOpenLiveStream = true,
                    ),
                ).content
                info.errorCode?.let { code -> error("The server refused playback ($code).") }
                val src = info.mediaSources.firstOrNull { s -> s.id == req.mediaSourceId } ?: info.mediaSources.firstOrNull()
                    ?: error("The server returned no playable source.")
                source = src
                playSessionId = info.playSessionId
                audioIndex = audio ?: src.defaultAudioStreamIndex
                subtitleIndex = sub

                if (!isMpv && !adopting && !req.forceTranscode && src.supportsDirectPlay && wantsMpv(subStream, settings.playerEngine)) {
                    switchToMpvAt = req.startMs ?: run {
                        val ticks = it.userData?.playbackPositionTicks ?: 0
                        (ticks / TICKS_PER_MS - settings.resumeRewindSec * 1000).coerceAtLeast(0)
                    }
                    return@launch
                }

                if (adopting) {
                    // The preview is already playing this file (it can, or it wouldn't have run):
                    // keep it as direct play rather than restart on whatever stream the server offers.
                    adopting = false
                    method = PlayMethod.DIRECT_PLAY
                    if (player.playbackState == Player.STATE_READY) {
                        loading = false
                        if (!reportedStart) reportStart()
                    }
                    applyTrackOverrides(player.currentTracks)
                    loadCues()
                    loadExtras(it)
                    return@launch
                }

                val base = session.server.url.trimEnd('/')
                val (url, m) = when {
                    src.supportsDirectPlay && !req.forceTranscode ->
                        "$base/Videos/${req.itemId}/stream?static=true&mediaSourceId=${src.id}&playSessionId=${info.playSessionId}" +
                            (src.eTag?.let { t -> "&tag=$t" } ?: "") to PlayMethod.DIRECT_PLAY
                    src.transcodingUrl != null ->
                        base + src.transcodingUrl to (if (src.supportsDirectStream) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE)
                    else -> error("This file can't be played on this device and the server didn't offer a converted stream.")
                }
                method = m
                val mediaItem = MediaItem.Builder()
                    .setUri(url)
                    .setMediaId(req.itemId.toString())
                    .setMimeType(if (m == PlayMethod.DIRECT_PLAY) null else MimeTypes.APPLICATION_M3U8)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(it.name).setSubtitle(it.seriesName).build(),
                    )
                    .build()

                val resumeMs = req.startMs ?: run {
                    val ticks = it.userData?.playbackPositionTicks ?: 0
                    (ticks / TICKS_PER_MS - settings.resumeRewindSec * 1000).coerceAtLeast(0)
                }
                // Transcoded HLS streams already start at startTimeTicks.
                player.setMediaItem(mediaItem, if (m == PlayMethod.DIRECT_PLAY) resumeMs else 0)
                if (m != PlayMethod.DIRECT_PLAY && resumeMs > 0) player.seekTo(resumeMs)
                player.prepare()
                player.playWhenReady = true

                loadCues()
                loadExtras(it)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                loading = false
                error = friendlyError(e)
            }
        }
    }

    /** Styled embedded subtitles look right only in mpv. */
    private fun wantsMpv(sub: MediaStream?, engine: com.finmax.data.PlayerEngine) = when (engine) {
        com.finmax.data.PlayerEngine.MPV -> true
        com.finmax.data.PlayerEngine.EXO -> false
        com.finmax.data.PlayerEngine.AUTO -> sub != null && !sub.isExternal && sub.codec?.lowercase() in setOf("ass", "ssa")
    }

    private fun preferredAudio(src: MediaSourceInfo?): Int? {
        val lang = app.settings.current.preferredAudioLang.takeIf { it.isNotEmpty() } ?: return null
        return src?.mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO && it.language.equals(lang, true) }?.index
    }

    private fun preferredSubtitle(src: MediaSourceInfo?): Int? {
        val lang = app.settings.current.preferredSubtitleLang
        val subs = src?.mediaStreams?.filter { it.type == MediaStreamType.SUBTITLE }.orEmpty()
        if (lang.isNotEmpty()) {
            subs.firstOrNull { it.language.equals(lang, true) && !it.isForced }?.let { return it.index }
        }
        // The server's default already reflects the user's Jellyfin subtitle mode settings.
        return src?.defaultSubtitleStreamIndex?.takeIf { it >= 0 }
    }

    private fun loadExtras(it: BaseItemDto) {
        scope.launch {
            segments = runCatching { session.api.mediaSegmentsApi.getItemSegments(it.id).content.items }.getOrDefault(emptyList())
        }
        val playlist = request?.playlistId
        if (playlist != null || (it.type == BaseItemKind.EPISODE && it.seriesId != null)) scope.launch {
            val all = runCatching {
                if (playlist != null) repo.playlistItems(playlist) else repo.episodes(it.seriesId!!)
            }.getOrDefault(emptyList())
            val i = all.indexOfFirst { e -> e.id == it.id }
            queue = all
            queueIsPlaylist = playlist != null
            nextEpisode = all.getOrNull(i + 1)?.takeIf { i >= 0 }
            previousEpisode = all.getOrNull(i - 1)
        } else {
            queue = emptyList()
            nextEpisode = null
            previousEpisode = null
        }
    }

    // ---- Tracks ------------------------------------------------------------------------------

    /**
     * For direct play the file's own tracks are in the player. Jellyfin lists streams in file order,
     * so the n-th Jellyfin audio stream is the n-th ExoPlayer audio group (same for embedded subtitles).
     */
    private fun applyTrackOverrides(tracks: Tracks) {
        val params = player.trackSelectionParameters.buildUpon()
        if (method == PlayMethod.DIRECT_PLAY) {
            val groups = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val ordinal = audioStreams.sortedBy { it.index }.indexOfFirst { it.index == audioIndex }
            groups.getOrNull(ordinal)?.let { params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        }
        val sub = currentSubtitle
        // The player renders image subtitles, and embedded text ones until finmax has their text
        // (the server may take minutes to extract it); then finmax renders them, with sync control.
        val playerRenders = sub != null && method == PlayMethod.DIRECT_PLAY && !sub.isExternal && (isMpv || !sub.isTextSubtitle || cues == null)
        if (playerRenders) {
            val textGroups = tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }
            val ordinal = subtitleStreams.filter { !it.isExternal }.sortedBy { it.index }.indexOfFirst { it.index == sub.index }
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            textGroups.getOrNull(ordinal)?.let { params.setOverrideForType(TrackSelectionOverride(it.mediaTrackGroup, 0)) }
        } else {
            // Burned-in (transcode) or rendered by FINMAX: keep ExoPlayer's own subtitle output off.
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }
        val newParams = params.build()
        if (newParams != player.trackSelectionParameters) player.trackSelectionParameters = newParams
    }

    fun selectAudio(index: Int) {
        if (index == audioIndex) return
        if (method == PlayMethod.DIRECT_PLAY) {
            audioIndex = index
            applyTrackOverrides(player.currentTracks)
            reportProgress()
        } else {
            // The converted stream carries one audio track: ask the server for a new stream.
            restartAt(audio = index)
        }
    }

    /** @param index Jellyfin stream index, or -1 for off. */
    fun selectSubtitle(index: Int) {
        val old = currentSubtitle
        val new = subtitleStreams.firstOrNull { it.index == index }
        val needsServer = method != PlayMethod.DIRECT_PLAY &&
            ((new != null && !new.isTextSubtitle) || (old != null && !old.isTextSubtitle))
        if (needsServer) {
            restartAt(subtitle = index)
            return
        }
        if (!isMpv && method == PlayMethod.DIRECT_PLAY && new != null && wantsMpv(new, app.settings.current.playerEngine)) {
            subtitleIndex = new.index
            switchToMpvAt = player.currentPosition
            return
        }
        subtitleIndex = new?.index
        clearPreview()
        applyTrackOverrides(player.currentTracks)
        loadCues()
        reportProgress()
    }

    /** Audio and subtitles at once; a converted stream restarts only once for both. -1 turns subtitles off. */
    fun selectTracks(audio: Int, subtitle: Int) {
        val old = currentSubtitle
        val new = subtitleStreams.firstOrNull { it.index == subtitle }
        val subNeedsServer = (new != null && !new.isTextSubtitle) || (old != null && !old.isTextSubtitle)
        if (method != PlayMethod.DIRECT_PLAY && (audio != audioIndex || subNeedsServer)) {
            restartAt(audio = audio, subtitle = subtitle)
            return
        }
        selectAudio(audio)
        selectSubtitle(subtitle)
    }

    private fun restartAt(audio: Int? = audioIndex, subtitle: Int? = subtitleIndex ?: -1) {
        val req = request ?: return
        start(req.copy(startMs = player.currentPosition, audioIndex = audio, subtitleIndex = subtitle, mediaSourceId = source?.id))
    }

    fun changeSpeed(s: Float) = player.setPlaybackSpeed(s)

    // ---- Text subtitles ----------------------------------------------------------------------

    private fun loadCues() {
        cueJob?.cancel()
        cues = null
        timing = Timing()
        applyTrackOverrides(player.currentTracks)
        val sub = currentSubtitle ?: return
        if (!sub.isTextSubtitle) return
        if (isMpv && !sub.isExternal && method == PlayMethod.DIRECT_PLAY) return // mpv draws it
        val itemId = item?.id ?: return
        val sourceId = source?.id ?: return
        cuesLoading = true
        cueJob = scope.launch {
            runCatching { app.subtitles.cues(session, itemId, sourceId, sub.index) }
                .onSuccess {
                    cues = CueTrack(it)
                    applyTrackOverrides(player.currentTracks) // finmax takes over from the player
                    app.subtitles.rememberedTiming(itemId, sub.syncKey())?.let { t ->
                        timing = t
                        notice = "Using your saved sync for this subtitle (${describe(t)})"
                    }
                }
                .onFailure {
                    // Embedded ones keep showing through the player; only say so when nothing shows.
                    if (sub.isExternal || method != PlayMethod.DIRECT_PLAY) notice = "Couldn't load subtitles: ${friendlyError(it)}"
                }
            cuesLoading = false
        }
    }

    /** Sets the live correction and remembers it for this media + subtitle. */
    fun applyTiming(t: Timing) {
        timing = t
        (player as? MpvPlayer)?.subDelayMs = t.offsetMs
        val sub = currentSubtitle ?: return
        item?.id?.let { app.subtitles.rememberTiming(it, sub.syncKey(), t) }
    }

    fun nudgeDelay(deltaMs: Long) = applyTiming(timing.copy(offsetMs = timing.offsetMs + deltaMs))

    fun clearPreview() {
        previewCues = null
        previewTiming = null
    }

    /** Reloads streams after the server gained a subtitle (download/Bazarr), keeping position. */
    fun reloadForNewSubtitle(selectIndex: Int? = null, refreshed: suspend () -> Unit = {}) {
        val req = request ?: return
        item?.id?.let { app.subtitles.invalidate(it) }
        scope.launch {
            refreshed()
            start(req.copy(startMs = player.currentPosition, audioIndex = audioIndex, subtitleIndex = selectIndex ?: subtitleIndex ?: -1, mediaSourceId = source?.id))
        }
    }

    // ---- Reporting ---------------------------------------------------------------------------

    private fun reportStart() {
        reportedStart = true
        val it = item ?: return
        val pos = player.currentPosition
        app.appScope.launch {
            runCatching {
                session.api.playStateApi.reportPlaybackStart(
                    PlaybackStartInfo(
                        canSeek = true, itemId = it.id, mediaSourceId = source?.id, audioStreamIndex = audioIndex,
                        subtitleStreamIndex = subtitleIndex ?: -1, positionTicks = pos * TICKS_PER_MS, playSessionId = playSessionId,
                        playMethod = method, isPaused = false, isMuted = false, repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        }
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                delay(10_000)
                reportProgress()
            }
        }
    }

    fun reportProgress() {
        val it = item ?: return
        if (!reportedStart) return
        val pos = player.currentPosition
        val paused = !player.isPlaying
        app.appScope.launch {
            runCatching {
                session.api.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        canSeek = true, itemId = it.id, mediaSourceId = source?.id, audioStreamIndex = audioIndex,
                        subtitleStreamIndex = subtitleIndex ?: -1, positionTicks = pos * TICKS_PER_MS, playSessionId = playSessionId,
                        playMethod = method, isPaused = paused, isMuted = false, repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                    ),
                )
            }
        }
    }

    private fun reportStopped(posMs: Long) {
        val it = item ?: return
        val sessionId = playSessionId
        val sourceId = source?.id
        progressJob?.cancel()
        // Runs on the app scope so it still completes after the screen is gone.
        app.appScope.launch {
            runCatching {
                session.api.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(itemId = it.id, mediaSourceId = sourceId, positionTicks = posMs * TICKS_PER_MS, playSessionId = sessionId, failed = false),
                )
            }
        }
    }

    /** Polls the server's view of this session (used by the playback info panel). */
    suspend fun refreshTranscodeInfo() {
        transcodeInfo = runCatching {
            session.api.sessionApi.getSessions(deviceId = app.sessions.jellyfin.deviceInfo?.id).content
                .firstOrNull { it.nowPlayingItem?.id == item?.id }?.transcodingInfo
        }.getOrNull()
    }

    fun release() {
        if (reportedStart) {
            // At the very end Jellyfin needs the full runtime to mark the item watched.
            reportStopped(if (ended) durationMs else player.currentPosition)
        }
        scope.cancel()
        mediaSession.release()
        player.release()
    }

    companion object {
        fun describe(t: Timing): String = buildString {
            append(if (t.offsetMs >= 0) "+" else "−")
            append("%.2f s".format(kotlin.math.abs(t.offsetMs) / 1000.0))
            if (t.scale != 1.0) append(" · speed ×%.4f".format(t.scale))
        }
    }
}
