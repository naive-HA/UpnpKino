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
        enum class AudioCodec(val codecId: String, val testChannels: Int, val softwareEncoder: String? = null) {
            UNKNOWN("unknown", 0),
            AAC("aac", 2, "aac"),
            AC3("ac3", 6, "ac3"),
            EAC3("eac3", 6),
            DCA("dca", 6),
            MP3("mp3", 2),
            OPUS("opus", 2),
            VORBIS("vorbis", 2),
            FLAC("flac", 2),
            ALAC("alac", 2),
            MLP("mlp", 8),
            TRUEHD("truehd", 8),
            PCM_S16LE("pcm_s16le", 2),
            WMAV2("wmav2", 2);

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
    }
}
