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
import kotlin.text.contains

class DlnaController(val context: Context, val upnpService: UpnpService) {
    companion object {
        private const val SUBSCRIPTION_POLL_MS = 1_000L
        private const val SUBSCRIPTION_TIMEOUT = 3_600
        private const val PLAYING_PROGRESS_POLL_MS = 1_000L
        private const val PAUSED_PROGRESS_POLL_MS = 2_000L
        private const val BACKGROUND_PROGRESS_POLL_MS = 10_000L
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
                    val devices = repo.devices.value
                    val device = deviceId.let { devices?.get(it) } ?: run {
                        Log.e("DlnaController", "No device selected")
                        repo.setSelectedDeviceId(null)
                        return@collect
                    }
                    val deviceMediaCollection = UpnpRepository.kinoService.sharedMediaCollection.value.toMutableMap()
                    device.setMediaCollection(deviceMediaCollection) // TODO filter out media files which cannot be played natively or transcoded
                }
            }
        }
        scope.launch {
            repo.selectedMediaFileId.collect { selectedMediaFileId ->
                if (selectedMediaFileId != null) {
                    val selectedDeviceId = repo.selectedDeviceId.value
                    val selectedDevice = repo.devices.value?.get(selectedDeviceId) ?: return@collect
                    val mediaFile = selectedDevice.mediaCollection[selectedMediaFileId]
                    if (mediaFile is MediaCollection.MediaNode.Item) {
                        val caps = upnpService.transcoderController.capabilities
                        val transcodeTarget = resolveTranscodeTarget(
                            sourceItem = mediaFile,
                            device = selectedDevice,
                            canEncodeVideo = { codec: Constants.Transcoder.VideoCodec -> caps?.verifiedVideo?.get(codec)?.isNotEmpty() == true },
                            canEncodeVideoAtRes = { codec: Constants.Transcoder.VideoCodec, res: Constants.Transcoder.Resolution -> caps?.canEncode(codec, res) == true },
                            canEncodeAudio = { codec: Constants.Transcoder.AudioCodec -> caps?.canEncodeAudio(codec) == true }
                        )
                        Log.d("DlnaController", "Resolved transcode target for ${mediaFile.name}: $transcodeTarget")
                    }
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
                    if (fileId?.let { device.mediaCollection[it] } !is MediaCollection.MediaNode.Item) {
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
                        Action.ERROR -> {
                            repo.setStreamingFlag(null)
                            resetDlnaActivity()
                        }
                        else -> {
                            Log.e("DlnaController", "Error executing streaming command: $streamingFlag")
                            repo.setStreamingFlag(Action.ERROR)
                            resetDlnaActivity()
                        }
                    }
                }
            }
        }
        scope.launch {
            repo.streamingFeedbackFlag.collect { streamingFeedbackFlag ->
                val deviceId = repo.selectedDeviceId.value
                val streamingFlag = repo.streamingFlag.value
                if (deviceId != null && streamingFeedbackFlag != null && streamingFlag in listOf(Action.PLAY, Action.PAUSE, Action.SEEK)) {
                    Log.d("DlnaController", "streamingFeedbackFlag set to: $streamingFeedbackFlag")
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
        Log.d("DlnaController", "Sending SUBSCRIBE request to $fullUrl (sid=$sid)")
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
                                syncSeekBar()
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
    private fun sendMediaCommand(command: Action, args: Map<String, String>): String? {
        val deviceId = repo.selectedDeviceId.value
        val devices = repo.devices.value
        val device = deviceId?.let { devices?.get(it) } ?: run {
            Log.e("DlnaController", "sendMediaCommand: no device selected")
            return null
        }
        val soapUrl = device.controlUrls[command.service] ?: run {
            Log.e("DlnaController", "sendMediaCommand: service not supported by device")
            return null
        }
        Log.d("DlnaController", "Sending SOAP command: ${command.actionName} to ${device.friendlyName}")
        return try {
            val fullUrl = resolveUrl(device, soapUrl)
            val serviceUrn = device.serviceUrns[command.service] ?: run {
                Log.e("DlnaController", "Service ${command.service} not supported by device")
                throw Exception("Service ${command.service} not supported by device")
            }
            val payload = upnpService.upnpMessages.draftDlnaMessage(command.actionName, serviceUrn, args, device.mediaCollection)
            val request = Request.Builder()
                .url(fullUrl)
                .post(payload.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .addHeader("SOAPACTION", "\"$serviceUrn#${command.actionName}\"")
                .build()
            client.newCall(request).execute().use { response ->
                val responseBody = response.body.string()
                Log.d("DlnaController", "SOAP command ${command.actionName} response: code=${response.code}")
                if (response.isSuccessful && responseBody.isNotEmpty()) {
                    val expectedResponse = command.responseName
                    if (expectedResponse.isNotEmpty() && upnpService.upnpMessages.parseUpnpHttpRequest(responseBody) == expectedResponse
                    ) {
                        return responseBody
                    }
                }
                Log.w("DlnaController", "SOAP command ${command.actionName} failed: code=${response.code} body=$responseBody")
                return null
            }
        } catch (e: Exception) {
            Log.e("DlnaController", "Exception sending SOAP command: ${command.actionName}", e)
            repo.setStreamingFlag(Action.ERROR)
            null
        }
    }
    fun handleMediaEvent(remoteAddr: String, payload: String) {
        Log.d("DlnaController", "Received event from $remoteAddr")
        when (repo.streamingFeedbackFlag.value) {
            ActionFeedback.STOPPED,
            ActionFeedback.DISCONNECTED,
            ActionFeedback.NO_MEDIA_PRESENT -> return
        }
        if (repo.streamingFlag.value == Action.ERROR) {
             return
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
                            deviceDescription.serviceUrns,
                            deviceDescription.controlUrls,
                            deviceDescription.eventUrls
                        )
                        val command = Action.GET_PROTOCOL_INFO
                        val controlUrl = device.controlUrls[command.service] ?: run {
                            Log.e("DlnaController", "Service not supported by device")
                            repo.setSelectedDeviceId(null)
                            return@let
                        }
                        val serviceUrn = device.serviceUrns[command.service] ?: run {
                            Log.e("DlnaController", "Service not supported by device")
                            repo.setSelectedDeviceId(null)
                            return@let
                        }
                        val fullUrl = resolveUrl(device, controlUrl)
                        val payload = upnpService.upnpMessages.draftDlnaMessage(command.actionName, serviceUrn, mapOf(), device.mediaCollection)
                        val request = Request.Builder()
                            .url(fullUrl)
                            .post(payload.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                            .addHeader("SOAPACTION", "\"$serviceUrn#${command.actionName}\"")
                            .build()
                        client.newCall(request).execute().use { response ->
                            val responseBody = response.body.string()
                            Log.d("DlnaController", "SOAP command ${command.actionName} response: code=${response.code}")
                            if (response.isSuccessful && responseBody.isNotEmpty()) {
                                val expectedResponse = command.responseName
                                if (expectedResponse.isNotEmpty() && upnpService.upnpMessages.parseUpnpHttpRequest(responseBody) == expectedResponse) {
                                    val responseData = upnpService.upnpMessages.parseDlnaResponse(responseBody)
                                    if (responseData.isNotEmpty()) {
                                        val sinkData = responseData["Sink"] ?: ""
                                        if (sinkData.isBlank()) {
                                            return@let
                                        }
                                        device.fromProtocolInfo(sinkData)
                                        dlnaDevices[id] = device
                                        withContext(Dispatchers.Main) {
                                            repo.setDevices(dlnaDevices.toMap())
                                        }
                                        Log.d("DlnaController", "Registered $location")
                                    }
                                }
                            }
                        }
                    }
                }
            } finally {
                pendingDevices.remove(location)
            }
        }
    }
    suspend fun interrogateDevice(location: String): UpnpNodeModel? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("http://$location")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val responseBody = response.body.string()
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

    /**
     * Resolves the optimal TranscodeProfile using a 5-step dimensional evaluation.
     */
    fun resolveTranscodeTarget(
        sourceItem: MediaCollection.MediaNode.Item,
        device: DlnaNodeModel,
        canEncodeVideo: (Constants.Transcoder.VideoCodec) -> Boolean,
        canEncodeVideoAtRes: (Constants.Transcoder.VideoCodec, Constants.Transcoder.Resolution) -> Boolean,
        canEncodeAudio: (Constants.Transcoder.AudioCodec) -> Boolean
    ): Constants.Transcoder.TranscodeProfile? {
        val sink = device.sinkCapabilities
        val sourceVideoCodec = sourceItem.videoTracks.firstOrNull()?.codec
        val sourceAudioCodec = sourceItem.audioTracks.firstOrNull()?.codec
        val sourceHeight = sourceItem.videoTracks.firstOrNull()?.resolution
            ?.split("x")?.getOrNull(1)?.toIntOrNull() ?: 1080
        val sourceTier = Constants.Transcoder.Priority.RESOLUTIONS.sortedBy { it.maxHeight }.find { it.maxHeight >= sourceHeight } ?: Constants.Transcoder.Resolution.HD_1080
        val sourceChannels = sourceItem.audioTracks.firstOrNull()?.channelCount ?: 2
        val sourceAudioQuality = Constants.Transcoder.Priority.AUDIO_QUALITIES.find { it.channelCount <= sourceChannels } ?: Constants.Transcoder.AudioQuality.STEREO
        val sourceExt = sourceItem.name.substringAfterLast('.', "").lowercase()
        val sourceContainer = when {
            sourceExt in setOf("mkv") -> Constants.Transcoder.Container.MKV
            sourceExt in setOf("ts", "mts") -> Constants.Transcoder.Container.MPEG_TS
            else -> Constants.Transcoder.Container.MP4
        }

        // Prefer Native / Minimum Effort: If sink accepts wildcard '*' or supports native source profile directly
        if (sink.isWildcard || (
            sourceVideoCodec != null && sink.supportsVideoCodec(sourceVideoCodec) &&
            sink.supportsResolution(sourceTier) &&
            sourceAudioCodec != null && sink.supportsAudioCodec(sourceAudioCodec) &&
            sink.supportsChannelCount(sourceAudioQuality.channelCount) &&
            sink.supportsContainer(sourceContainer)
        )) {
            return Constants.Transcoder.TranscodeProfile(
                videoCodec = sourceVideoCodec ?: Constants.Transcoder.VideoCodec.H264,
                resolution = sourceTier,
                audioCodec = sourceAudioCodec ?: Constants.Transcoder.AudioCodec.AAC,
                audioQuality = sourceAudioQuality,
                container = sourceContainer
            )
        }

        // Downward-only evaluation: Candidates start strictly at source tier & channel count and only go downwards
        val candidateResolutions = Constants.Transcoder.Priority.RESOLUTIONS.filter { it.maxHeight <= sourceTier.maxHeight }
        val candidateAudioQualities = Constants.Transcoder.Priority.AUDIO_QUALITIES.filter { it.channelCount <= sourceChannels }

        for (videoCodec in Constants.Transcoder.Priority.VIDEO_CODECS) {
            if (!canEncodeVideo(videoCodec)) continue

            for (resolution in candidateResolutions) {
                if (!canEncodeVideoAtRes(videoCodec, resolution)) continue

                for (audioCodec in Constants.Transcoder.Priority.AUDIO_CODECS) {
                    if (!canEncodeAudio(audioCodec)) continue

                    for (audioQuality in candidateAudioQualities) {

                        for (container in Constants.Transcoder.Priority.CONTAINERS) {
                            if (sink.supportsCombination(videoCodec, resolution, audioCodec, audioQuality, container)) {
                                return Constants.Transcoder.TranscodeProfile(
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
        return null
    }

}