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
//    val dlnaProfiles4Images = mapOf(
//        "icon_png" to "PNG_TN",
//        "large_png" to "PNG_LRG",
//        "icon_jpeg" to "JPEG_TN",
//        "small_jpeg" to "JPEG_SM",
//        "medium_jpeg" to "JPEG_MED")
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
//        object URN {
//            const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
//            const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
//            const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
//        }
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
//        object ActionURN {
//            const val SET_AV_TRANSPORT_URI = Service.AV_TRANSPORT
//            const val PLAY = Service.AV_TRANSPORT
//            const val PAUSE = Service.AV_TRANSPORT
//            const val SEEK = Service.AV_TRANSPORT
//            const val STOP = Service.AV_TRANSPORT
//            const val GET_MEDIA_INFO = Service.AV_TRANSPORT
//            const val GET_POSITION_INFO = Service.AV_TRANSPORT
//            const val GET_TRANSPORT_INFO = Service.AV_TRANSPORT
//        }
        object ActionFeedback {
            const val PLAYING = "PLAYING"
            const val PAUSED_PLAYBACK = "PAUSED_PLAYBACK"
            const val TRANSITIONING = "TRANSITIONING"
            const val STOPPED = "STOPPED"
            const val DISCONNECTED = "DISCONNECTED"
            const val NO_MEDIA_PRESENT = "NO_MEDIA_PRESENT"
        }
//        fun getURN(action: String): String = when(getService(action)) {
//            Service.AV_TRANSPORT -> URN.AV_TRANSPORT
//            Service.CONNECTION_MANAGER -> URN.CONNECTION_MANAGER
//            Service.RENDERING_CONTROL -> URN.RENDERING_CONTROL
//            else -> ""
//        }
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
            STARTING_UP("STARTING_UP", ""), // local-only pseudo-state, never sent over the wire
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
            ERROR("ERROR", "") // local-only pseudo-state, never sent over the wire
        }
        object ActionFeedback {
            const val PLAYING = "PLAYING"
            const val PAUSED_PLAYBACK = "PAUSED_PLAYBACK"
            const val TRANSITIONING = "TRANSITIONING"
            const val STOPPED = "STOPPED"
            const val NO_MEDIA_PRESENT = "NO_MEDIA_PRESENT"
        }
        // RECEIVER_STATUS/MEDIA_STATUS/LOAD_FAILED are incoming message types (events or
        // responses) and don't map 1:1 to a single outgoing Action the way service/urn do,
        // so unlike Action these stay as plain constants rather than folding into it.
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
        // Video encoders are MediaCodec-backed (hardware); ffmpegEncoder is the -c:v name.
        enum class VideoCodec(val ffmpegEncoder: String) {
            H264("h264_mediacodec"),
            HEVC("hevc_mediacodec")
        }
        // Audio encoders are software; testChannels is the channel count each is actually used for
        // here (AC3 exists specifically to carry 5.1) and what capability checks encode a test tone at.
        enum class AudioCodec(val ffmpegEncoder: String, val testChannels: Int) {
            AAC("aac", 2),
            AC3("ac3", 6)
        }
        enum class Container(val ffmpegMuxer: String) {
            MP4("mp4"),
            MPEG_TS("mpegts"),
            MKV("matroska")
        }
        // targetBitrateKbps: a realistic encode bitrate for this resolution class. Used by
        // capability checks too (not just real transcoding) -- without an explicit -b:v,
        // ffmpeg's mediacodec wrapper falls back to its own internal default (200kbps),
        // which is unrealistic at higher resolutions and can make a hardware encoder's own
        // parameter validation reject the request outright. Testing with the same bitrate
        // real encoding would actually use avoids that false negative.
        enum class Resolution(val maxWidth: Int, val maxHeight: Int, val targetBitrateKbps: Int) {
            SD(720, 576, 1_500),
            HD_720(1280, 720, 4_000),
            HD_1080(1920, 1080, 8_000),
            UHD_4K(3840, 2160, 20_000)
        }
    }
}
