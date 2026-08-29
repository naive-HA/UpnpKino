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
                    val devices = repo.devices.value
                    val device = deviceId.let { devices?.get(it) } ?: run {
                        Log.e("DlnaController", "No device selected")
                        repo.setSelectedDeviceId(null)
                        return@collect
                    }
                    val command = Action.GET_PROTOCOL_INFO
                    val controlUrl = device.controlUrls[command.service] ?: run {
                        Log.e("DlnaController", "Service not supported by device")
                        repo.setSelectedDeviceId(null)
                        return@collect
                    }
                    val serviceUrn = device.serviceUrns[command.service] ?: run {
                        Log.e("DlnaController", "Service not supported by device")
                        repo.setSelectedDeviceId(null)
                        return@collect
                    }
                    val fullUrl = resolveUrl(device, controlUrl)
                    val payload = upnpService.upnpMessages.draftDlnaMessage(command.actionName, serviceUrn, mapOf(), device.mediaCollection)
                    val request = Request.Builder()
                        .url(fullUrl)
                        .post(payload.toRequestBody("text/xml; charset=utf-8".toMediaType()))
                        .addHeader("SOAPACTION", "\"$serviceUrn#${command.actionName}\"")
                        .build()
                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string() ?: ""
                        Log.d("DlnaController", "SOAP command ${command.actionName} response: code=${response.code}")
                        if (response.isSuccessful && responseBody.isNotEmpty()) {
                            val expectedResponse = command.responseName
                            if (expectedResponse.isNotEmpty() && upnpService.upnpMessages.parseUpnpHttpRequest(responseBody) == expectedResponse
                            ) {
                                val responseData = upnpService.upnpMessages.parseDlnaResponse(responseBody)
                                if (responseData.isNotEmpty()) {
                                    val deviceMediaCollection = UpnpRepository.kinoService.sharedMediaCollection.value.toMutableMap()
                                    val sinkData = responseData["Sink"] ?: ""
                                    if (sinkData.isBlank()) {
                                        device.setMediaCollection(deviceMediaCollection)
                                        return@collect
                                    }
                                    var sinkEntries = sinkData.split(",").mapNotNull { raw ->
                                        val parts = raw.split(":", limit = 4)
                                        if (parts.size == 4) {
                                            mapOf("protocol" to parts[0],
                                                  "network" to parts[1],
                                                  "contentFormat" to parts[2].substringBefore(";"),
                                                  "dlnaProfile" to parts[3].split(";")
                                                      .firstOrNull { it.startsWith("DLNA.ORG_PN=") }
                                                      ?.substringAfter("DLNA.ORG_PN="))
                                        } else {
                                            null
                                        }
                                    }
                                    //TODO remove this
                                    sinkEntries = "http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_TN,http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_SM,http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_MED,http-get:*:image/jpeg:DLNA.ORG_PN=JPEG_LRG,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HD_50_AC3_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HD_60_AC3_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_HP_HD_AC3_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_MP_HD_AAC_MULT5_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_MP_HD_AC3_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_MP_HD_MPEG1_L3_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_MP_SD_AAC_MULT5_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_MP_SD_AC3_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=AVC_TS_MP_SD_MPEG1_L3_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS_NTSC,http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_PS_PAL,http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_HD_NA_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_NA_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=MPEG_TS_SD_EU_ISO,http-get:*:video/mpeg:DLNA.ORG_PN=MPEG1,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_SD_AAC_MULT5,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_SD_AC3,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF15_AAC_520,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_CIF30_AAC_940,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_L31_HD_AAC,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_L32_HD_AAC,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_BL_L3L_SD_AAC,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_HP_HD_AAC,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD_1080i_AAC,http-get:*:video/mp4:DLNA.ORG_PN=AVC_MP4_MP_HD_720p_AAC,http-get:*:video/mp4:DLNA.ORG_PN=MPEG4_P2_MP4_ASP_AAC,http-get:*:video/mp4:DLNA.ORG_PN=MPEG4_P2_MP4_SP_VGA_AAC,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HD_50_AC3,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HD_50_AC3_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HD_60_AC3,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HD_60_AC3_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_HP_HD_AC3_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_HD_AAC_MULT5,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_HD_AAC_MULT5_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_HD_AC3,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_HD_AC3_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_HD_MPEG1_L3,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_HD_MPEG1_L3_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_SD_AAC_MULT5,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_SD_AAC_MULT5_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_SD_AC3,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_SD_AC3_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_SD_MPEG1_L3,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=AVC_TS_MP_SD_MPEG1_L3_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_HD_NA,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_HD_NA_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_SD_EU,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_SD_EU_T,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_SD_NA,http-get:*:video/vnd.dlna.mpeg-tts:DLNA.ORG_PN=MPEG_TS_SD_NA_T,http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVSPLL_BASE,http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVSPML_BASE,http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVSPML_MP3,http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVMED_BASE,http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVMED_FULL,http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVMED_PRO,http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVHIGH_FULL,http-get:*:video/x-ms-wmv:DLNA.ORG_PN=WMVHIGH_PRO,http-get:*:video/3gpp:DLNA.ORG_PN=MPEG4_P2_3GPP_SP_L0B_AAC,http-get:*:video/3gpp:DLNA.ORG_PN=MPEG4_P2_3GPP_SP_L0B_AMR,http-get:*:audio/mpeg:DLNA.ORG_PN=MP3,http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMABASE,http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMAFULL,http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMAPRO,http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMALSL,http-get:*:audio/x-ms-wma:DLNA.ORG_PN=WMALSL_MULT5,http-get:*:audio/mp4:DLNA.ORG_PN=AAC_ISO_320,http-get:*:audio/3gpp:DLNA.ORG_PN=AAC_ISO_320,http-get:*:audio/mp4:DLNA.ORG_PN=AAC_ISO,http-get:*:audio/mp4:DLNA.ORG_PN=AAC_MULT5_ISO,http-get:*:audio/L16;rate=44100;channels=2:DLNA.ORG_PN=LPCM".split(",").mapNotNull { raw ->
                                        val parts = raw.split(":", limit = 4)
                                        if (parts.size == 4) {
                                            mapOf("protocol" to parts[0],
                                                "network" to parts[1],
                                                "contentFormat" to parts[2].substringBefore(";"),
                                                "dlnaProfile" to parts[3].split(";")
                                                    .firstOrNull { it.startsWith("DLNA.ORG_PN=") }
                                                    ?.substringAfter("DLNA.ORG_PN="))
                                        } else {
                                            null
                                        }
                                    }
                                    if (sinkEntries.isEmpty()){
                                        device.setMediaCollection(deviceMediaCollection)
                                        return@collect
                                    }
                                    val mediaItems = deviceMediaCollection.values.filterIsInstance<MediaCollection.MediaNode.Item>()
                                    for (item in mediaItems){
                                        Log.d("DlnaController", "processing: ${item.name} (${item.mimeType})")
                                        val matchedContentFormat = sinkEntries.filter {
                                            it["contentFormat"] == "*" || it["contentFormat"].equals(
                                                item.mimeType,
                                                ignoreCase = true
                                            )
                                        }
                                        if (!matchedContentFormat.isEmpty()) {
                                            if (matchedContentFormat.any { it["dlnaProfile"] == null }){
                                                Log.d("DlnaController", "dlnaProfile is null: remote device accepts anything; file can be natively played")
                                                continue
                                            }
                                            val nativelyPlayed = matchedContentFormat.any { entry ->
                                                val profile = entry["dlnaProfile"] ?: return@any false
                                                val profileVideo = item.videoCodec == null || (item.dlnaProfileVideo.isNotEmpty() && item.dlnaProfileVideo.all { profile.contains(it) })
                                                val profileAudio = item.audioCodec == null || (item.dlnaProfileAudio.isNotEmpty() && item.dlnaProfileAudio.all { profile.contains(it) })
                                                profileVideo && profileAudio
                                            }
                                            if (nativelyPlayed) {
                                                Log.d("DlnaController", "natively playable: ${item.name} (${item.mimeType})")
                                                continue
                                            }
                                        }

                                        Log.w("DlnaController", "needs transcoding: ${item.name} (${item.mimeType})")

                                        val canBeTranscoded = true
                                        if (canBeTranscoded) {
//                                            deviceMediaCollection[item.id] = item.copy()

                                            continue
                                        } else {
                                            val parent = deviceMediaCollection[item.parentId] as? MediaCollection.MediaNode.Container
                                            if (parent != null) {
                                                deviceMediaCollection[item.parentId] = parent.copy(children = parent.children - item.id)
                                            }
                                            deviceMediaCollection.remove(item.id)
                                            Log.e("DlnaController", "Cannot be played nor transcoded: ${item.name} (${item.mimeType})")
                                        }
                                    }
                                    val mediaContainers = deviceMediaCollection.values.filterIsInstance<MediaCollection.MediaNode.Container>()
                                    var foundEmptyContainer = false
                                    do {
                                        for (container in mediaContainers){
                                            if (container.children.isEmpty()){
                                                foundEmptyContainer = true
                                                val parent = deviceMediaCollection[container.parentId] as? MediaCollection.MediaNode.Container
                                                if (parent != null) {
                                                    deviceMediaCollection[container.parentId] = parent.copy(children = parent.children - container.id)
                                                }
                                                deviceMediaCollection.remove(container.id)
                                            }
                                        }
                                    } while (foundEmptyContainer)
                                    device.setMediaCollection(deviceMediaCollection)
                                    return@collect
                                } else {
                                    Log.w("upnpkino", "SOAP command ${command.actionName} failed: dlna response is empty: $responseData")
                                }
                            } else {
                                Log.w("upnpkino", "SOAP command ${command.actionName} failed: not the expected response: $expectedResponse")
                            }
                        } else {
                            Log.w("upnpkino", "SOAP command ${command.actionName} failed: code=${response.code} body=$responseBody")
                        }
                        repo.setSelectedDeviceId(null)
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
                val responseBody = response.body?.string() ?: ""
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
            resetDlnaActivity()
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
                        dlnaDevices[id] = device
                        withContext(Dispatchers.Main) {
                            repo.setDevices(dlnaDevices.toMap())
                        }
                        Log.d("DlnaController", "Registered $location")
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
                    val responseBody = response.body?.string() ?: ""
//                    Log.d("DlnaController", "Interrogate response: $responseBody")
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