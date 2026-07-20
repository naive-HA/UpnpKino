package acab.naiveha.upnpkino

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.collections.set
import acab.naiveha.upnpkino.Constants.Dlna.ActionFeedback
import acab.naiveha.upnpkino.Constants.Dlna.Action
import acab.naiveha.upnpkino.Constants.durationToSeconds
import acab.naiveha.upnpkino.Constants.secondsToDuration
import kotlin.String
import kotlin.time.Duration.Companion.milliseconds
import android.util.Log

class DlnaController(val context: Context, val upnpService: UpnpService) {
    companion object {
        private const val SUBSCRIPTION_POLL_MS = 1_000L
        private const val SUBSCRIPTION_TIMEOUT = 3_600
        private const val PLAYING_PROGRESS_POLL_MS = 1_000L
        private const val BACKGROUND_PROGRESS_POLL_MS = 10_000L
        private const val PAUSED_PROGRESS_POLL_MS = 4_000L
        private const val HTTP_TIMEOUT = 5L
    }
    private val repo = UpnpRepository.dlna
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .connectTimeout(HTTP_TIMEOUT, TimeUnit.SECONDS)
        .readTimeout(HTTP_TIMEOUT, TimeUnit.SECONDS)
        .build()
    @Volatile private var pollingJob: Job? = null
    @Volatile private var subscriptionJob: Job? = null
    private val pollingMutex = Mutex()
    private val subscriptionSids = ConcurrentHashMap<String, String>()
    private val minSubscriptionTimeoutSeconds = AtomicInteger(SUBSCRIPTION_TIMEOUT)
    private val subscriptionMutex = Mutex()
    private val pendingDevices = ConcurrentHashMap.newKeySet<String>()
    val dlnaDevices: MutableMap<String, DlnaNodeModel> = ConcurrentHashMap<String, DlnaNodeModel>()
    init {
        scope.launch {
            repo.searchForDevices.collect { searching ->
                if (searching) {
                    dlnaDevices.clear()
                    repo.setDevices(null)
                    //M-Search notification sent by Multicast server
                }
            }
        }
        scope.launch {
            repo.selectedDeviceId.collect { deviceId ->
                Log.d("DlnaController", "Selected device changed: $deviceId")
                if (deviceId == null) {
                    resetDlnaActivity()
                } else {
                    //no handshake required
                }
            }
        }
        scope.launch {
            repo.streamingFlag.collect { streamingFlag ->
                if (streamingFlag != null) {
                    Log.d("DlnaController", "Streaming command received: $streamingFlag")
                    val deviceId = repo.selectedDeviceId.value
                    val devices = repo.devices.value
                    val device = deviceId?.let { devices?.get(it) } ?: run {
                        Log.e("DlnaController", "No device selected for command $streamingFlag")
                        return@collect
                    }
                    val fileId = repo.selectedMediaFileId.value
                    if (fileId?.let { device.mediaCollection[it] } !is Configuration.MediaNode.Item) {
                        Log.e("DlnaController", "Invalid media item for command $streamingFlag")
                        return@collect
                    }
                    val streamingFeedbackFlag = repo.streamingFeedbackFlag.value
                    when (streamingFlag) {
                        Action.PLAY -> {
                            if (streamingFeedbackFlag == ActionFeedback.PAUSED_PLAYBACK) {
                                if (sendMediaCommand(Action.PLAY, mapOf()) != null) {
                                    return@collect
                                } //else ERROR
                            } else {
                                if (sendMediaCommand(Action.SET_AV_TRANSPORT_URI, mapOf("fileId" to fileId)) != null) {
                                    if (sendMediaCommand(Action.PLAY, mapOf()) != null) {
                                        subscribeToDeviceEvents(device)
                                        return@collect
                                    } //else ERROR
                                } //else ERROR
                            }
                        }
                        Action.PAUSE -> {
                            if (sendMediaCommand(Action.PAUSE, mapOf()) != null) {
                                return@collect
                            } //else ERROR
                        }
                        Action.STOP -> {
                            if (sendMediaCommand(Action.STOP, mapOf()) != null) {
                                stopPlaying()
                                return@collect
                            } //else ERROR
                        }
                        Action.STOP_LOCAL -> {
                            stopPlayingLocal()
                            return@collect
                        }
                        Action.SEEK -> {
                            val target = repo.seekBarTarget.value
                            Log.d("DlnaController", "Seeking to target: $target")
                            if (target != null && sendMediaCommand(Action.SEEK, mapOf("Target" to target)) != null) {
                                return@collect
                            } //else ERROR
                        }
                    }
                    Log.e("DlnaController", "Error executing streaming command: $streamingFlag")
                    repo.setStreamingFlag(Action.ERROR)
                    resetDlnaActivity()
                }
            }
        }
        scope.launch {
            repo.streamingFeedbackFlag.collect { streamingFeedbackFlag ->
                val deviceId = repo.selectedDeviceId.value
                val streamingFlag = repo.streamingFlag.value
                if (deviceId != null && streamingFeedbackFlag != null && streamingFlag in listOf(Action.PLAY, Action.PAUSE, Action.SEEK)) {
                     when (streamingFeedbackFlag) {
                        ActionFeedback.STOPPED,
                        ActionFeedback.NO_MEDIA_PRESENT -> {
                            stopPlaying()
                        }
                        ActionFeedback.PLAYING,
                        ActionFeedback.PAUSED_PLAYBACK,
                        ActionFeedback.TRANSITIONING -> {
                            startPolling()
                        }
                        ActionFeedback.DISCONNECTED -> {
                            repo.setStreamingFlag(Action.STOP_LOCAL)
                        }
                    }
                }
            }
        }
        scope.launch {
            repo.isDlnaActivityVisible.collect { isDlnaActivityVisible ->
                val deviceId = repo.selectedDeviceId.value
                val devices = repo.devices.value
                val device = deviceId?.let { devices?.get(it) }
                if (device != null) {
                    if (!isDlnaActivityVisible) {
                        pollingJob?.cancel()
                        pollingJob = null
                    } else {
                        when (repo.streamingFeedbackFlag.value) {
                            ActionFeedback.PLAYING,
                            ActionFeedback.PAUSED_PLAYBACK,
                            ActionFeedback.TRANSITIONING -> {
                                if (validateRemoteSession()) {
                                    startPolling()
                                } else {
                                    repo.setStreamingFeedbackFlag(ActionFeedback.DISCONNECTED)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    private suspend fun subscribeToDeviceEvents(dlnaDevice: DlnaNodeModel) {
        Log.d("DlnaController", "Subscribing to device events: ${dlnaDevice.friendlyName}")
        subscriptionMutex.withLock {
            if (subscriptionJob?.isActive == true) {
                return@withLock
            }
            subscriptionSids.clear()
            subscriptionJob = scope.launch {
                while (isActive) {
                    val services = listOf("AVTransport", "ConnectionManager")
                    for (service in services) {
                        val eventUrl = dlnaDevice.eventUrls[service]
                        if (eventUrl != null) {
                            val sid = subscriptionSids[service]
                            val result = sendSubscribeRequest(dlnaDevice, eventUrl, "/${upnpService.configuration.uuid}/dlnaSubscriptionCallback", sid)
                            if (result != null) {
                                val (newSid, timeout) = result
                                subscriptionSids[service] = newSid
                                if (timeout < minSubscriptionTimeoutSeconds.get()) {
                                    minSubscriptionTimeoutSeconds.set(timeout)
                                }
                            } else {
                                subscriptionSids.remove(service)
                            }
                        }
                    }
                    if (subscriptionSids.isEmpty()) {
                        delay(SUBSCRIPTION_POLL_MS.milliseconds)
                    } else {
                        val renewalDelay = (minSubscriptionTimeoutSeconds.get() * 0.8 * SUBSCRIPTION_POLL_MS).toLong()
                        delay(renewalDelay.milliseconds)
                    }
                }
            }
            scope.launch {
                syncSeekBar()
                getStreamingFeedbackFlag()
            }
        }
    }
    private fun sendSubscribeRequest(device: DlnaNodeModel, url: String, callback: String, sid: String? = null): Pair<String, Int>? {
        val fullUrl = resolveUrl(device, url)
        Log.v("DlnaController", "Sending SUBSCRIBE request to $fullUrl (sid=$sid)")
        return try {
            val builder = Request.Builder()
                .url(fullUrl)
                .method("SUBSCRIBE", null)
                .addHeader("HOST", device.location.substringBefore("/"))

            if (sid != null) {
                builder.addHeader("SID", sid)
            } else {
                val callbackUrl = "<http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}$callback>"
                builder.addHeader("CALLBACK", callbackUrl)
                builder.addHeader("NT", "upnp:event")
            }
            builder.addHeader("TIMEOUT", "Second-3600")
            client.newCall(builder.build()).execute().use { response ->
                if (response.isSuccessful) {
                    val actualSid = response.header("SID") ?: sid ?: "unknown-sid"
                    val timeoutHeader = response.header("TIMEOUT") ?: "Second-3600"
                    val timeoutSeconds = timeoutHeader.substringAfter("Second-").toIntOrNull() ?: SUBSCRIPTION_TIMEOUT
                    Pair(actualSid, timeoutSeconds)
                } else if (response.code == 412 && sid != null) {
                    sendSubscribeRequest(device, url, callback, null)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    private suspend fun startPolling() {
        val deviceId = repo.selectedDeviceId.value
        val devices = repo.devices.value
        val device = deviceId?.let { devices?.get(it) }
        if (device == null) {
            Log.w("DlnaController", "Cannot start polling: no device selected")
            return
        }
        pollingMutex.withLock {
            if (pollingJob?.isActive == true) return@withLock
            Log.d("DlnaController", "Starting polling for ${device.friendlyName}")
            pollingJob = scope.launch {
                try {
                    while (isActive) {
                        val streamingFeedbackFlag = repo.streamingFeedbackFlag.value
                        val streamingFlag = repo.streamingFlag.value
                        val isStopped = when (streamingFeedbackFlag) {
                            ActionFeedback.STOPPED,
                            ActionFeedback.DISCONNECTED,
                            ActionFeedback.NO_MEDIA_PRESENT -> true
                            else -> false
                        } || streamingFlag == Action.ERROR
                        if (isStopped) {
                            break
                        }
                        when (streamingFeedbackFlag) {
                            ActionFeedback.PLAYING, ActionFeedback.TRANSITIONING -> {
                                syncSeekBar()
                                getStreamingFeedbackFlag()
                                delay(PLAYING_PROGRESS_POLL_MS.milliseconds)
                            }
                            ActionFeedback.PAUSED_PLAYBACK -> {
                                getStreamingFeedbackFlag()
                                delay(PAUSED_PROGRESS_POLL_MS.milliseconds)
                            }
                            else -> {
                                getStreamingFeedbackFlag()
                                delay(BACKGROUND_PROGRESS_POLL_MS.milliseconds)
                            }
                        }
                    }
                } finally {
                }
            }
        }
    }
    private fun syncSeekBar() {
        val response = sendMediaCommand(Action.GET_POSITION_INFO, mapOf())
        if (response != null) {
            val responseData = upnpService.upnpMessages.parseDlnaResponse(response)
            if (responseData.isNotEmpty()) {
                val seekBarDuration = responseData["TrackDuration"] ?: repo.seekBarDuration.value
                val seekBarPosition = responseData["RelTime"] ?: repo.seekBarPosition.value
                updateSeekBarPosition(seekBarPosition, seekBarDuration)
            }
        }
    }
    private fun getStreamingFeedbackFlag() {
        val response = sendMediaCommand(Action.GET_TRANSPORT_INFO, mapOf())
        if (response != null) {
            val responseData = upnpService.upnpMessages.parseDlnaResponse(response)
            if (responseData.isNotEmpty()) {
                responseData["CurrentTransportState"]?.let {
                    repo.setStreamingFeedbackFlag(it)
                }
            }
        }
    }
    private fun updateSeekBarPosition(position: String, duration: String) {
        //position format is like "00:00:00"
        //duration format is like "00:00:00"
        if (repo.userIsSeeking.value) return
        val positionInSeconds = durationToSeconds(position)
        val durationInSeconds = durationToSeconds(duration).takeIf { it > 0L }
        if (durationInSeconds == null){
            repo.setSeekBarPosition(secondsToDuration(positionInSeconds))
        } else if (durationInSeconds > 0L && positionInSeconds > durationInSeconds) {
            stopPlaying()
        } else {
            repo.setSeekBarPosition(secondsToDuration(positionInSeconds))
            repo.setSeekBarDuration(secondsToDuration(durationInSeconds))
        }
    }
    private fun validateRemoteSession(): Boolean {
        val fileId = repo.selectedMediaFileId.value ?: return false
        val response = sendMediaCommand(Action.GET_MEDIA_INFO, mapOf())
        if (response != null) {
            val responseData = upnpService.upnpMessages.parseDlnaResponse(response)
            if (responseData.isNotEmpty()) {
                val playingUri = responseData["CurrentURI"]
                if (playingUri.isNullOrEmpty()) return false
                return playingUri.contains(fileId)
            }
        }
        return false
    }
    private fun sendMediaCommand(command: String, args: Map<String, String>): String? {
        val deviceId = repo.selectedDeviceId.value
        val devices = repo.devices.value
        val device = deviceId?.let { devices?.get(it) } ?: run {
            Log.e("DlnaController", "sendMediaCommand: no device selected")
            return null
        }
        val soapUrl = device.controlUrls[Constants.Dlna.getService(command)] ?: run {
            Log.e("DlnaController", "sendMediaCommand: service not supported by device")
            return null
        }
        Log.v("DlnaController", "Sending SOAP command: $command to ${device.friendlyName}")
        return try {
            val fullUrl = resolveUrl(device, soapUrl)
            val payload = upnpService.upnpMessages.draftDlnaMessage(command, args, device.mediaCollection)
            val request = Request.Builder()
                .url(fullUrl)
                .post(payload.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .addHeader("SOAPACTION", "\"${Constants.Dlna.getURN(command)}#$command\"")
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                Log.v("DlnaController", "SOAP command $command response: code=${response.code}")
                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    val expectedResponse = Constants.Dlna.getResponse(command)
                    if (expectedResponse.isNotEmpty() && upnpService.upnpMessages.parseUpnpHttpRequest(
                            responseBody
                        ) == expectedResponse
                    ) {
                        return responseBody
                    }
                }
                Log.w("DlnaController", "SOAP command $command failed: code=${response.code} body=$responseBody")
                return null
            }
        } catch (e: Exception) {
            Log.e("DlnaController", "Exception sending SOAP command: $command", e)
            repo.setStreamingFlag(Action.ERROR)
            resetDlnaActivity()
            null
        }
    }
    fun handleMediaEvent(remoteAddr: String, payload: String) {
        Log.v("DlnaController", "Received event from $remoteAddr")
        when (repo.streamingFeedbackFlag.value) {
            ActionFeedback.STOPPED,
            ActionFeedback.DISCONNECTED,
            ActionFeedback.NO_MEDIA_PRESENT -> return
        }
        when (repo.streamingFlag.value) {
            Action.ERROR -> return
        }
        val selectedDeviceId = repo.selectedDeviceId.value ?: return
        val device = repo.devices.value?.get(selectedDeviceId) ?: return
        val deviceHost = try {
            java.net.URL(device.location).host
        } catch (e: Exception) {
            null
        }
        if (deviceHost != remoteAddr) {
            return
        }
        val eventData = upnpService.upnpMessages.parseDlnaEvent(payload)
        if (eventData.isNotEmpty()) {
            if (eventData.containsKey("TransportState")){
                repo.setStreamingFeedbackFlag(eventData["TransportState"])
            }
            if (eventData.containsKey("CurrentTrackDuration") || eventData.containsKey("RelTime")){
                val seekBarDuration = eventData["CurrentTrackDuration"] ?: repo.seekBarDuration.value
                val seekBarPosition = eventData["RelTime"] ?: repo.seekBarPosition.value
                updateSeekBarPosition(seekBarPosition, seekBarDuration)
            }
            if (eventData.containsKey("AVTransportURI")){
                eventData["AVTransportURI"]?.let { newUri ->
                    val fileId = repo.selectedMediaFileId.value
                    if (fileId != null && !newUri.contains(fileId)) {
                        stopPlaying()
                    }
                }
            }
        }
    }
    fun registerDevice(location: String) {
        if (!repo.searchForDevices.value) return
        if (dlnaDevices.values.any { it.location == location }) return
        if (!pendingDevices.add(location)) return
        Log.d("DlnaController", "Registering device at location: $location")
        scope.launch {
            try {
                val id = upnpService.configuration.generateRandomId(10, dlnaDevices.keys)
                interrogateDevice(location)?.let { deviceDescription ->
                    Log.i("DlnaController", "Discovered DLNA device: ${deviceDescription.friendlyName} at $location")
                    if (dlnaDevices.values.none { it.location == location }) {
                        val device = DlnaNodeModel(
                            id,
                            deviceDescription.friendlyName,
                            location,
                            deviceDescription.urlBase,
                            deviceDescription.controlUrls,
                            deviceDescription.eventUrls
                        )
                        device.setMediaCollection(UpnpRepository.kinoService.sharedMediaCollection.value)
                        dlnaDevices[id] = device
                        withContext(Dispatchers.Main) {
                            repo.setDevices(dlnaDevices.toMap())
                        }
                    }
                }
            } finally {
                pendingDevices.remove(location)
            }
        }
    }
    suspend fun interrogateDevice(location: String): UpnpMessages.UpnpDeviceDescription? = withContext(Dispatchers.IO) {







        
        try {
            val request = Request.Builder()
                .url("http://$location")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body?.string() ?: ""
                    upnpService.upnpMessages.parseUpnpDescription(responseBody)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }
    private fun resolveUrl(device: DlnaNodeModel, relativeUrl: String): String {
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl
        }
        val baseUrl = device.urlBase ?: if (device.location.contains("/")) {
            "http://" + device.location.substringBeforeLast("/") + "/"
        } else {
            "http://${device.location}/"
        }
        return if (relativeUrl.startsWith("/")) {
            val rootUrl = if (device.urlBase != null) {
                val uri = java.net.URI(device.urlBase)
                "${uri.scheme}://${uri.authority}"
            } else {
                "http://${device.location.substringBefore("/")}"
            }
            rootUrl + relativeUrl
        } else {
            baseUrl + relativeUrl
        }
    }
    fun release() {
        resetDlnaActivity()
        scope.cancel()
    }
    private fun resetDlnaActivity() {
        stopPlaying()
        repo.setSelectedDeviceId(null)
        repo.setDevices(null)
        dlnaDevices.clear()
    }
    private fun stopPlaying(){
        Log.d("DlnaController", "stopPlaying")
        val selectedDeviceId = repo.selectedDeviceId.value
        val dlnaDevicesMap = repo.devices.value
        val dlnaDevice = selectedDeviceId?.let { dlnaDevicesMap?.get(it) }
        if (dlnaDevice != null) {
            runBlocking(Dispatchers.IO) {
                runCatching { sendMediaCommand(Action.STOP, mapOf()) }
            }
        }
        repo.setStreamingFlag(Action.STOP)
        repo.setStreamingFeedbackFlag(ActionFeedback.STOPPED)
        stopPlayingLocal()
    }
    private fun stopPlayingLocal(){
        pollingJob?.cancel()
        pollingJob = null
        subscriptionJob?.cancel()
        subscriptionSids.clear()
        repo.setSeekBarDuration("00:00:00")
        repo.setSeekBarPosition("00:00:00")
        repo.setLoadingState(false)
    }
}