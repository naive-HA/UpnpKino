package acab.naiveha.upnpkino

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.*
import java.net.*
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.*
import kotlin.collections.set
import acab.naiveha.upnpkino.Constants.Chromecast.ActionFeedback
import acab.naiveha.upnpkino.Constants.Chromecast.Action
import acab.naiveha.upnpkino.Constants.Chromecast.ActionURN
import acab.naiveha.upnpkino.Constants.Chromecast.SERVICE_TYPE
import acab.naiveha.upnpkino.Constants.Chromecast.MDNS_IP
import acab.naiveha.upnpkino.Constants.Chromecast.MDNS_PORT
import acab.naiveha.upnpkino.Constants.Chromecast.RECEIVER_ID
import acab.naiveha.upnpkino.Constants.Chromecast.SENDER_ID
import acab.naiveha.upnpkino.Constants.Chromecast.URN.HEARTBEAT
import acab.naiveha.upnpkino.Constants.Chromecast.URN.MEDIA
import acab.naiveha.upnpkino.Constants.Chromecast.URN.RECEIVER
import acab.naiveha.upnpkino.Constants.Chromecast.ActionResponse
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.MAX_FRAME_BYTES
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.VERSION
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.SOURCE
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.DESTINATION
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.NAMESPACE
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.PAYLOAD_TYPE
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.PAYLOAD_UTF8
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.WIRE_VARINT
import acab.naiveha.upnpkino.Constants.Chromecast.Proto.WIRE_LEN
import acab.naiveha.upnpkino.Constants.durationToSeconds
import acab.naiveha.upnpkino.Constants.secondsToDuration
import acab.naiveha.upnpkino.Constants.Chromecast.getURN
import android.annotation.SuppressLint
import org.json.JSONArray
import kotlin.time.Duration.Companion.milliseconds

class ChromecastController(val context: Context, val upnpService: UpnpService) {
    companion object {
        private const val PROGRESS_POLL_MS = 1_000L
        private const val PING_INTERVAL_MS = 5_000L
        private const val PONG_TIMEOUT_MS  = 10_000L

    }
    private data class CastMessage(
        val sourceId:      String,
        val destinationId: String,
        val namespace:     String,
        val payload:       String,
    )
    private val repo = UpnpRepository.chromecast
    @Volatile var isConnected: Boolean = false
//        private set
    private val resourceLock = Any()
    private var socket: SSLSocket? = null
    private var writer: DataOutputStream? = null
    private val writeLock = Mutex()
    private var connectionScope: CoroutineScope? = null
    private var disconnectSignal: Channel<String>? = null
    private val lastPongMs = AtomicLong(0L)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var connectionJob: Job? = null
    val chromecastDevices = ConcurrentHashMap<String, ChromecastNodeModel>()
    private val pendingDevices = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var transportId: String? = null
    @Volatile private var mediaSessionId: Int? = null
    @Volatile private var isSplashActive = false
    @Volatile private var pollingJob: Job? = null
    private val pollingMutex = Mutex()
    private val requestId = AtomicInteger(1)
    private fun nextReqId() = requestId.getAndIncrement()
    init {
        scope.launch {
            repo.searchForDevices.collect { searching ->
                Log.d("ChromecastController", "searchForDevices changed: $searching")
                if (searching) {
                    chromecastDevices.clear()
                    repo.setDevices(null)
                    searchForDevices()
                }
            }
        }
        scope.launch {
            repo.selectedDeviceId.collect { deviceId ->
                Log.d("ChromecastController", "selectedDeviceId changed: $deviceId")
                if (deviceId == null) {
                    resetChromecastActivity()
                } else {
                    val chromecastDevicesMap = repo.devices.value
                    deviceId.let { chromecastDevicesMap?.get(it) } ?: run {
                        Log.w("ChromecastController", "Selected device not found in map")
                        return@collect
                    }
                    repo.setStreamingFlag(Action.STARTING_UP)
                    if (!performHandshake()){
                        Log.e("ChromecastController", "Handshake failed for device $deviceId")
                        repo.setStreamingFlag(Action.ERROR)
                    }
                }
            }
        }
        scope.launch {
            repo.streamingFlag.collect { streamingFlag ->
                if (streamingFlag != null) {
                    Log.d("ChromecastController", "streamingFlag command: $streamingFlag")
                    val deviceId = repo.selectedDeviceId.value
                    val devices = repo.devices.value
                    val device = deviceId?.let { devices?.get(it) } ?: run {
                        Log.e("ChromecastController", "No device selected for command $streamingFlag")
                        return@collect
                    }
                    val fileId = repo.selectedMediaFileId.value
                    if(fileId?.let { device.mediaCollection[it] } !is Configuration.MediaNode.Item) {
                        Log.w("ChromecastController", "Invalid or missing media item for command $streamingFlag")
                        return@collect
                    }
                    val streamingFeedbackFlag = repo.streamingFeedbackFlag.value
                    when (streamingFlag) {
                        Action.STARTING_UP -> {
                            // handshake in progress; UI is locked
                            return@collect
                        }
                        Action.PLAY -> {
                            if (transportId != null) {
                                if (streamingFeedbackFlag == ActionFeedback.PAUSED_PLAYBACK) {
                                    if (sendMediaCommand(Action.PLAY, mapOf())) {
                                        return@collect
                                    } //else ERROR
                                } else {
                                    if (sendMediaCommand(Action.LOAD, mapOf("fileId" to fileId))) {
//                                    requestPlayback(fileId)
                                        return@collect
                                    } //else ERROR
                                }
                            } else {
                                Log.i("ChromecastController", "Transport ID null, attempting reLaunch")
                                if (reLaunch()) {
                                    return@collect
                                } //else ERROR
                            }
                        }
                        Action.PAUSE -> {
                            if (sendMediaCommand(Action.PAUSE, mapOf())) {
                                return@collect
                            } //else ERROR
                        }
                        Action.STOP -> {
                            if (sendMediaCommand(Action.STOP, mapOf())) {
                                stopPlaying()
                                return@collect
                            } //else ERROR
                        }
                        Action.SEEK -> {
                            val target = repo.seekBarTarget.value
                            Log.d("ChromecastController", "Seeking to $target")
                            if (target != null && transportId != null && mediaSessionId != null && sendMediaCommand(Action.SEEK, mapOf("target" to target))){
                                return@collect
                            }
                        }
                    }
                    Log.e("ChromecastController", "Error processing command $streamingFlag")
                    repo.setStreamingFlag(Action.ERROR)
                    resetChromecastActivity()
                }
            }
        }
        scope.launch {
            repo.streamingFeedbackFlag.collect { streamingFeedbackFlag ->
                Log.d("ChromecastController", "streamingFeedbackFlag changed: $streamingFeedbackFlag")
                val deviceId = repo.selectedDeviceId.value
                val streamingFlag = repo.streamingFlag.value
                if (deviceId != null && streamingFeedbackFlag != null && streamingFlag in listOf(Action.PLAY, Action.PAUSE, Action.SEEK)) {
                    when (streamingFeedbackFlag){
                        ActionFeedback.STOPPED,
                        ActionFeedback.NO_MEDIA_PRESENT -> {
                            if (!isSplashActive) {
                                Log.i("ChromecastController", "Playback stopped on device")
                                stopPlaying()
                            }
                        }
                        Action.ERROR -> {
                            Log.e("ChromecastController", "Feedback reported error")
                            stopPlaying()
                        }
                        ActionFeedback.PLAYING,
                        ActionFeedback.TRANSITIONING ->
                            startPolling()
                    }
                }
            }
        }
        scope.launch {
            repo.isChromecastActivityVisible.collect { isChromecastActivityVisible ->
                Log.d("ChromecastController", "Activity visible: $isChromecastActivityVisible")
                val deviceId = repo.selectedDeviceId.value
                val devices = repo.devices.value
                val device = deviceId?.let { devices?.get(it) }
                if (device != null) {
                    if (!isChromecastActivityVisible) {
                        Log.d("ChromecastController", "Stopping polling due to activity hidden")
                        pollingJob?.cancel()
                        pollingJob = null
                    } else {
                        when (repo.streamingFeedbackFlag.value) {
                            ActionFeedback.PLAYING,
                            ActionFeedback.PAUSED_PLAYBACK,
                            ActionFeedback.TRANSITIONING -> {
                                Log.d("ChromecastController", "Resuming polling due to activity visible")
                                //validation of session required???
                                startPolling()
                            }
                        }
                    }
                }
            }
        }
    }
    private fun performHandshake() : Boolean {
        val deviceId = repo.selectedDeviceId.value
        val devices = repo.devices.value
        val device = deviceId?.let { devices?.get(it) }
        if (device == null){
            Log.e("ChromecastController", "performHandshake: device not found")
            return false
        }
        Log.d("ChromecastController", "Initiating handshake with ${device.friendlyName} at ${device.host}:${device.port}")
//        disconnect()
        connectionJob = scope.launch {
            try {
                val sock = withContext(Dispatchers.IO) {
                    Log.v("ChromecastController", "Creating SSL socket")
                    buildTrustAllSslContext().socketFactory.createSocket(device.host, device.port) as SSLSocket
                }
                withContext(Dispatchers.IO) {
                    sock.enabledProtocols = sock.supportedProtocols.filter { it.startsWith("TLS") }.toTypedArray()
                    Log.v("ChromecastController", "Starting SSL handshake")
                    sock.startHandshake()
                }
                Log.i("ChromecastController", "SSL handshake successful")
                val signal = Channel<String>(capacity = 1)
                synchronized(resourceLock) {
                    socket = sock
                    writer = DataOutputStream(BufferedOutputStream(sock.outputStream))
                    disconnectSignal = signal
                    isConnected = true
                }
                lastPongMs.set(System.currentTimeMillis())
                val cScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                connectionScope = cScope
                cScope.launch { readLoop(sock, signal) }
                cScope.launch { heartbeatLoop(signal) }
                Log.v("ChromecastController", "Sending CONNECT and LAUNCH messages")
                if (!send(RECEIVER_ID, ActionURN.CONNECT, connectJson())){
                    throw Exception("Connect failed")
                }
                if (!send(RECEIVER_ID, ActionURN.LAUNCH,   launchJson())){
                    throw Exception("Launch failed")
                }
            } catch (e: CancellationException) {
                Log.d("ChromecastController", "Handshake cancelled")
                throw e
            } catch (e: Exception) {
                Log.e("ChromecastController", "Handshake failed", e)
                resetChromecastActivity()
                repo.setStreamingFlag(Action.ERROR)
            }
        }
        return true
    }
    private fun connectJson(): String {
        return JSONObject().apply {
            put("type", Action.CONNECT)
            put("userAgent", Constants.userAgent) }.toString()
    }
    private fun launchJson(): String {
        return JSONObject().apply {
            put("type", Action.LAUNCH)
            put("requestId", nextReqId())
            put("appId", Constants.Chromecast.APP_ID) }.toString()
    }
    private suspend fun send(destinationId: String, namespace: String, payload: String): Boolean = withContext(Dispatchers.IO) {
        if (destinationId.isEmpty() || namespace.isEmpty()) {
            Log.w("ChromecastController", "send failed: empty destinationId or namespace")
            return@withContext false
        }
        val out = synchronized(resourceLock) { writer } ?: run {
            Log.w("ChromecastController", "send failed: writer is null")
            return@withContext false
        }
        Log.v("ChromecastController", "Sending message to $destinationId [$namespace]: $payload")
        val frame = encodeFrame(destinationId, namespace, payload)
        writeLock.withLock {
            try {
                out.writeInt(frame.size)
                out.write(frame)
                out.flush()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("ChromecastController", "Write error, triggering disconnect", e)
                disconnectSignal?.trySend(e.message.toString())
                false
            }
        }
    }
    private fun encodeFrame(destinationId: String, namespace: String, payload: String): ByteArray = ByteArrayOutputStream(512).apply {
        writeTag(VERSION); writeVarint(0)
        writeStringField(SOURCE, SENDER_ID)
        writeStringField(DESTINATION, destinationId)
        writeStringField(NAMESPACE, namespace)
        writeTag(PAYLOAD_TYPE); writeVarint(0)
        writeStringField(PAYLOAD_UTF8, payload)
    }.toByteArray()
    private suspend fun readLoop(sock: SSLSocket, signal: Channel<String>) = withContext(Dispatchers.IO) {
        val reader = DataInputStream(BufferedInputStream(sock.inputStream))
        val readJob = launch {
            try {
                while (isActive) {
                    val frameLen = reader.readInt()
                    if (frameLen !in 1..MAX_FRAME_BYTES) continue
                    val bytes = ByteArray(frameLen); reader.readFully(bytes)
                    val msg = decodeFrame(bytes) ?: continue
                    launch {
                        handleIncomingMessage(msg)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EOFException) {
                resetChromecastActivity()
                repo.setStreamingFlag(Action.ERROR)
            } catch (e: Exception) {
                if (isActive) {
                    resetChromecastActivity()
                    repo.setStreamingFlag(Action.ERROR)
                }
            }
        }
        val signalJob = launch {
            signal.receive();
            readJob.cancel();
            stopPlaying()
        }
        readJob.join()
        signalJob.cancel()
    }
    private fun decodeFrame(bytes: ByteArray): CastMessage? = runCatching {
        var pos = 0; var srcId = ""; var dstId = ""; var ns = ""; var pld = ""
        while (pos < bytes.size) {
            val (tagVal, tagLen) = readVarint(bytes, pos); pos += tagLen
            if (pos > bytes.size) break
            val fieldNum = tagVal.toInt() ushr 3
            val wireType = tagVal.toInt() and 0x07
            when (wireType) {
                WIRE_VARINT -> { val (_, vLen) = readVarint(bytes, pos); pos += vLen }
                WIRE_LEN -> {
                    val (lenVal, lenBytes) = readVarint(bytes, pos); pos += lenBytes
                    val len = lenVal.toInt()
                    if (len < 0 || pos + len > bytes.size) break
                    val str = String(bytes, pos, len, Charsets.UTF_8)
                    pos += len
                    when (fieldNum) { 2 -> srcId = str; 3 -> dstId = str; 4 -> ns = str; 6 -> pld = str }
                }
                else -> break
            }
        }
        CastMessage(srcId, dstId, ns, pld)
    }.getOrNull()
    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var i = offset
        while (i < data.size) {
            val b = data[i++].toLong() and 0xFF; result = result or ((b and 0x7F) shl shift); shift += 7
            if (b and 0x80 == 0L) break
            if (shift >= 64) break
        }
        return result to (i - offset)
    }
    private suspend fun handleIncomingMessage(msg: CastMessage) {
        Log.v("ChromecastController", "Received message from ${msg.sourceId} [${msg.namespace}]: ${msg.payload}")
        when (msg.namespace) {
            RECEIVER -> handleDeviceEvent(msg.payload)
            MEDIA -> handleMediaEvent(msg.payload)
            HEARTBEAT -> handleHeartbeat(msg.payload)
            ActionURN.CLOSE -> {
                if (jsonType(msg.payload) == Action.CLOSE) {
                    Log.i("ChromecastController", "Received CLOSE command from device")
                    stopPlaying()
                }
            }
        }
    }
    private suspend fun handleDeviceEvent(payload: String) {
        if (jsonType(payload) != ActionResponse.RECEIVER_STATUS) return
        val applicationsArray = JSONObject(payload).optJSONObject("status")?.optJSONArray("applications") ?: return
        if (applicationsArray.length() == 0) {
            Log.v("ChromecastController", "No applications running on device")
            transportId = null
            return
        }
        val chromecastDefaultMediaPlayer = applicationsArray.getJSONObject(0)
        val appId = chromecastDefaultMediaPlayer.optString("appId")
        if (appId != Constants.Chromecast.APP_ID) {
            Log.d("ChromecastController", "Foreign app running ($appId); clearing our session")
            transportId = null
            return
        }
        val tid = chromecastDefaultMediaPlayer.optString("transportId").takeIf { it.isNotBlank() } ?: run {
            Log.v("ChromecastController", "No transportId found in status")
            return
        }
        if (transportId == tid) return
        Log.i("ChromecastController", "Connected to application with transportId: $tid")
        transportId = tid
        send(transportId!!, ActionURN.CONNECT, connectJson())
        withContext(Dispatchers.Main) { repo.setStreamingFlag(null) }
        val streamingFlag = repo.streamingFlag.value
        val deviceId = repo.selectedDeviceId.value
        val devices = repo.devices.value
        val device = deviceId?.let { devices?.get(it) } ?: return
        val fileId = repo.selectedMediaFileId.value
        if(fileId?.let { device.mediaCollection[it] } !is Configuration.MediaNode.Item) return












        if (streamingFlag == Action.PLAY) {
            requestPlayback(fileId)
        } else {
            showIdleSplash()
        }
    }










    private suspend fun handleMediaEvent(payload: String) {
        val json = JSONObject(payload)
        when (jsonType(payload)) {
            ActionResponse.MEDIA_STATUS -> {
                val statuses = json.optJSONArray("status") ?: return
                if (statuses.length() == 0) {
                    Log.v("ChromecastController", "Media status empty")
                    if (!isSplashActive) {
                        withContext(Dispatchers.Main) {
                            repo.setStreamingFeedbackFlag(ActionFeedback.STOPPED)
                        }
                    }
                    return
                }
                if (isSplashActive) {
                    Log.v("ChromecastController", "Ignoring media status: splash active")
                    return
                }
                val status = statuses.getJSONObject(0)
                val playerState = status.optString("playerState")
                Log.v("ChromecastController", "Player state: $playerState")
                val idleReason  = status.optString("idleReason")
                status.optInt("mediaSessionId", 0).takeIf { it > 0 }?.let { mediaSessionId = it }
                val streamingFeedbackFlag = when (playerState) {
                    "PLAYING"   -> ActionFeedback.PLAYING
                    "PAUSED"    -> ActionFeedback.PAUSED_PLAYBACK
                    "BUFFERING" -> ActionFeedback.TRANSITIONING
                    "IDLE"      -> ActionFeedback.STOPPED
                    else        -> ActionFeedback.STOPPED
                }
                if (playerState == "IDLE" && idleReason in listOf("FINISHED", "CANCELLED")) isSplashActive = true
                val positionMs = status["currentTime"].toString()
                val durationMs = status.optJSONObject("media")?.opt("duration")?.toString() ?: "-1"
                withContext(Dispatchers.Main) {
                    repo.setStreamingFeedbackFlag(streamingFeedbackFlag)
                    updateSeekBarPosition(positionMs, durationMs)
                }
                if (streamingFeedbackFlag == ActionFeedback.PLAYING) startPolling()
                if (playerState == "IDLE" && idleReason in listOf("FINISHED", "CANCELLED")) showIdleSplash()
            }
            ActionResponse.LOAD_FAILED -> {
                if (isSplashActive) {
                    isSplashActive = false
                    return
                }
                Log.e("ChromecastController", "Media LOAD_FAILED: $payload")
                withContext(Dispatchers.Main) { repo.setStreamingFeedbackFlag(ActionFeedback.STOPPED) }
            }
        }
    }
    private fun updateSeekBarPosition(position: String, duration: String) {
        //position format is like "0.444039"
        //duration format is like "6766.334"
        if (repo.userIsSeeking.value) return
        val positionInSeconds = position.toDoubleOrNull()?.toLong() ?: return
        val durationInSeconds = duration.toDoubleOrNull()?.takeIf { it > 0.0 }?.toLong()
        if (durationInSeconds == null){
            repo.setSeekBarPosition(secondsToDuration(positionInSeconds))
        } else if (durationInSeconds > 0L && positionInSeconds > durationInSeconds) {
            stopPlaying()
        } else {
            repo.setSeekBarPosition(secondsToDuration(positionInSeconds))
            repo.setSeekBarDuration(secondsToDuration(durationInSeconds))
        }
    }
    private suspend fun handleHeartbeat(payload: String) {
        when (jsonType(payload)) {
            Action.PING -> {
                val pong = JSONObject().apply { put("type", Action.PONG) }.toString()
                send(RECEIVER_ID, ActionURN.PONG, pong)
            }
            Action.PONG -> {
                lastPongMs.set(System.currentTimeMillis())
            }
        }
    }
    private suspend fun heartbeatLoop(signal: Channel<String>) = withContext(Dispatchers.IO) {
        while (isActive && isConnected) {
            delay(PING_INTERVAL_MS.milliseconds)
            val elapsed = System.currentTimeMillis() - lastPongMs.get()
            if (elapsed > PONG_TIMEOUT_MS) {
                signal.trySend("pong timeout")
                //error. stop playing?
                //disconnect?
                break
            }
            try {
                val ping = JSONObject().apply { put("type", Action.PING) }.toString()
                send(RECEIVER_ID, ActionURN.PING, ping)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    signal.trySend("heartbeat send failed")
                }
                //error. stop playing?
                //disconnect?
                break
            }
        }
    }
    private suspend fun showIdleSplash() {
        val tid  = transportId ?: return
        Log.d("ChromecastController", "Showing idle splash")
        val ip   = upnpService.configuration.getIpAddress() ?: return
        val port = upnpService.configuration.getHttpServerPort()
        val uuid = upnpService.configuration.uuid
        val media = JSONObject().apply {
            put("contentId",   "http://$ip:$port/$uuid/splash")
            put("contentType", "audio/wav")
            put("streamType",  "NONE")
            put("metadata", JSONObject().apply {
                put("metadataType", 3)
                put("title",  BuildConfig.APPLICATION_NAME)
                put("artist", "Ready to play")
                put("images", JSONArray().apply {
                    put(JSONObject().apply { put("url", "http://$ip:$port/$uuid/icon") })
                })
            })
        }
        val payload = JSONObject().apply {
            put("type",      Action.LOAD)
            put("requestId", nextReqId())
            put("media",     media)
            put("autoplay",  true)
        }
        isSplashActive = true
        send(tid, ActionURN.LOAD, payload.toString())
    }
    private suspend fun reLaunch() : Boolean {
        if (!isConnected) {
            return performHandshake()
        }
        Log.d("upnpkino", "reLaunch: sending LAUNCH to bring back Default Media Receiver")
        return send(RECEIVER_ID, ActionURN.LAUNCH, launchJson())
    }
    private suspend fun requestPlayback(fileId: String) {
        val deviceId = repo.selectedDeviceId.value
        val chromecastDevicesMap = repo.devices.value
        val chromecastDevice = deviceId?.let { chromecastDevicesMap?.get(it) } ?: run {
            Log.e("ChromecastController", "requestPlayback: device not found")
            return
        }
        val mediaFile = fileId.let { chromecastDevice.mediaCollection[it] } as? Configuration.MediaNode.Item ?: run {
            Log.e("ChromecastController", "requestPlayback: media file not found")
            return
        }
        val tid = transportId
        if (tid == null) {
            Log.e("ChromecastController", "requestPlayback aborted: transportId is null")
            return
        }
        Log.i("ChromecastController", "requestPlayback: title='${mediaFile.name}', url='${mediaFile.url}', mime='${mediaFile.mimeType}'")
        isSplashActive = false
        setVolume(1.0)
        val media = JSONObject().apply {
            put("contentId",   mediaFile.url)
            put("contentType", mediaFile.mimeType)
            put("streamType",  "BUFFERED")
            put("metadata", JSONObject().apply {
                put("metadataType", 1)
                put("title", mediaFile.name)
            })
        }
        val payload = JSONObject().apply {
            put("type",        Action.LOAD)
            put("requestId",   nextReqId())
            put("media",       media)
            put("autoplay",    true)
            put("currentTime", 0L)
        }
        Log.d("upnpkino", "Sending LOAD payload: $payload")
        send(tid, ActionURN.LOAD, payload.toString())
    }
    private suspend fun sendMediaCommand(command: String, args: Map<String, String>) : Boolean {
        val tid  = transportId ?: run {
            Log.w("ChromecastController", "sendMediaCommand $command: transportId is null")
            return false
        }
        val payload = when (command) {
            Action.LOAD -> {
                val fileId = args["fileId"]
                val deviceId = repo.selectedDeviceId.value
                val chromecastDevicesMap = repo.devices.value
                val chromecastDevice = deviceId?.let { chromecastDevicesMap?.get(it) } ?: run {
                    Log.e("ChromecastController", "sendMediaCommand LOAD: device not found")
                    return false
                }
                val mediaFile = fileId?.let { chromecastDevice.mediaCollection[it] } as? Configuration.MediaNode.Item ?: run {
                    Log.e("ChromecastController", "sendMediaCommand LOAD: media file not found")
                    return false
                }
                Log.i("ChromecastController", "Command LOAD: title='${mediaFile.name}', url='${mediaFile.url}', mime='${mediaFile.mimeType}'")
                isSplashActive = false
                setVolume(1.0)
                val media = JSONObject().apply {
                    put("contentId", mediaFile.url)
                    put("contentType", mediaFile.mimeType)
                    put("streamType", "BUFFERED")
                    put("metadata", JSONObject().apply {
                        put("metadataType", 1)
                        put("title", mediaFile.name)
                    })
                }
                JSONObject().apply {
                    put("type", command)
                    put("requestId", nextReqId())
                    put("media", media)
                    put("autoplay", true)
                    put("currentTime", 0L)
                }.toString()
            }

            Action.SEEK -> {
                val target = args["target"]
                val msId = mediaSessionId ?: run {
                    Log.w("ChromecastController", "sendMediaCommand SEEK: mediaSessionId is null")
                    return false
                }
                Log.i("ChromecastController", "Command SEEK: target=$target")
                JSONObject().apply {
                    put("type", command)
                    put("requestId", nextReqId())
                    put("mediaSessionId", msId)
                    put("currentTime", durationToSeconds(target!!).toDouble())
                    put("resumeState", "PLAYBACK_START")
                }.toString()
            }
            else -> {
                val msId = mediaSessionId ?: run {
                    Log.w("ChromecastController", "sendMediaCommand $command: mediaSessionId is null")
                    return false
                }
                Log.i("ChromecastController", "Command $command")
                JSONObject().apply {
                    put("type", command)
                    put("requestId", nextReqId())
                    put("mediaSessionId", msId)
                }.toString()
            }
        }
        Log.v("ChromecastController", "Sending command: $command payload: $payload")
        return send(tid, getURN(command), payload)
    }
    private suspend fun setVolume(level: Double) {
        val payload = JSONObject().apply {
            put("type", Action.SET_VOLUME)
            put("requestId", nextReqId())
            put("volume", JSONObject().apply {
                put("level", level)
                put("muted", false)
            })
        }
        send(RECEIVER_ID, ActionURN.SET_VOLUME, payload.toString())
    }
    private suspend fun startPolling() {
        pollingMutex.withLock {
            if (pollingJob?.isActive == true) return
            val tid = transportId ?: return
            pollingJob = scope.launch {
                while (isActive && isConnected) {
                    delay(PROGRESS_POLL_MS.milliseconds)
                    val payload = JSONObject().apply {
                        put("type", Action.GET_STATUS)
                        put("requestId", nextReqId()) }.toString()
                    runCatching {
                        send(tid, ActionURN.GET_STATUS, payload)
                    }
                }
            }
        }
    }
    private fun buildTrustAllSslContext(): SSLContext {
        val trustAll = arrayOf<TrustManager>(@SuppressLint("CustomX509TrustManager")
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        return SSLContext.getInstance("TLS").apply { init(null, trustAll, SecureRandom()) }
    }
    private suspend fun searchForDevices() = withContext(Dispatchers.IO) {
        scope.launch {
            delay(8000.milliseconds)
            repo.setSearchForDevices(false)
        }
        val group = InetAddress.getByName(MDNS_IP)
        var mSocket: MulticastSocket? = null
        try {
            mSocket = MulticastSocket(MDNS_PORT)
            mSocket.joinGroup(group)
            mSocket.soTimeout = 2000
            val query = buildMdnsQuery(SERVICE_TYPE)
            val packet = DatagramPacket(query, query.size, group, MDNS_PORT)
            while (isActive && repo.searchForDevices.value) {
                mSocket.send(packet)
                val buffer = ByteArray(1500)
                val response = DatagramPacket(buffer, buffer.size)
                while (repo.searchForDevices.value) {
                    try {
                        mSocket.receive(response)
                        parseMdnsResponse(response.data.copyOf(response.length), response.length, response.address)
                    } catch (e: SocketTimeoutException) {
                        break
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                    }
                    delay(3000.milliseconds)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
        } finally {
            try {
                mSocket?.leaveGroup(group)
                mSocket?.close()
            } catch (e: Exception) {
            }
        }
    }
    private fun buildMdnsQuery(serviceType: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(0)    // ID
        dos.writeShort(0)    // Flags
        dos.writeShort(1)    // Questions
        dos.writeShort(0)    // Answers
        dos.writeShort(0)    // Authority
        dos.writeShort(0)    // Additional
        for (part in serviceType.split(".")) {
            dos.writeByte(part.length)
            dos.writeBytes(part)
        }
        dos.writeByte(0)
        dos.writeShort(12)   // PTR
        dos.writeShort(1)    // IN
        return baos.toByteArray()
    }
    private fun parseMdnsResponse(data: ByteArray, length: Int, remoteAddress: InetAddress) {
        try {
            val bais = ByteArrayInputStream(data, 0, length)
            val dis = DataInputStream(bais)
            dis.skipBytes(2) // ID
            dis.skipBytes(2) // Flags
            val qdCount = dis.readUnsignedShort()
            val anCount = dis.readUnsignedShort()
            dis.skipBytes(2) // NS
            val arCount = dis.readUnsignedShort()
            repeat(qdCount) { skipName(dis, data); dis.skipBytes(4) }
            val records = mutableListOf<DnsRecord>()
            repeat(anCount + arCount) {
                val name = readName(dis, data)
                val type = dis.readUnsignedShort()
                dis.skipBytes(2) // Class
                dis.skipBytes(4) // TTL
                val rdLen = dis.readUnsignedShort()
                val rdata = ByteArray(rdLen)
                dis.readFully(rdata)
                records.add(DnsRecord(name, type, rdata))
            }
            // Chromecast discovery:
            // 1. PTR record mapping _googlecast._tcp.local to a device name (e.g. Chromecast-xyz._googlecast._tcp.local)
            // 2. SRV record mapping device name to port and target host
            // 3. TXT record mapping device name to metadata (friendly name 'fn')
            // 4. A record mapping target host to IP
            val ptrs = records.filter { it.type == 12 }
            val srvs = records.filter { it.type == 33 }
            val txts = records.filter { it.type == 16 }
            val as_  = records.filter { it.type == 1 }
            ptrs.forEach { ptr ->
                val serviceName = readName(ByteArrayInputStream(ptr.rdata), data)
                if (serviceName.contains(SERVICE_TYPE)) {
                    val srv = srvs.find { it.name.equals(serviceName, ignoreCase = true) }
                    val txt = txts.find { it.name.equals(serviceName, ignoreCase = true) }
                    if (srv != null) {
                        val srvDis = DataInputStream(ByteArrayInputStream(srv.rdata))
                        srvDis.skipBytes(4) // Priority, weight
                        val port = srvDis.readUnsignedShort()
                        val target = readName(srvDis, data)
                        val friendlyName = parseFriendlyName(txt?.rdata) ?: serviceName.substringBefore(".")
                        // IP in A records, otherwise sender's address
                        val aRecord = as_.find { it.name.equals(target, ignoreCase = true) }
                        val host = if (aRecord != null && aRecord.rdata.size == 4) {
                            InetAddress.getByAddress(aRecord.rdata)
                        } else {
                            remoteAddress
                        }
                        registerDevice(serviceName, friendlyName, host, port)
                    }
                }
            }
        } catch (e: Exception) {
        }
    }
    private data class DnsRecord(val name: String, val type: Int, val rdata: ByteArray)
    private fun readName(dis: InputStream, fullData: ByteArray, depth: Int = 0): String {
        if (depth > 10) return ""
        val sb = StringBuilder()
        var len = dis.read()
        while (len > 0) {
            if (len and 0xC0 == 0xC0) {
                val offset = ((len and 0x3F) shl 8) or dis.read()
                sb.append(readName(ByteArrayInputStream(fullData, offset, fullData.size - offset), fullData, depth + 1))
                return sb.toString()
            }
            val part = ByteArray(len)
            dis.read(part)
            sb.append(String(part, Charsets.UTF_8)).append(".")
            len = dis.read()
        }
        return sb.toString().removeSuffix(".")
    }
    private fun skipName(dis: DataInputStream, fullData: ByteArray) {
        var len = dis.readUnsignedByte()
        while (len > 0) {
            if (len and 0xC0 == 0xC0) { dis.skipBytes(1); return }
            dis.skipBytes(len)
            len = dis.readUnsignedByte()
        }
    }
    private fun parseFriendlyName(txtData: ByteArray?): String? {
        if (txtData == null) return null
        var pos = 0
        while (pos < txtData.size) {
            val len = txtData[pos++].toInt() and 0xFF
            if (pos + len > txtData.size) break
            val entry = String(txtData, pos, len, Charsets.UTF_8)
            if (entry.startsWith("fn=")) return entry.substring(3)
            pos += len
        }
        return null
    }
    fun registerDevice(name: String, friendlyName: String, host: InetAddress, port: Int) {
        if (!repo.searchForDevices.value) return
        if (chromecastDevices.values.any { it.name == name }) return
        if (!pendingDevices.add(name)) return
        Log.d("ChromecastController", "Registering device: $friendlyName at $host:$port")
        try {
            val node = ChromecastNodeModel(name, friendlyName, host, port)
            node.setMediaCollection(UpnpRepository.kinoService.sharedMediaCollection.value)
            chromecastDevices[name] = node
            scope.launch(Dispatchers.Main) {
                repo.setDevices(chromecastDevices.toMap())
            }
        } finally {
            pendingDevices.remove(name)
        }
    }
    private fun jsonType(payload: String): String = runCatching {
        JSONObject(payload).optString("type", "")
    }.getOrDefault("")
    private fun ByteArrayOutputStream.writeTag(tag: Int) = writeVarint(tag)
    private fun ByteArrayOutputStream.writeStringField(tag: Int, value: String) {
        val utf8 = value.toByteArray(Charsets.UTF_8); writeTag(tag); writeVarint(utf8.size); write(utf8)
    }
    private fun ByteArrayOutputStream.writeVarint(value: Int) {
        var v = value; while (v and 0x7F.inv() != 0) { write((v and 0x7F) or 0x80); v = v ushr 7 }; write(v)
    }
    fun release() {
        resetChromecastActivity()
        scope.cancel()
    }
    private fun resetChromecastActivity() {
        stopPlaying()
        repo.setSelectedDeviceId(null)
        repo.setDevices(null)
        chromecastDevices.clear()
    }
    private fun stopPlaying(){
        Log.d("ChromecastController", "stopPlaying")
        val tid  = transportId
        val msId = mediaSessionId
        runBlocking(Dispatchers.IO) {
            runCatching {
                if (tid != null && msId != null && isConnected) {
                    send(tid, ActionURN.STOP, JSONObject().apply {
                        put("type",           Action.STOP)
                        put("requestId",      nextReqId())
                        put("mediaSessionId", msId)
                    }.toString())
                }
                if (isConnected) {
                    send(RECEIVER_ID, ActionURN.STOP_APP, JSONObject().apply {
                        put("type",      Action.STOP)
                        put("requestId", nextReqId())
                    }.toString())
                }
            }
        }
        val sockToClose: SSLSocket?
        synchronized(resourceLock) {
            sockToClose = socket
            isConnected = false
            socket = null
            writer = null
            disconnectSignal = null
        }
        connectionJob?.cancel()
        connectionJob = null
        connectionScope?.cancel()
        connectionScope = null
        transportId = null
        mediaSessionId = null
        isSplashActive = false
        pollingJob?.cancel()
        pollingJob = null
        runCatching {
            sockToClose?.close()
        }
        repo.setStreamingFlag(Action.STOP)
        repo.setStreamingFeedbackFlag(ActionFeedback.STOPPED)
        repo.setSeekBarDuration("00:00:00")
        repo.setSeekBarPosition("00:00:00")
        repo.setLoadingState(false)
    }
}
