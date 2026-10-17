package acab.naiveha.upnpkino

import android.content.Context
import android.content.res.Resources
import android.media.MediaFormat
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
        enum class StreamType(val typeId: String) {
            VIDEO("video"),
            AUDIO("audio"),
            SUBTITLE("subtitle")
        }

        enum class SubtitleCodec(
            val codecId: String,
            val mimeType: String,
            val dlnaProfileAliases: Set<String> = emptySet()
        ) {
            UNKNOWN("unknown", "application/octet-stream"),
            SUBRIP("subrip", "application/x-subrip", setOf("subrip", "srt")),
            WEB_VTT("webvtt", MediaFormat.MIMETYPE_TEXT_VTT, setOf("webvtt", "vtt")),
            ASS("ass", "text/x-ssa", setOf("ass", "ssa")),
            SSA("ssa", "text/x-ssa", setOf("ssa")),
            DVD_SUBTITLE("dvd_subtitle", "application/vnd.dvb.subtitle", setOf("dvd_subtitle", "dvdsub")),
            PGS("pgs", "application/vnd.dvb.subtitle", setOf("pgs", "hdmv_pgs_subtitle"))
        }

        object H264AvcProfile {
            const val BASELINE = 66
            const val MAIN = 77
            const val HIGH = 100
            const val HIGH_10 = 110
            const val HIGH_422 = 122
            const val HIGH_444 = 244
        }

        object HevcProfile {
            const val MAIN = 1
            const val MAIN_10 = 2
        }

        object Av1Profile {
            const val MAIN = 0
            const val HIGH = 1
            const val PROFESSIONAL = 2
        }

        object Mpeg2Profile {
            const val SIMPLE = 1
            const val MAIN = 2
            const val HIGH = 4
        }

        object Mpeg4Profile {
            const val SIMPLE = 1
            const val ADVANCED_SIMPLE = 15
            const val MAIN = 3
        }

        object Vp9Profile {
            const val PROFILE_0 = 0
            const val PROFILE_1 = 1
            const val PROFILE_2 = 2
            const val PROFILE_3 = 3
        }

        object ProResProfile {
            const val PROXY = 0
            const val LT = 1
            const val STANDARD = 2
            const val HQ = 3
            const val PRO_4444 = 4
            const val PRO_4444_XQ = 5
        }

        enum class VideoCodec(
            val codecId: String,
            val hardwareEncoder: String? = null,
            val ffmpegCanDecode: Boolean = true,
            val ffmpegCanEncode: Boolean = false,
            val media3CanDecode: Boolean = false,
            val media3CanEncode: Boolean = false,
            val transcodingPriority: Int = 0,
            val dlnaProfileAliases: Set<String> = emptySet(),
            val profileMapping: Map<String, Int> = emptyMap()
        ) {
            UNKNOWN("unknown", ffmpegCanDecode = false, media3CanDecode = false),
            HEVC("hevc", "hevc_mediacodec", ffmpegCanDecode = true, ffmpegCanEncode = true, media3CanDecode = true, media3CanEncode = true, transcodingPriority = 1, dlnaProfileAliases = setOf("hevc", "h265"), profileMapping = mapOf("Main" to HevcProfile.MAIN, "Main 10" to HevcProfile.MAIN_10)),
            H264("h264", "h264_mediacodec", ffmpegCanDecode = true, ffmpegCanEncode = true, media3CanDecode = true, media3CanEncode = true, transcodingPriority = 2, dlnaProfileAliases = setOf("avc", "h264"), profileMapping = mapOf("Baseline" to H264AvcProfile.BASELINE, "Main" to H264AvcProfile.MAIN, "High" to H264AvcProfile.HIGH, "High 10" to H264AvcProfile.HIGH_10, "High 4:2:2" to H264AvcProfile.HIGH_422, "High 4:4:4" to H264AvcProfile.HIGH_444)),
            VP9("vp9", ffmpegCanDecode = true, media3CanDecode = true, dlnaProfileAliases = setOf("vp9", "vp09"), profileMapping = mapOf("Profile 0" to Vp9Profile.PROFILE_0, "0" to Vp9Profile.PROFILE_0, "Profile 1" to Vp9Profile.PROFILE_1, "1" to Vp9Profile.PROFILE_1, "Profile 2" to Vp9Profile.PROFILE_2, "2" to Vp9Profile.PROFILE_2, "Profile 3" to Vp9Profile.PROFILE_3, "3" to Vp9Profile.PROFILE_3)),
            VP8("vp8", ffmpegCanDecode = true, media3CanDecode = true, dlnaProfileAliases = setOf("vp8")),
            AV1("av1", ffmpegCanDecode = true, media3CanDecode = true, dlnaProfileAliases = setOf("av1", "av01"), profileMapping = mapOf("Main" to Av1Profile.MAIN, "High" to Av1Profile.HIGH, "Professional" to Av1Profile.PROFESSIONAL)),
            MPEG4("mpeg4", ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("mpeg4", "mp4v", "divx", "xvid"), profileMapping = mapOf("Simple" to Mpeg4Profile.SIMPLE, "Advanced Simple" to Mpeg4Profile.ADVANCED_SIMPLE, "Main" to Mpeg4Profile.MAIN)),
            MPEG2("mpeg2video", ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("mpeg2", "mpeg-2", "mpeg", "mp2t", "mp2p", "mp1s", "bmpeg", "mpv"), profileMapping = mapOf("Simple" to Mpeg2Profile.SIMPLE, "Main" to Mpeg2Profile.MAIN, "High" to Mpeg2Profile.HIGH)),
            WMV3("wmv3", ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("wmv3", "wmv")),
            VC1("vc1", ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("vc1", "vc-1")),
            MJPEG("mjpeg", ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("mjpeg", "jpeg")),
            PRORES("prores", ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("prores"), profileMapping = mapOf("Proxy" to ProResProfile.PROXY, "LT" to ProResProfile.LT, "Standard" to ProResProfile.STANDARD, "HQ" to ProResProfile.HQ, "4444" to ProResProfile.PRO_4444, "4444 XQ" to ProResProfile.PRO_4444_XQ)),
            DNXHD("dnxhd", ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("dnxhd"));

            val encoderId: String get() = hardwareEncoder ?: "UNKNOWN"
            val canEncode: Boolean get() = ffmpegCanEncode || media3CanEncode
            val canDecode: Boolean get() = ffmpegCanDecode || media3CanDecode
        }

        // Audio codecs; testChannels is used for capability probing.
        enum class AudioCodec(
            val codecId: String,
            val testChannels: Int,
            val softwareEncoder: String? = null,
            val defaultBitrateKbps: Int = 128,
            val ffmpegCanDecode: Boolean = true,
            val ffmpegCanEncode: Boolean = false,
            val media3CanDecode: Boolean = false,
            val media3CanEncode: Boolean = false,
            val transcodingPriority: Int = 0,
            val dlnaProfileAliases: Set<String> = emptySet()
        ) {
            UNKNOWN("unknown", 0, defaultBitrateKbps = 0, ffmpegCanDecode = false, media3CanDecode = false),
            AC3("ac3", 6, "ac3", defaultBitrateKbps = 384, ffmpegCanDecode = true, ffmpegCanEncode = true, media3CanDecode = true, media3CanEncode = false, transcodingPriority = 1, dlnaProfileAliases = setOf("ac3")),
            AAC("aac", 2, "aac", defaultBitrateKbps = 128, ffmpegCanDecode = true, ffmpegCanEncode = true, media3CanDecode = true, media3CanEncode = true, transcodingPriority = 2, dlnaProfileAliases = setOf("aac", "mp4a")),
            EAC3("eac3", 6, defaultBitrateKbps = 448, ffmpegCanDecode = true, media3CanDecode = true, dlnaProfileAliases = setOf("eac3", "ec3", "ec-3")),
            DCA("dca", 6, defaultBitrateKbps = 384, ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("dca", "dts")),
            MP3("mp3", 2, defaultBitrateKbps = 192, ffmpegCanDecode = true, media3CanDecode = true, dlnaProfileAliases = setOf("mp3", "mpeg")),
            OPUS("opus", 2, defaultBitrateKbps = 128, ffmpegCanDecode = true, media3CanDecode = true, dlnaProfileAliases = setOf("opus")),
            VORBIS("vorbis", 2, defaultBitrateKbps = 128, ffmpegCanDecode = true, media3CanDecode = true, dlnaProfileAliases = setOf("vorbis")),
            FLAC("flac", 2, defaultBitrateKbps = 700, ffmpegCanDecode = true, media3CanDecode = true, dlnaProfileAliases = setOf("flac")),
            ALAC("alac", 2, defaultBitrateKbps = 700, ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("alac")),
            MLP("mlp", 8, defaultBitrateKbps = 640, ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("mlp")),
            TRUEHD("truehd", 8, defaultBitrateKbps = 640, ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("truehd")),
            PCM_S16LE("pcm_s16le", 2, defaultBitrateKbps = 1411, ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("pcm", "raw")),
            WMAV2("wmav2", 2, defaultBitrateKbps = 128, ffmpegCanDecode = true, media3CanDecode = false, dlnaProfileAliases = setOf("wma"));

            val encoderId: String get() = softwareEncoder ?: "UNKNOWN"
            val canEncode: Boolean get() = ffmpegCanEncode || media3CanEncode
            val canDecode: Boolean get() = ffmpegCanDecode || media3CanDecode
        }
        enum class Container(
            val formatId: String,
            val ffmpegCanDemux: Boolean = true,
            val ffmpegCanMux: Boolean = true,
            val media3CanDemux: Boolean = true,
            val media3CanMux: Boolean = false,
            val transcodingPriority: Int = 0,
            val dlnaProfileAliases: Set<String> = emptySet()
        ) {
            UNKNOWN("unknown", ffmpegCanDemux = false, ffmpegCanMux = false, media3CanDemux = false, media3CanMux = false, transcodingPriority = 99, dlnaProfileAliases = setOf("unknown")),
            MP4("mp4", ffmpegCanDemux = true, ffmpegCanMux = true, media3CanDemux = true, media3CanMux = true, transcodingPriority = 1, dlnaProfileAliases = setOf("mp4")),
            MKV("matroska", ffmpegCanDemux = true, ffmpegCanMux = true, media3CanDemux = true, media3CanMux = false, transcodingPriority = 2, dlnaProfileAliases = setOf("matroska", "mkv")),
            MPEG_TS("mpegts", ffmpegCanDemux = true, ffmpegCanMux = true, media3CanDemux = true, media3CanMux = false, transcodingPriority = 3, dlnaProfileAliases = setOf("mpegts", "transportstream", "ts", "mp2t", "m2ts", "mts")),
            WEBM("webm", ffmpegCanDemux = true, ffmpegCanMux = true, media3CanDemux = true, media3CanMux = true, transcodingPriority = 4, dlnaProfileAliases = setOf("webm")),
            MOV("mov", ffmpegCanDemux = true, ffmpegCanMux = true, media3CanDemux = true, media3CanMux = true, transcodingPriority = 5, dlnaProfileAliases = setOf("quicktime", "mov")),
            AVI("avi", ffmpegCanDemux = true, ffmpegCanMux = false, media3CanDemux = true, media3CanMux = false, transcodingPriority = 6, dlnaProfileAliases = setOf("avi", "msvideo")),
            ASF("asf", ffmpegCanDemux = true, ffmpegCanMux = false, media3CanDemux = false, media3CanMux = false, transcodingPriority = 7, dlnaProfileAliases = setOf("asf", "wmv", "wma", "wmx", "wvx")),
            FLV("flv", ffmpegCanDemux = false, ffmpegCanMux = false, media3CanDemux = true, media3CanMux = false, transcodingPriority = 8, dlnaProfileAliases = setOf("flv")),
            OGG("ogg", ffmpegCanDemux = true, ffmpegCanMux = false, media3CanDemux = true, media3CanMux = false, transcodingPriority = 9, dlnaProfileAliases = setOf("ogg"))
        }
        enum class Resolution(
            val maxWidth: Int,
            val maxHeight: Int,
            val targetBitrateKbps: Int,
            val transcodingPriority: Int = 0,
            val dlnaProfileAliases: Set<String> = emptySet()
        ) {
            UHD_4K(3840, 2160, 20_000, transcodingPriority = 1, dlnaProfileAliases = setOf("4k", "2160", "uhd")),
            HD_1080(1920, 1080, 8_000, transcodingPriority = 2, dlnaProfileAliases = setOf("1080", "fhd")),
            HD_720(1280, 720, 4_000, transcodingPriority = 3, dlnaProfileAliases = setOf("720", "hd")),
            SD(720, 576, 1_500, transcodingPriority = 4, dlnaProfileAliases = setOf("sd", "480", "576"))
        }
        enum class AudioQuality(
            val channelCount: Int,
            val targetBitrateKbps: Int,
            val ffmpegLayoutName: String? = null,
            val transcodingPriority: Int = 0,
            val dlnaProfileAliases: Set<String> = emptySet()
        ) {
            SURROUND_7_1(8, 640, "7.1", transcodingPriority = 0, dlnaProfileAliases = setOf("7_1", "8ch")),
            SURROUND_5_1(6, 384, "5.1", transcodingPriority = 1, dlnaProfileAliases = setOf("5_1", "6ch")),
            STEREO(2, 128, null, transcodingPriority = 2, dlnaProfileAliases = setOf("2ch", "stereo")),
            MONO(1, 64, null, transcodingPriority = 3, dlnaProfileAliases = setOf("1ch", "mono"));

            val bitrateBps: Int get() = targetBitrateKbps * 1000
        }

        data class TranscodeProfile(
            val videoCodec: VideoCodec?,
            val resolution: Resolution?,
            val audioCodec: AudioCodec?,
            val audioQuality: AudioQuality?,
            val container: Container,
            val videoProfile: Int? = null
        ) {
            val estimatedBitrateKbps: Int
                get() = (resolution?.targetBitrateKbps ?: 0) + (audioQuality?.targetBitrateKbps ?: 0)
        }

        object Priority {
            /** Encodable video codecs ordered by quality/efficiency preference */
            val VIDEO_CODECS = VideoCodec.entries.filter { it.canEncode }.sortedBy { it.transcodingPriority }

            val RESOLUTIONS = Resolution.entries.sortedBy { it.transcodingPriority }

            /** Encodable audio codecs ordered by quality preference */
            val AUDIO_CODECS = AudioCodec.entries.filter { it.canEncode }.sortedBy { it.transcodingPriority }

            val AUDIO_QUALITIES = AudioQuality.entries.filter { it.transcodingPriority > 0 }.sortedBy { it.transcodingPriority }

            /** Muxable containers ordered by preference */
            val CONTAINERS = Container.entries.filter { it.ffmpegCanMux || it.media3CanMux }.sortedBy { it.transcodingPriority }
        }
    }
}
