package acab.naiveha.upnpkino

import android.content.Context
import android.content.res.Resources
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowMetrics
import kotlin.math.max
import kotlin.math.min

object Constants {
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
        "m4a" to "audio/mpeg",
        "aac" to "audio/aac",
        "flac" to "audio/x-flac",
        "wav" to "audio/x-wav",
        "opus" to "audio/ogg")
    val dlnaProfiles4Images = mapOf(
        "icon_png" to "PNG_TN",
        "large_png" to "PNG_LRG",
        "icon_jpeg" to "JPEG_TN",
        "small_jpeg" to "JPEG_SM",
        "medium_jpeg" to "JPEG_MED")
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
    object Dlna {
        object Service {
            const val AV_TRANSPORT = "AVTransport"
            const val CONNECTION_MANAGER = "ConnectionManager"
            const val RENDERING_CONTROL = "RenderingControl"
        }
        object URN {
            const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
            const val CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"
            const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
        }
        object Action {
            const val SET_AV_TRANSPORT_URI = "SetAVTransportURI"
            const val PLAY = "Play"
            const val PAUSE = "Pause"
            const val SEEK = "Seek"
            const val STOP = "Stop"
            const val STOP_LOCAL = "StopLocal"
            const val GET_MEDIA_INFO = "GetMediaInfo"
            const val GET_POSITION_INFO = "GetPositionInfo"
            const val GET_TRANSPORT_INFO = "GetTransportInfo"
            const val ERROR = "Error"
        }
        object ActionURN {
            const val SET_AV_TRANSPORT_URI = Service.AV_TRANSPORT
            const val PLAY = Service.AV_TRANSPORT
            const val PAUSE = Service.AV_TRANSPORT
            const val SEEK = Service.AV_TRANSPORT
            const val STOP = Service.AV_TRANSPORT
            const val GET_MEDIA_INFO = Service.AV_TRANSPORT
            const val GET_POSITION_INFO = Service.AV_TRANSPORT
            const val GET_TRANSPORT_INFO = Service.AV_TRANSPORT
        }
        object ActionResponse {
            const val SET_AV_TRANSPORT_URI = "SetAVTransportURIResponse"
            const val PLAY = "PlayResponse"
            const val PAUSE = "PauseResponse"
            const val SEEK = "SeekResponse"
            const val STOP = "StopResponse"
            const val GET_MEDIA_INFO = "GetMediaInfoResponse"
            const val GET_POSITION_INFO = "GetPositionInfoResponse"
            const val GET_TRANSPORT_INFO = "GetTransportInfoResponse"
        }
        object ActionFeedback {
            const val PLAYING = "PLAYING"
            const val PAUSED_PLAYBACK = "PAUSED_PLAYBACK"
            const val TRANSITIONING = "TRANSITIONING"
            const val STOPPED = "STOPPED"
            const val DISCONNECTED = "DISCONNECTED"
            const val NO_MEDIA_PRESENT = "NO_MEDIA_PRESENT"
        }
        fun getService(action: String): String = when(action) {
            Action.SET_AV_TRANSPORT_URI, Action.PLAY, Action.PAUSE,
            Action.SEEK, Action.STOP, Action.GET_POSITION_INFO,
            Action.GET_TRANSPORT_INFO, Action.GET_MEDIA_INFO -> Service.AV_TRANSPORT
            else -> ""
        }
        fun getURN(action: String): String = when(getService(action)) {
            Service.AV_TRANSPORT -> URN.AV_TRANSPORT
            Service.CONNECTION_MANAGER -> URN.CONNECTION_MANAGER
            Service.RENDERING_CONTROL -> URN.RENDERING_CONTROL
            else -> ""
        }
        fun getResponse(action: String): String = when(action) {
            Action.SET_AV_TRANSPORT_URI -> ActionResponse.SET_AV_TRANSPORT_URI
            Action.PLAY -> ActionResponse.PLAY
            Action.PAUSE -> ActionResponse.PAUSE
            Action.SEEK -> ActionResponse.SEEK
            Action.STOP -> ActionResponse.STOP
            Action.GET_MEDIA_INFO -> ActionResponse.GET_MEDIA_INFO
            Action.GET_POSITION_INFO -> ActionResponse.GET_POSITION_INFO
            Action.GET_TRANSPORT_INFO -> ActionResponse.GET_TRANSPORT_INFO
            else -> ""
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
        object Action {
            const val CONNECT = "CONNECT"
            const val LAUNCH = "LAUNCH"
            const val STARTING_UP = "STARTING_UP"
            const val PING = "PING"
            const val PONG = "PONG"
            const val CLOSE = "CLOSE"
            const val LOAD = "LOAD"
            const val PLAY = "PLAY"
            const val PAUSE = "PAUSE"
            const val STOP = "STOP"
            const val SEEK = "SEEK"
            const val SET_VOLUME = "SET_VOLUME"
            const val GET_STATUS = "GET_STATUS"
            const val ERROR = "ERROR"
        }
        object ActionFeedback {
            const val PLAYING = "PLAYING"
            const val PAUSED_PLAYBACK = "PAUSED_PLAYBACK"
            const val TRANSITIONING = "TRANSITIONING"
            const val STOPPED = "STOPPED"
            const val NO_MEDIA_PRESENT = "NO_MEDIA_PRESENT"
        }
        object ActionURN {
            const val CONNECT = URN.CONNECTION
            const val LAUNCH = URN.RECEIVER
            const val PING = URN.HEARTBEAT
            const val PONG = URN.HEARTBEAT
            const val CLOSE = URN.CONNECTION
            const val LOAD = URN.MEDIA
            const val PLAY = URN.MEDIA
            const val PAUSE = URN.MEDIA
            const val STOP = URN.MEDIA
            const val STOP_APP = URN.RECEIVER
            const val SEEK = URN.MEDIA
            const val SET_VOLUME = URN.RECEIVER
            const val GET_STATUS = URN.MEDIA
        }
        object ActionResponse {
            const val RECEIVER_STATUS = "RECEIVER_STATUS"
            const val MEDIA_STATUS = "MEDIA_STATUS"
            const val LOAD_FAILED = "LOAD_FAILED"
        }
        fun getURN(action: String): String = when(action) {
            Action.CONNECT, Action.CLOSE -> URN.CONNECTION
            Action.PING, Action.PONG -> URN.HEARTBEAT
            Action.LAUNCH, Action.SET_VOLUME -> URN.RECEIVER
            Action.LOAD, Action.PLAY, Action.PAUSE, Action.STOP, Action.SEEK, Action.GET_STATUS -> URN.MEDIA
            else -> ""
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
}
