package com.finmax.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.jdtech.mpv.MPVLib
import java.io.File

/**
 * mpv behind Media3's [Player] interface, so the player screen, MediaSession and track picking work
 * unchanged. Used for files whose subtitles need real ASS/SSA rendering (styles, positions, fonts).
 *
 * Tracks are exposed one group per mpv track in file order, the same order ExoPlayer uses, so the
 * Jellyfin stream → track mapping in [PlayerController] needs no special case.
 */
@OptIn(UnstableApi::class)
class MpvPlayer(context: Context, authHeader: String) : SimpleBasePlayer(Looper.getMainLooper()) {
    private val main = Handler(Looper.getMainLooper())
    private val mpv = MPVLib.create(context) ?: error("mpv failed to start")

    private var item: MediaItem? = null
    private var startMs = 0L
    private var prepared = false
    private var loaded = false
    private var playWhenReady = false
    private var paused = true
    private var buffering = false
    private var seeking = false
    private var eof = false
    private var error: PlaybackException? = null
    private var positionMs = 0L
    private var durationMs = C.TIME_UNSET
    private var cacheMs = 0L
    private var speed = 1f
    private var videoSize = VideoSize.UNKNOWN
    private var tracks = Tracks.EMPTY
    private var params = TrackSelectionParameters.DEFAULT
    private var holder: SurfaceHolder? = null
    // PlayerView keeps its black shutter over the video until the player says a frame is up.
    private var shownFirstFrame = false
    private var newFrame = false

    /** Subtitle delay, from finmax's timing controls (mpv draws these subtitles itself). */
    var subDelayMs: Long = 0
        set(v) { field = v; mpv.setPropertyDouble("sub-delay", v / 1000.0) }

    init {
        mpv.setOptionString("vo", "gpu")
        mpv.setOptionString("gpu-context", "android")
        mpv.setOptionString("opengl-es", "yes")
        // Hardware decoding straight into the GPU (no copy), with subtitles drawn on top.
        mpv.setOptionString("hwdec", "mediacodec,mediacodec-copy")
        mpv.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        mpv.setOptionString("ao", "audiotrack,opensles")
        mpv.setOptionString("force-window", "no")
        mpv.setOptionString("idle", "yes")
        mpv.setOptionString("keep-open", "yes") // the end pauses on the last frame, so "ended" is visible
        mpv.setOptionString("input-default-bindings", "no")
        mpv.setOptionString("config", "no")
        // A 32-bit TV box: keep the demuxer cache modest.
        mpv.setOptionString("cache", "yes")
        mpv.setOptionString("demuxer-max-bytes", "64MiB")
        mpv.setOptionString("demuxer-max-back-bytes", "16MiB")
        mpv.setOptionString("http-header-fields-append", "Authorization: $authHeader")
        // libass has no system font lookup on Android: a fallback font for subtitles without embedded ones.
        fallbackFontDir(context)?.let {
            mpv.setOptionString("sub-fonts-dir", it.absolutePath)
            mpv.setOptionString("sub-font", "Roboto")
        }
        mpv.init()

        mpv.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        mpv.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
        mpv.observeProperty("track-list/count", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("video-params/w", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.observeProperty("video-params/h", MPVLib.MpvFormat.MPV_FORMAT_INT64)
        mpv.addObserver(object : MPVLib.EventObserver {
            override fun eventProperty(property: String) = Unit
            override fun eventProperty(property: String, value: Long) = post {
                when (property) {
                    "track-list/count" -> tracks = readTracks()
                    "video-params/w", "video-params/h" -> {
                        val w = mpv.getPropertyInt("video-params/w") ?: 0
                        val h = mpv.getPropertyInt("video-params/h") ?: 0
                        if (w > 0 && h > 0) videoSize = VideoSize(w, h)
                    }
                }
            }
            override fun eventProperty(property: String, value: Double) = post {
                when (property) {
                    "time-pos" -> if (!seeking) positionMs = (value * 1000).toLong()
                    "duration" -> durationMs = (value * 1000).toLong()
                    "demuxer-cache-time" -> cacheMs = (value * 1000).toLong()
                }
            }
            override fun eventProperty(property: String, value: Boolean) = post {
                when (property) {
                    "pause" -> paused = value
                    "paused-for-cache" -> buffering = value
                    "eof-reached" -> eof = value
                }
            }
            override fun eventProperty(property: String, value: String) = Unit
            override fun event(eventId: Int) = post {
                when (eventId) {
                    MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                        loaded = true
                        tracks = readTracks()
                        applyTrackParams()
                    }
                    MPVLib.MpvEvent.MPV_EVENT_SEEK -> seeking = true
                    MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                        seeking = false
                        if (!shownFirstFrame) { shownFirstFrame = true; newFrame = true }
                    }
                    MPVLib.MpvEvent.MPV_EVENT_END_FILE -> if (!loaded && prepared) {
                        error = PlaybackException("mpv couldn't open the file", null, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
                    }
                }
            }
        })
    }

    /** mpv calls back on its own thread; state lives on the main thread. */
    private fun post(block: () -> Unit) {
        main.post {
            block()
            invalidateState()
        }
    }

    override fun getState(): State {
        val commands = Player.Commands.Builder().addAll(
            COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_RELEASE,
            COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SEEK_TO_DEFAULT_POSITION,
            COMMAND_SET_SPEED_AND_PITCH, COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS,
            COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_TRACKS, COMMAND_SET_TRACK_SELECTION_PARAMETERS,
            COMMAND_SET_VIDEO_SURFACE, COMMAND_GET_METADATA,
        ).build()
        val state = when {
            item == null || !prepared -> STATE_IDLE
            error != null -> STATE_IDLE
            eof -> STATE_ENDED
            !loaded || buffering || seeking -> STATE_BUFFERING
            else -> STATE_READY
        }
        val playing = state == STATE_READY && !paused
        val frame = newFrame
        newFrame = false
        return State.Builder()
            .setNewlyRenderedFirstFrame(frame)
            .setAvailableCommands(commands)
            .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackState(state)
            .setPlayerError(error)
            .setIsLoading(state == STATE_BUFFERING)
            .setPlaybackParameters(PlaybackParameters(speed))
            .setTrackSelectionParameters(params)
            .setVideoSize(videoSize)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setPlaylist(
                item?.let {
                    listOf(
                        MediaItemData.Builder(it.mediaId.ifEmpty { "mpv" })
                            .setMediaItem(it)
                            .setTracks(tracks)
                            .setIsSeekable(true)
                            .setDurationUs(if (durationMs == C.TIME_UNSET) C.TIME_UNSET else durationMs * 1000)
                            .build(),
                    )
                } ?: emptyList(),
            )
            .setCurrentMediaItemIndex(0)
            .setContentPositionMs(
                if (playing) PositionSupplier.getExtrapolating(positionMs, speed) else PositionSupplier.getConstant(positionMs),
            )
            .setContentBufferedPositionMs(PositionSupplier.getConstant(maxOf(positionMs, cacheMs)))
            .build()
    }

    // ---- Commands --------------------------------------------------------------------------------

    override fun handleSetMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long): ListenableFuture<*> {
        item = mediaItems.getOrNull(startIndex.coerceAtLeast(0))
        startMs = if (startPositionMs == C.TIME_UNSET) 0 else startPositionMs
        positionMs = startMs
        if (prepared) load()
        return done()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepared = true
        error = null
        load()
        return done()
    }

    private fun load() {
        val uri = item?.localConfiguration?.uri?.toString() ?: return
        loaded = false
        eof = false
        shownFirstFrame = false
        mpv.setPropertyBoolean("pause", !playWhenReady)
        mpv.command(arrayOf("loadfile", uri, "replace", "-1", "start=${startMs / 1000.0}"))
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        this.playWhenReady = playWhenReady
        if (playWhenReady && eof) mpv.command(arrayOf("seek", "0", "absolute"))
        mpv.setPropertyBoolean("pause", !playWhenReady)
        paused = !playWhenReady
        return done()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val to = if (positionMs == C.TIME_UNSET) 0 else positionMs.coerceAtLeast(0)
        this.positionMs = to
        eof = false
        seeking = true
        if (loaded) mpv.command(arrayOf("seek", (to / 1000.0).toString(), "absolute")) else startMs = to
        return done()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        speed = playbackParameters.speed
        mpv.setPropertyDouble("speed", speed.toDouble())
        return done()
    }

    override fun handleSetTrackSelectionParameters(trackSelectionParameters: TrackSelectionParameters): ListenableFuture<*> {
        params = trackSelectionParameters
        applyTrackParams()
        return done()
    }

    override fun handleStop(): ListenableFuture<*> {
        mpv.command(arrayOf("stop"))
        prepared = false
        loaded = false
        return done()
    }

    override fun handleRelease(): ListenableFuture<*> {
        detach()
        mpv.destroy()
        return done()
    }

    // ---- Video surface ---------------------------------------------------------------------------

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(h: SurfaceHolder) = attach(h.surface)
        override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, ht: Int) {
            mpv.setPropertyString("android-surface-size", "${w}x$ht")
        }
        override fun surfaceDestroyed(h: SurfaceHolder) = detach()
    }

    override fun handleSetVideoOutput(videoOutput: Any): ListenableFuture<*> {
        val h = when (videoOutput) {
            is SurfaceView -> videoOutput.holder
            is SurfaceHolder -> videoOutput
            else -> null
        }
        if (h != null) {
            holder?.removeCallback(surfaceCallback)
            holder = h
            h.addCallback(surfaceCallback)
            if (h.surface?.isValid == true) attach(h.surface)
        } else if (videoOutput is Surface) {
            attach(videoOutput)
        }
        return done()
    }

    override fun handleClearVideoOutput(videoOutput: Any?): ListenableFuture<*> {
        holder?.removeCallback(surfaceCallback)
        holder = null
        detach()
        return done()
    }

    private fun attach(surface: Surface) {
        mpv.attachSurface(surface)
        mpv.setOptionString("force-window", "yes")
        mpv.setPropertyString("vo", "gpu")
    }

    private fun detach() {
        mpv.setPropertyString("vo", "null")
        mpv.setOptionString("force-window", "no")
        mpv.detachSurface()
    }

    // ---- Tracks ----------------------------------------------------------------------------------

    private fun readTracks(): Tracks {
        val n = mpv.getPropertyInt("track-list/count") ?: return Tracks.EMPTY
        val groups = (0 until n).mapNotNull { i ->
            val type = mpv.getPropertyString("track-list/$i/type") ?: return@mapNotNull null
            val id = mpv.getPropertyInt("track-list/$i/id") ?: return@mapNotNull null
            val mime = when (type) {
                "video" -> MimeTypes.VIDEO_UNKNOWN
                "audio" -> MimeTypes.AUDIO_UNKNOWN
                "sub" -> MimeTypes.TEXT_SSA
                else -> return@mapNotNull null
            }
            val format = Format.Builder()
                .setId("$type:$id")
                .setSampleMimeType(mime)
                .setLanguage(mpv.getPropertyString("track-list/$i/lang"))
                .setLabel(mpv.getPropertyString("track-list/$i/title"))
                .build()
            val selected = mpv.getPropertyBoolean("track-list/$i/selected") == true
            Tracks.Group(TrackGroup("$type:$id", format), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(selected))
        }
        return Tracks(groups)
    }

    /** Overrides from [PlayerController] (one group per mpv track) become mpv's aid/sid. */
    private fun applyTrackParams() {
        if (!loaded) return
        fun idOf(type: Int): String? = params.overrides.values
            .firstOrNull { it.type == type }?.mediaTrackGroup?.getFormat(0)?.id?.substringAfter(':')
        idOf(C.TRACK_TYPE_AUDIO)?.let { mpv.setPropertyString("aid", it) }
        val sid = if (C.TRACK_TYPE_TEXT in params.disabledTrackTypes) "no" else idOf(C.TRACK_TYPE_TEXT) ?: "no"
        mpv.setPropertyString("sid", sid)
    }

    private fun done(): ListenableFuture<*> = Futures.immediateVoidFuture()

    companion object {
        /** Copies a system sans-serif into app storage once; libass loads fonts from a folder. */
        fun fallbackFontDir(context: Context): File? = runCatching {
            val dir = File(context.filesDir, "mpv-fonts").apply { mkdirs() }
            val dest = File(dir, "Roboto-Regular.ttf")
            if (!dest.exists()) {
                val src = listOf("/system/fonts/RobotoStatic-Regular.ttf", "/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf", "/system/fonts/DroidSans.ttf")
                    .map(::File).first { it.exists() }
                src.copyTo(dest)
            }
            dir
        }.getOrNull()
    }
}
