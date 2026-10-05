package acab.naiveha.upnpkino

import android.content.Context
import android.content.res.Resources
import android.os.ParcelFileDescriptor
import android.os.VibrationEffect
import android.os.VibratorManager
import android.system.Os
import android.system.OsConstants
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowMetrics
import kotlin.math.max
import kotlin.math.min

object Constants {
    const val UNKNOWN_FILE_SIZE = 0x7FFFFFFFFFFFFFFL

    val userAgent = "UpnpKino/1.0"
    const val APP_NAME = "UPnP Kino by naiveHA"
    val mimeType = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "wmv" to "video/x-ms-wmv",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mp4",
        "flac" to "audio/x-flac",
        "wav" to "audio/x-wav",
        "aac" to "audio/aac",
        "opus" to "audio/ogg")
    val movieExtensions = mimeType.filter { it.value.contains("video/") }.keys
    val musicExtensions = mimeType.filter { it.value.contains("audio/") }.keys
    fun vibrate(context: Context, short: Boolean = false) {
        val vibrationEffect = if (short) {
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator?.vibrate(vibrationEffect)
        } else {
            @Suppress("DEPRECATION")
            val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.vibrate(vibrationEffect)
        }
    }
    fun setDisplaySizing(windowManager: WindowManager, resources: Resources, params: ViewGroup.LayoutParams) {
        if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            return
        }
        val displayMetrics = DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val windowMetrics: WindowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            displayMetrics.widthPixels = bounds.width()
            displayMetrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getMetrics(displayMetrics)
        }
        displayMetrics.density = resources.displayMetrics.density
        val displayDensity = max(displayMetrics.density, 1f)
        val screenWidthDp = displayMetrics.widthPixels / displayDensity
        val screenHeightDp = 0.95f * displayMetrics.heightPixels / displayDensity
        params.width = (min(screenWidthDp, 1150f) * displayDensity).toInt()
        params.height = (min(screenHeightDp, 2650f) * displayDensity).toInt()
    }
    fun durationToSeconds(duration: String): Long {
        val parts = duration.split(":")
        if (parts.size < 3) return 0
        val h = parts[0].toLongOrNull() ?: 0L
        val m = parts[1].toLongOrNull() ?: 0L
        val sStr = parts[2].substringBefore(".")
        val s = sStr.toLongOrNull() ?: 0L
        return h * 3600 + m * 60 + s
    }
    fun secondsToDuration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return String.format("%02d:%02d:%02d", h, m, s)
    }
    fun getFileSize(pfd: ParcelFileDescriptor): Long {
        val statSize = pfd.statSize
        if (statSize > 0 && statSize != UNKNOWN_FILE_SIZE) return statSize

        return try {
            val fd = pfd.fileDescriptor
            val current = Os.lseek(fd, 0, OsConstants.SEEK_CUR)
            val size = Os.lseek(fd, 0, OsConstants.SEEK_END)
            Os.lseek(fd, current, OsConstants.SEEK_SET)
            if (size > 0 && size != UNKNOWN_FILE_SIZE) size else -1L
        } catch (e: Exception) {
            -1L
        }
    }
    object Dlna {
        object Service {
            const val AV_TRANSPORT = "AVTransport"
            const val CONNECTION_MANAGER = "ConnectionManager"
            const val RENDERING_CONTROL = "RenderingControl"
        }
        enum class Action(val actionName: String, val service: String, val responseName: String) {
            SET_AV_TRANSPORT_URI("SetAVTransportURI", Service.AV_TRANSPORT, "SetAVTransportURIResponse"),
            PLAY("Play", Service.AV_TRANSPORT, "PlayResponse"),
            PAUSE("Pause", Service.AV_TRANSPORT, "PauseResponse"),
            SEEK("Seek", Service.AV_TRANSPORT, "SeekResponse"),
            STOP("Stop", Service.AV_TRANSPORT, "StopResponse"),
            STOP_LOCAL("StopLocal", "", ""), // local-only pseudo-action, never sent over the wire
            GET_MEDIA_INFO("GetMediaInfo", Service.AV_TRANSPORT, "GetMediaInfoResponse"),
            GET_POSITION_INFO("GetPositionInfo", Service.AV_TRANSPORT, "GetPositionInfoResponse"),
            GET_TRANSPORT_INFO("GetTransportInfo", Service.AV_TRANSPORT, "GetTransportInfoResponse"),
            GET_PROTOCOL_INFO("GetProtocolInfo", Service.CONNECTION_MANAGER, "GetProtocolInfoResponse"),
            ERROR("Error", "", "") // local-only pseudo-action, never sent over the wire
        }
        object ActionFeedback {
            const val PLAYING = "PLAYING"
            const val PAUSED_PLAYBACK = "PAUSED_PLAYBACK"
            const val TRANSITIONING = "TRANSITIONING"
            const val STOPPED = "STOPPED"
            const val DISCONNECTED = "DISCONNECTED"
            const val NO_MEDIA_PRESENT = "NO_MEDIA_PRESENT"
        }
    }
    object Chromecast {
        const val MDNS_IP = "224.0.0.251"
        const val MDNS_PORT = 5353
        const val SERVICE_TYPE = "_googlecast._tcp.local"
        const val APP_ID = "CC1AD845"
        const val SENDER_ID = "sender-0"
        const val RECEIVER_ID = "receiver-0"
        object URN {
            const val CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
            const val HEARTBEAT = "urn:x-cast:com.google.cast.tp.heartbeat"
            const val RECEIVER = "urn:x-cast:com.google.cast.receiver"
            const val MEDIA = "urn:x-cast:com.google.cast.media"
        }
        enum class Action(val actionName: String, val urn: String) {
            CONNECT("CONNECT", URN.CONNECTION),
            LAUNCH("LAUNCH", URN.RECEIVER),
            STARTING_UP("STARTING_UP", ""),
            PING("PING", URN.HEARTBEAT),
            PONG("PONG", URN.HEARTBEAT),
            CLOSE("CLOSE", URN.CONNECTION),
            LOAD("LOAD", URN.MEDIA),
            PLAY("PLAY", URN.MEDIA),
            PAUSE("PAUSE", URN.MEDIA),
            STOP("STOP", URN.MEDIA),
            SEEK("SEEK", URN.MEDIA),
            SET_VOLUME("SET_VOLUME", URN.RECEIVER),
            GET_STATUS("GET_STATUS", URN.MEDIA),
            ERROR("ERROR", "")
        }
        object ActionFeedback {
            const val PLAYING = "PLAYING"
            const val PAUSED_PLAYBACK = "PAUSED_PLAYBACK"
            const val TRANSITIONING = "TRANSITIONING"
            const val STOPPED = "STOPPED"
            const val NO_MEDIA_PRESENT = "NO_MEDIA_PRESENT"
        }
        object ActionResponse {
            const val RECEIVER_STATUS = "RECEIVER_STATUS"
            const val MEDIA_STATUS = "MEDIA_STATUS"
            const val LOAD_FAILED = "LOAD_FAILED"
        }
        object Proto {
            const val VERSION = (1 shl 3) or 0
            const val SOURCE = (2 shl 3) or 2
            const val DESTINATION = (3 shl 3) or 2
            const val NAMESPACE = (4 shl 3) or 2
            const val PAYLOAD_TYPE = (5 shl 3) or 0
            const val PAYLOAD_UTF8 = (6 shl 3) or 2
            const val WIRE_VARINT = 0
            const val WIRE_LEN = 2
            const val MAX_FRAME_BYTES = 1_048_576
        }
    }
    object Transcoder {
        enum class VideoCodec(val codecId: String, val hardwareEncoder: String? = null) {
            UNKNOWN("unknown"),
            H264("h264", "h264_mediacodec"),
            HEVC("hevc", "hevc_mediacodec"),
            VP9("vp9"),
            VP8("vp8"),
            AV1("av1"),
            MPEG4("mpeg4"),
            MPEG2("mpeg2video"),
            WMV3("wmv3"),
            VC1("vc1"),
            MJPEG("mjpeg"),
            PRORES("prores"),
            DNXHD("dnxhd");

            val encoderId: String get() = hardwareEncoder ?: "UNKNOWN"
        }

        // Audio codecs; testChannels is used for capability probing.
        enum class AudioCodec(
            val codecId: String,
            val testChannels: Int,
            val softwareEncoder: String? = null,
            val defaultBitrateKbps: Int = 128
        ) {
            UNKNOWN("unknown", 0, defaultBitrateKbps = 0),
            AAC("aac", 2, "aac", defaultBitrateKbps = 128),
            AC3("ac3", 6, "ac3", defaultBitrateKbps = 384),
            EAC3("eac3", 6, defaultBitrateKbps = 448),
            DCA("dca", 6, defaultBitrateKbps = 384),
            MP3("mp3", 2, defaultBitrateKbps = 192),
            OPUS("opus", 2, defaultBitrateKbps = 128),
            VORBIS("vorbis", 2, defaultBitrateKbps = 128),
            FLAC("flac", 2, defaultBitrateKbps = 700),
            ALAC("alac", 2, defaultBitrateKbps = 700),
            MLP("mlp", 8, defaultBitrateKbps = 640),
            TRUEHD("truehd", 8, defaultBitrateKbps = 640),
            PCM_S16LE("pcm_s16le", 2, defaultBitrateKbps = 1411),
            WMAV2("wmav2", 2, defaultBitrateKbps = 128);

            val encoderId: String get() = softwareEncoder ?: "UNKNOWN"
        }
        enum class Container(val formatId: String) {
            MP4("mp4"),
            MPEG_TS("mpegts"),
            MKV("matroska")
        }
        enum class Resolution(val maxWidth: Int, val maxHeight: Int, val targetBitrateKbps: Int) {
            SD(720, 576, 1_500),
            HD_720(1280, 720, 4_000),
            HD_1080(1920, 1080, 8_000),
            UHD_4K(3840, 2160, 20_000)
        }
        enum class AudioQuality(val channelCount: Int, val targetBitrateKbps: Int, val ffmpegLayoutName: String? = null) {
            MONO(1, 64, null),
            STEREO(2, 128, null),
            SURROUND_5_1(6, 384, "5.1");

            val bitrateBps: Int get() = targetBitrateKbps * 1000
        }

        data class TranscodeProfile(
            val videoCodec: VideoCodec,
            val resolution: Resolution,
            val audioCodec: AudioCodec,
            val audioQuality: AudioQuality,
            val container: Container
        ) {
            val estimatedBitrateKbps: Int
                get() = resolution.targetBitrateKbps + audioQuality.targetBitrateKbps
        }

        object Priority {
            /** All video codecs ordered by quality/efficiency preference */
            val VIDEO_CODECS = listOf(
                VideoCodec.HEVC,
                VideoCodec.H264,
                VideoCodec.AV1,
                VideoCodec.VP9,
                VideoCodec.VP8,
                VideoCodec.MPEG4,
                VideoCodec.MPEG2,
                VideoCodec.WMV3,
                VideoCodec.VC1,
                VideoCodec.MJPEG,
                VideoCodec.PRORES,
                VideoCodec.DNXHD
            )

            val RESOLUTIONS = listOf(
                Resolution.UHD_4K,
                Resolution.HD_1080,
                Resolution.HD_720,
                Resolution.SD
            )

            /** All audio codecs ordered by quality preference */
            val AUDIO_CODECS = listOf(
                AudioCodec.TRUEHD,
                AudioCodec.MLP,
                AudioCodec.FLAC,
                AudioCodec.ALAC,
                AudioCodec.DCA,
                AudioCodec.EAC3,
                AudioCodec.AC3,
                AudioCodec.AAC,
                AudioCodec.OPUS,
                AudioCodec.VORBIS,
                AudioCodec.MP3,
                AudioCodec.PCM_S16LE,
                AudioCodec.WMAV2
            )

            val AUDIO_QUALITIES = listOf(
                AudioQuality.SURROUND_5_1,
                AudioQuality.STEREO,
                AudioQuality.MONO
            )

            val CONTAINERS = listOf(
                Container.MP4,
                Container.MKV,
                Container.MPEG_TS
            )
        }

        /**
         * Represents a single parsed DLNA sink profile entry from a protocolInfo list.
         */
        data class SinkProfile(
            val videoCodec: VideoCodec?,
            val audioCodec: AudioCodec?,
            val maxResolution: Resolution?,
            val maxChannels: Int?,
            val container: Container?
        )

        /**
         * Aggregates all advertised sinks from a DLNA device.
         */
        data class SinkCapabilities(
            val profiles: List<SinkProfile>,
            val isWildcard: Boolean = false
        ) {
            fun supportsVideoCodec(codec: VideoCodec): Boolean =
                isWildcard || profiles.isEmpty() || profiles.any { it.videoCodec == null || it.videoCodec == codec }

            fun supportsResolution(resolution: Resolution): Boolean =
                isWildcard || profiles.isEmpty() || profiles.any { it.maxResolution == null || it.maxResolution.maxHeight >= resolution.maxHeight }

            fun supportsAudioCodec(codec: AudioCodec): Boolean =
                isWildcard || profiles.isEmpty() || profiles.any { it.audioCodec == null || it.audioCodec == codec }

            fun supportsChannelCount(channels: Int): Boolean =
                isWildcard || profiles.isEmpty() || profiles.any { it.maxChannels == null || it.maxChannels >= channels }

            fun supportsContainer(container: Container): Boolean =
                isWildcard || profiles.isEmpty() || profiles.any { it.container == null || it.container == container }

            fun supportsCombination(
                videoCodec: VideoCodec,
                resolution: Resolution,
                audioCodec: AudioCodec,
                audioQuality: AudioQuality,
                container: Container
            ): Boolean {
                if (isWildcard || profiles.isEmpty()) return true
                return profiles.any { p ->
                    (p.videoCodec == null || p.videoCodec == videoCodec) &&
                    (p.maxResolution == null || p.maxResolution.maxHeight >= resolution.maxHeight) &&
                    (p.audioCodec == null || p.audioCodec == audioCodec) &&
                    (p.maxChannels == null || p.maxChannels >= audioQuality.channelCount) &&
                    (p.container == null || p.container == container)
                }
            }

            companion object {
                /**
                 * Parses a raw DLNA comma-separated SinkProtocolInfo response string into SinkCapabilities.
                 */
                fun fromProtocolInfo(rawSinkString: String): SinkCapabilities {
                    if (rawSinkString.isBlank() || rawSinkString.contains("*:*:*") || rawSinkString.contains("video/*")) {
                        return SinkCapabilities(emptyList(), isWildcard = true)
                    }

                    val parsedProfiles = rawSinkString.split(",")
                        .map { it.trim() }
                        .filter { it.startsWith("http-get:") }
                        .mapNotNull { parseSingleSink(it) }

                    val isWildcardDetected = rawSinkString.contains("*") && parsedProfiles.isEmpty()

                    return SinkCapabilities(parsedProfiles, isWildcard = isWildcardDetected)
                }

                private fun parseSingleSink(sinkString: String): SinkProfile? {
                    val parts = sinkString.split(":")
                    if (parts.size < 3) return null

                    val mimeType = parts[2].lowercase()
                    val dlnaFeatures = parts.getOrNull(3)?.uppercase() ?: ""

                    // Resolve Container from MIME type
                    val container = when {
                        mimeType.contains("mp4") -> Container.MP4
                        mimeType.contains("matroska") || mimeType.contains("mkv") || mimeType.contains("webm") -> Container.MKV
                        mimeType.contains("mpegts") || mimeType.contains("transportstream") || mimeType.contains("ts") -> Container.MPEG_TS
                        else -> null
                    }

                    // Resolve Video Codec from MIME or DLNA.ORG_PN profile name
                    val videoCodec = when {
                        dlnaFeatures.contains("HEVC") || mimeType.contains("hevc") || mimeType.contains("h265") -> VideoCodec.HEVC
                        dlnaFeatures.contains("AVC") || mimeType.contains("h264") || mimeType.contains("avc") -> VideoCodec.H264
                        dlnaFeatures.contains("AV1") || mimeType.contains("av1") || mimeType.contains("av01") -> VideoCodec.AV1
                        dlnaFeatures.contains("VP9") || mimeType.contains("vp9") || mimeType.contains("vp09") -> VideoCodec.VP9
                        dlnaFeatures.contains("VP8") || mimeType.contains("vp8") -> VideoCodec.VP8
                        mimeType.contains("mpeg4") || mimeType.contains("mp4v") -> VideoCodec.MPEG4
                        mimeType.contains("mpeg2") || mimeType.contains("mpeg-2") -> VideoCodec.MPEG2
                        mimeType.contains("wmv3") || mimeType.contains("wmv") -> VideoCodec.WMV3
                        mimeType.contains("vc1") || mimeType.contains("vc-1") -> VideoCodec.VC1
                        mimeType.contains("mjpeg") || mimeType.contains("jpeg") -> VideoCodec.MJPEG
                        mimeType.contains("prores") -> VideoCodec.PRORES
                        mimeType.contains("dnxhd") -> VideoCodec.DNXHD
                        else -> null
                    }

                    // Resolve Resolution from DLNA profile string
                    val maxResolution = when {
                        dlnaFeatures.contains("4K") || dlnaFeatures.contains("2160") || dlnaFeatures.contains("UHD") -> Resolution.UHD_4K
                        dlnaFeatures.contains("1080") || dlnaFeatures.contains("FHD") -> Resolution.HD_1080
                        dlnaFeatures.contains("720") || dlnaFeatures.contains("HD") -> Resolution.HD_720
                        dlnaFeatures.contains("SD") || dlnaFeatures.contains("480") || dlnaFeatures.contains("576") -> Resolution.SD
                        else -> null // Wildcard / unspecified resolution
                    }

                    // Resolve Audio Codec from MIME or DLNA.ORG_PN profile name
                    val audioCodec = when {
                        dlnaFeatures.contains("TRUEHD") || mimeType.contains("truehd") -> AudioCodec.TRUEHD
                        dlnaFeatures.contains("MLP") || mimeType.contains("mlp") -> AudioCodec.MLP
                        dlnaFeatures.contains("FLAC") || mimeType.contains("flac") -> AudioCodec.FLAC
                        dlnaFeatures.contains("ALAC") || mimeType.contains("alac") -> AudioCodec.ALAC
                        dlnaFeatures.contains("DCA") || dlnaFeatures.contains("DTS") || mimeType.contains("dts") || mimeType.contains("dca") -> AudioCodec.DCA
                        dlnaFeatures.contains("EAC3") || dlnaFeatures.contains("EC3") || mimeType.contains("eac3") || mimeType.contains("ec-3") -> AudioCodec.EAC3
                        dlnaFeatures.contains("AC3") || mimeType.contains("ac3") -> AudioCodec.AC3
                        dlnaFeatures.contains("AAC") || mimeType.contains("aac") || mimeType.contains("mp4a") -> AudioCodec.AAC
                        dlnaFeatures.contains("OPUS") || mimeType.contains("opus") -> AudioCodec.OPUS
                        dlnaFeatures.contains("VORBIS") || mimeType.contains("vorbis") -> AudioCodec.VORBIS
                        dlnaFeatures.contains("MP3") || mimeType.contains("mp3") || mimeType.contains("mpeg") -> AudioCodec.MP3
                        mimeType.contains("pcm") || mimeType.contains("raw") -> AudioCodec.PCM_S16LE
                        mimeType.contains("wma") -> AudioCodec.WMAV2
                        else -> null
                    }

                    val maxChannels = when {
                        dlnaFeatures.contains("7_1") || dlnaFeatures.contains("8CH") -> 8
                        dlnaFeatures.contains("5_1") || dlnaFeatures.contains("6CH") -> 6
                        dlnaFeatures.contains("2CH") || dlnaFeatures.contains("STEREO") -> 2
                        else -> null
                    }

                    return SinkProfile(
                        videoCodec = videoCodec,
                        audioCodec = audioCodec,
                        maxResolution = maxResolution,
                        maxChannels = maxChannels,
                        container = container
                    )
                }
            }
        }

        /**
         * Resolves the optimal TranscodeProfile using a 5-step dimensional evaluation.
         *
         * If the DLNA device advertises a wildcard sink ('*') or directly supports the source
         * file's native video/audio codecs, resolution, quality, and container, serving the
         * native file directly is preferred (minimum effort, zero transcoding).
         *
         * Returns null if transcoding is required but any step cannot find a valid candidate.
         */
        fun resolveTranscodeTarget(
            sourceItem: MediaCollection.MediaNode.Item,
            sink: SinkCapabilities,
            canEncodeVideo: (VideoCodec) -> Boolean,
            canEncodeVideoAtRes: (VideoCodec, Resolution) -> Boolean,
            canEncodeAudio: (AudioCodec) -> Boolean
        ): TranscodeProfile? {
            val sourceVideoCodec = sourceItem.videoTracks.firstOrNull()?.codec
            val sourceAudioCodec = sourceItem.audioTracks.firstOrNull()?.codec
            val sourceHeight = sourceItem.videoTracks.firstOrNull()?.resolution
                ?.split("x")?.getOrNull(1)?.toIntOrNull() ?: 1080
            val sourceTier = Priority.RESOLUTIONS.sortedBy { it.maxHeight }.find { it.maxHeight >= sourceHeight } ?: Resolution.HD_1080
            val sourceChannels = sourceItem.audioTracks.firstOrNull()?.channelCount ?: 2
            val sourceAudioQuality = Priority.AUDIO_QUALITIES.find { it.channelCount <= sourceChannels } ?: AudioQuality.STEREO
            val sourceExt = sourceItem.name.substringAfterLast('.', "").lowercase()
            val sourceContainer = when {
                sourceExt in setOf("mkv") -> Container.MKV
                sourceExt in setOf("ts", "mts") -> Container.MPEG_TS
                else -> Container.MP4
            }

            // Prefer Native / Minimum Effort: If sink accepts wildcard '*' or supports native source profile directly
            if (sink.isWildcard || (
                sourceVideoCodec != null && sink.supportsVideoCodec(sourceVideoCodec) &&
                sink.supportsResolution(sourceTier) &&
                sourceAudioCodec != null && sink.supportsAudioCodec(sourceAudioCodec) &&
                sink.supportsChannelCount(sourceAudioQuality.channelCount) &&
                sink.supportsContainer(sourceContainer)
            )) {
                return TranscodeProfile(
                    videoCodec = sourceVideoCodec ?: VideoCodec.H264,
                    resolution = sourceTier,
                    audioCodec = sourceAudioCodec ?: AudioCodec.AAC,
                    audioQuality = sourceAudioQuality,
                    container = sourceContainer
                )
            }

            // Downward-only evaluation: Candidates start strictly at source tier & channel count and only go downwards
            val candidateResolutions = Priority.RESOLUTIONS.filter { it.maxHeight <= sourceTier.maxHeight }
            val candidateAudioQualities = Priority.AUDIO_QUALITIES.filter { it.channelCount <= sourceChannels }

            for (videoCodec in Priority.VIDEO_CODECS) {
                if (!canEncodeVideo(videoCodec)) continue

                for (resolution in candidateResolutions) {
                    if (!canEncodeVideoAtRes(videoCodec, resolution)) continue

                    for (audioCodec in Priority.AUDIO_CODECS) {
                        if (!canEncodeAudio(audioCodec)) continue

                        for (audioQuality in candidateAudioQualities) {

                            for (container in Priority.CONTAINERS) {
                                if (sink.supportsCombination(videoCodec, resolution, audioCodec, audioQuality, container)) {
                                    return TranscodeProfile(
                                        videoCodec = videoCodec,
                                        resolution = resolution,
                                        audioCodec = audioCodec,
                                        audioQuality = audioQuality,
                                        container = container
                                    )
                                }
                            }
                        }
                    }
                }
            }
            return null // Return null if no single sink entry supports a valid combination the phone can encode
        }
    }
}
