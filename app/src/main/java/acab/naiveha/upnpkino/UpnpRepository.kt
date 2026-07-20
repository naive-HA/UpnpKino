package acab.naiveha.upnpkino

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import android.util.Log

object UpnpRepository {
    class KinoService {
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()

        private val _isStarting = MutableStateFlow(false)
        val isStarting = _isStarting.asStateFlow()

        private val _sharedMediaCollection = MutableStateFlow<Map<String, Configuration.MediaNode>>(emptyMap())
        val sharedMediaCollection = _sharedMediaCollection.asStateFlow()

        fun setRunning(value: Boolean) { 
            Log.d("UpnpRepository", "KinoService: isRunning set to $value")
            _isRunning.value = value 
        }
        fun setStarting(value: Boolean) { 
            Log.d("UpnpRepository", "KinoService: isStarting set to $value")
            _isStarting.value = value 
        }
        fun setSharedMediaCollection(value: Map<String, Configuration.MediaNode>) { _sharedMediaCollection.value = value }

        fun stop() {
            _isRunning.value = false
            _isStarting.value = false
            _sharedMediaCollection.value = emptyMap()
        }
    }
    class Upnp {
        private val _repeatAliveNotification = MutableStateFlow(false)
        val repeatAliveNotification = _repeatAliveNotification.asStateFlow()

        fun setRepeatAliveNotification(value: Boolean) { _repeatAliveNotification.value = value }

        fun stop() {
            _repeatAliveNotification.value = false
        }
    }
    class Dlna {
        private val _searchForDevices = MutableStateFlow(false)
        val searchForDevices = _searchForDevices.asStateFlow()
        private val _devices = MutableStateFlow<Map<String, DlnaNodeModel>?>(null)
        val devices = _devices.asStateFlow()
        private val _selectedDeviceId = MutableStateFlow<String?>(null)
        val selectedDeviceId = _selectedDeviceId.asStateFlow()
        private val _selectedMediaFileId = MutableStateFlow<String?>(null)
        val selectedMediaFileId = _selectedMediaFileId.asStateFlow()
        private val _streamingFlag = MutableStateFlow<String?>(null)
        val streamingFlag = _streamingFlag.asStateFlow()
        private val _streamingFeedbackFlag = MutableStateFlow<String?>(null)
        val streamingFeedbackFlag = _streamingFeedbackFlag.asStateFlow()
        private val _seekBarDuration = MutableStateFlow("00:00:00")
        val seekBarDuration = _seekBarDuration.asStateFlow()
        private val _seekBarPosition = MutableStateFlow("00:00:00")
        val seekBarPosition = _seekBarPosition.asStateFlow()
        private val _seekBarTarget = MutableStateFlow<String?>(null)
        val seekBarTarget = _seekBarTarget.asStateFlow()
        private val _searchedForDevices = MutableStateFlow(false)
        val searchedForDevices = _searchedForDevices.asStateFlow()
        private val _isDlnaActivityVisible = MutableStateFlow(false)
        val isDlnaActivityVisible = _isDlnaActivityVisible.asStateFlow()
        private val _isLoadingState = MutableStateFlow(false)
        val isLoadingState = _isLoadingState.asStateFlow()
        private val _userIsSeeking = MutableStateFlow(false)
        val userIsSeeking = _userIsSeeking.asStateFlow()
        fun setDlnaActivityVisible(value: Boolean) {
            _isDlnaActivityVisible.value = value
        }
        fun setLoadingState(value: Boolean) {
            _isLoadingState.value = value
        }
        fun setUserIsSeeking(value: Boolean) {
            _userIsSeeking.value = value
        }
        fun setSearchForDevices(value: Boolean) {
            Log.d("UpnpRepository", "DLNA: searchForDevices set to $value")
            _searchForDevices.value = value
            if (value) {
                _selectedDeviceId.value = null
                _selectedMediaFileId.value = null
                _searchedForDevices.value = true
            }
        }
        fun setDevices(value: Map<String, DlnaNodeModel>?) {
            Log.d("UpnpRepository", "DLNA: devices updated (count=${value?.size ?: 0})")
            _devices.value = value
        }
        fun setSelectedDeviceId(value: String?) {
            Log.d("UpnpRepository", "DLNA: selectedDeviceId set to $value")
            if (_selectedDeviceId.value != value) {
                _selectedDeviceId.value = null
                _selectedDeviceId.value = value
            }
        }
        fun setSelectedMediaFileId(value: String?) {
            Log.d("UpnpRepository", "DLNA: selectedMediaFileId set to $value")
            _selectedMediaFileId.value = value
        }
        fun setStreamingFlag(value: String?) {
            Log.d("UpnpRepository", "DLNA: streamingFlag set to $value")
            _streamingFlag.value = value
            if (value == Constants.Dlna.Action.STOP || value == Constants.Dlna.Action.PAUSE || value == Constants.Dlna.Action.ERROR) {
                _isLoadingState.value = false
            }
        }
        fun setStreamingFeedbackFlag(value: String?) {
            if (_streamingFeedbackFlag.value != value) {
                Log.d("UpnpRepository", "DLNA: streamingFeedbackFlag set to $value")
            }
            _streamingFeedbackFlag.value = value
            if (value == Constants.Dlna.ActionFeedback.STOPPED || 
                value == Constants.Dlna.ActionFeedback.PAUSED_PLAYBACK ||
                value == Constants.Dlna.ActionFeedback.NO_MEDIA_PRESENT) {
                _isLoadingState.value = false
            }
        }
        fun setSeekBarDuration(value: String) {
            _seekBarDuration.value = value
        }
        fun setSeekBarPosition(value: String) {
            if (_seekBarPosition.value != value && value != "00:00:00") {
                _isLoadingState.value = false
            }
            _seekBarPosition.value = value
        }
        fun setSeekBarTarget(value: String?) {
            _seekBarTarget.value = value
        }
        fun stop() {
            _searchForDevices.value = false
            _searchedForDevices.value = false
            _devices.value = null
            _selectedDeviceId.value = null
            _selectedMediaFileId.value = null
            _streamingFlag.value = null
            _streamingFeedbackFlag.value = Constants.Dlna.ActionFeedback.STOPPED
            _seekBarDuration.value = "00:00:00"
            _seekBarPosition.value = "00:00:00"
            _seekBarTarget.value = null
            _isLoadingState.value = false
            _userIsSeeking.value = false
            _isDlnaActivityVisible.value = false
        }
    }
    class Chromecast {
        private val _searchForDevices = MutableStateFlow(false)
        val searchForDevices = _searchForDevices.asStateFlow()

        private val _devices = MutableStateFlow<Map<String, ChromecastNodeModel>?>(null)
        val devices = _devices.asStateFlow()

        private val _selectedDeviceId = MutableStateFlow<String?>(null)
        val selectedDeviceId = _selectedDeviceId.asStateFlow()

        private val _selectedMediaFileId = MutableStateFlow<String?>(null)
        val selectedMediaFileId = _selectedMediaFileId.asStateFlow()

        private val _streamingFlag = MutableStateFlow<String?>(null)
        val streamingFlag = _streamingFlag.asStateFlow()

        private val _streamingFeedbackFlag = MutableStateFlow<String?>(null)
        val streamingFeedbackFlag = _streamingFeedbackFlag.asStateFlow()

        private val _seekBarDuration = MutableStateFlow("00:00:00")
        val seekBarDuration = _seekBarDuration.asStateFlow()

        private val _seekBarPosition = MutableStateFlow("00:00:00")
        val seekBarPosition = _seekBarPosition.asStateFlow()

        private val _seekBarTarget = MutableStateFlow<String?>(null)
        val seekBarTarget = _seekBarTarget.asStateFlow()

        private val _searchedForDevices = MutableStateFlow(false)
        val searchedForDevices = _searchedForDevices.asStateFlow()

        private val _isChromecastActivityVisible = MutableStateFlow(false)
        val isChromecastActivityVisible = _isChromecastActivityVisible.asStateFlow()

        private val _isLoadingState = MutableStateFlow(false)
        val isLoadingState = _isLoadingState.asStateFlow()

        private val _userIsSeeking = MutableStateFlow(false)
        val userIsSeeking = _userIsSeeking.asStateFlow()

        fun setChromecastActivityVisible(value: Boolean) {
            _isChromecastActivityVisible.value = value
        }

        fun setLoadingState(value: Boolean) {
            _isLoadingState.value = value
        }

        fun setSearchForDevices(value: Boolean) {
            Log.d("UpnpRepository", "Chromecast: searchForDevices set to $value")
            _searchForDevices.value = value
            if (value) {
                _selectedDeviceId.value = null
                _selectedMediaFileId.value = null
                _searchedForDevices.value = true
            }
        }

        fun setDevices(value: Map<String, ChromecastNodeModel>?) {
            Log.d("UpnpRepository", "Chromecast: devices updated (count=${value?.size ?: 0})")
            _devices.value = value
        }

        fun setSelectedDeviceId(value: String?) {
            Log.d("UpnpRepository", "Chromecast: selectedDeviceId set to $value")
            _selectedDeviceId.value = value
        }

        fun setSelectedMediaFileId(value: String?) {
            Log.d("UpnpRepository", "Chromecast: selectedMediaFileId set to $value")
            _selectedMediaFileId.value = value
        }

        fun setStreamingFlag(value: String?) {
            Log.d("UpnpRepository", "Chromecast: streamingFlag set to $value")
            _streamingFlag.value = value
            if (value == Constants.Chromecast.Action.STOP || value == Constants.Chromecast.Action.PAUSE || value == Constants.Chromecast.Action.ERROR) {
                _isLoadingState.value = false
            }
        }

        fun setStreamingFeedbackFlag(value: String?) {
            if (_streamingFeedbackFlag.value != value) {
                Log.d("UpnpRepository", "Chromecast: streamingFeedbackFlag set to $value")
            }
            _streamingFeedbackFlag.value = value
            if (value == Constants.Chromecast.ActionFeedback.STOPPED || 
                value == Constants.Chromecast.ActionFeedback.PAUSED_PLAYBACK ||
                value == Constants.Chromecast.ActionFeedback.NO_MEDIA_PRESENT) {
                _isLoadingState.value = false
            }
        }

        fun setSeekBarDuration(value: String) {
            _seekBarDuration.value = value
        }

        fun setSeekBarPosition(value: String) {
            if (_seekBarPosition.value != value && value != "00:00:00") {
                _isLoadingState.value = false
            }
            _seekBarPosition.value = value
        }

        fun setSeekBarTarget(value: String?) {
            _seekBarTarget.value = value
        }

        fun setUserIsSeeking(value: Boolean) {
            _userIsSeeking.value = value
        }

        fun stop() {
            _searchForDevices.value = false
            _searchedForDevices.value = false
            _devices.value = null
            _selectedDeviceId.value = null
            _selectedMediaFileId.value = null
            _streamingFlag.value = null
            _streamingFeedbackFlag.value = Constants.Chromecast.ActionFeedback.STOPPED
            _seekBarDuration.value = "00:00:00"
            _seekBarPosition.value = "00:00:00"
            _seekBarTarget.value = null
            _isLoadingState.value = false
            _isChromecastActivityVisible.value = false
            _userIsSeeking.value = false
        }
    }

    class Selector {
        private val sources = mutableMapOf<String, Any>()

        fun registerSource(source: Any): String {
            val id = java.util.UUID.randomUUID().toString()
            sources[id] = source
            return id
        }

        fun getSource(id: String): Any? = sources[id]
    }

    val kinoService = KinoService()
    val upnp = Upnp()
    val dlna = Dlna()
    val chromecast = Chromecast()
    val selector = Selector()

    private val _events = MutableSharedFlow<String>()
    val events = _events.asSharedFlow()

    suspend fun postEvent(event: String) {
        _events.emit(event)
    }

    fun stop() {
        chromecast.stop()
        dlna.stop()
        upnp.stop()
        kinoService.stop()
    }
}
