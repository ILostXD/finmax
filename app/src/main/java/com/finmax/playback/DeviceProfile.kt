package com.finmax.playback

import android.content.Context
import android.media.MediaCodecList
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioCapabilities
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.deviceprofile.buildDeviceProfile

/** What this device can decode in hardware/software and pass through to an AV receiver. */
class DeviceCapabilities private constructor(
    val video: Set<String>,
    val audio: Set<String>,
    val dolbyVision: Boolean,
) {
    fun summary() = (video.sorted() + audio.sorted()).joinToString(", ") + if (dolbyVision) ", Dolby Vision" else ""

    companion object {
        @Volatile private var cached: DeviceCapabilities? = null

        private val videoMimes = mapOf(
            "video/avc" to "h264", "video/hevc" to "hevc", "video/x-vnd.on2.vp9" to "vp9", "video/av01" to "av1",
            "video/mpeg2" to "mpeg2video", "video/mp4v-es" to "mpeg4", "video/x-vnd.on2.vp8" to "vp8",
        )
        private val audioMimes = mapOf(
            "audio/mp4a-latm" to "aac", "audio/mpeg" to "mp3", "audio/opus" to "opus", "audio/flac" to "flac",
            "audio/vorbis" to "vorbis", "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/eac3-joc" to "eac3",
            "audio/alac" to "alac", "audio/true-hd" to "truehd", "audio/vnd.dts" to "dts", "audio/vnd.dts.hd" to "dts",
            "audio/raw" to "pcm",
        )

        @OptIn(UnstableApi::class)
        fun get(context: Context): DeviceCapabilities = cached ?: synchronized(this) {
            cached ?: run {
                val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder }
                val types = decoders.flatMap { it.supportedTypes.map(String::lowercase) }.toSet()
                val video = types.mapNotNull { videoMimes[it] }.toMutableSet().apply { add("h264") }
                val audio = types.mapNotNull { audioMimes[it] }.toMutableSet().apply { addAll(listOf("aac", "mp3")) }
                // Formats the device can't decode but can bitstream to a receiver/soundbar over HDMI.
                val caps = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
                if (caps.supportsEncoding(C.ENCODING_AC3)) audio += "ac3"
                if (caps.supportsEncoding(C.ENCODING_E_AC3) || caps.supportsEncoding(C.ENCODING_E_AC3_JOC)) audio += "eac3"
                if (caps.supportsEncoding(C.ENCODING_DTS) || caps.supportsEncoding(C.ENCODING_DTS_HD)) audio += "dts"
                if (caps.supportsEncoding(C.ENCODING_DOLBY_TRUEHD)) audio += "truehd"
                DeviceCapabilities(video, audio, "video/dolby-vision" in types).also { cached = it }
            }
        }
    }
}

/**
 * Builds the profile Jellyfin uses to decide between Direct Play, Direct Stream (remux) and transcoding.
 *
 * Subtitles: text formats are declared External so they never force a transcode; FINMAX downloads
 * them itself (as SRT) to render with adjustable timing. Image formats (PGS/VobSub/DVB) can be
 * embedded for direct play or burned in (Encode) when transcoding.
 */
fun buildFinmaxProfile(caps: DeviceCapabilities, maxBitrate: Int): DeviceProfile = buildDeviceProfile {
    name = "FINMAX"
    maxStreamingBitrate = maxBitrate
    maxStaticBitrate = maxBitrate
    musicStreamingTranscodingBitrate = 384_000

    directPlayProfile {
        type = DlnaProfileType.VIDEO
        container("mp4", "m4v", "mkv", "webm", "mov", "ts", "mpegts", "m2ts", "avi", "3gp", "ogv")
        videoCodec(*caps.video.toTypedArray())
        audioCodec(*caps.audio.toTypedArray())
    }
    directPlayProfile {
        type = DlnaProfileType.AUDIO
        container("mp3", "aac", "m4a", "flac", "ogg", "opus", "wav", "webma")
        audioCodec(*caps.audio.toTypedArray())
    }

    transcodingProfile {
        type = DlnaProfileType.VIDEO
        context = EncodingContext.STREAMING
        protocol = MediaStreamProtocol.HLS
        container = "ts"
        videoCodec(*listOfNotNull("hevc".takeIf { it in caps.video }, "h264").toTypedArray())
        audioCodec(*listOf("aac", "ac3", "eac3").filter { it in caps.audio }.toTypedArray())
        maxAudioChannels = "6"
        copyTimestamps = false
        breakOnNonKeyFrames = true
        minSegments = 1
    }
    transcodingProfile {
        type = DlnaProfileType.AUDIO
        context = EncodingContext.STREAMING
        protocol = MediaStreamProtocol.HTTP
        container = "mp3"
        audioCodec("mp3")
    }

    // Hardware H.264 decoders on TV SoCs are 8-bit only (Hi10P would fail or stutter).
    codecProfile {
        type = CodecType.VIDEO
        codec = "h264"
        conditions { lowerThanOrEquals(ProfileConditionValue.VIDEO_BIT_DEPTH, 8) }
    }
    if (!caps.dolbyVision) {
        // Without a DV decoder, profile 5 (no fallback layer) shows green/purple: let the server tone-map it.
        codecProfile {
            type = CodecType.VIDEO
            codec = "hevc"
            conditions {
                add(
                    ProfileConditionValue.VIDEO_RANGE_TYPE,
                    ProfileConditionType.EQUALS_ANY,
                    "SDR|HDR10|HDR10Plus|HLG|DOVIWithHDR10|DOVIWithHLG|DOVIWithSDR|DOVIWithHDR10Plus",
                    false,
                )
            }
        }
    }

    listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "ttml", "mov_text", "smi", "sub", "microdvd").forEach {
        subtitleProfile(it, SubtitleDeliveryMethod.EXTERNAL)
    }
    listOf("pgssub", "pgs", "dvdsub", "dvbsub", "vobsub").forEach {
        subtitleProfile(it, SubtitleDeliveryMethod.EMBED)
        subtitleProfile(it, SubtitleDeliveryMethod.ENCODE)
    }
}
