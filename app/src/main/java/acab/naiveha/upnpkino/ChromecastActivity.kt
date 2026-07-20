package acab.naiveha.upnpkino

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import acab.naiveha.upnpkino.databinding.ActivityChromecastBinding
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import acab.naiveha.upnpkino.Constants.Chromecast.ActionFeedback
import acab.naiveha.upnpkino.Constants.Chromecast.Action
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

class ChromecastActivity : AppCompatActivity() {

    private val repo = UpnpRepository.chromecast

    private lateinit var binding: ActivityChromecastBinding
    private lateinit var startingAnimation: ValueAnimator
    private var isWaitingForSeek = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityChromecastBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        Constants.setDisplaySizing(windowManager, resources, binding.contentGroup.layoutParams)
        val darkGrey = ContextCompat.getColor(this, R.color.darker_grey)
        val white = ContextCompat.getColor(this, R.color.label_enabled)
        binding.imageView.setColorFilter(darkGrey)
        binding.imageView.setOnDoubleTapListener {
            val isStopped = when (repo.streamingFeedbackFlag.value) {
                ActionFeedback.STOPPED,
                ActionFeedback.NO_MEDIA_PRESENT -> true
                else -> false
            } || repo.streamingFlag.value == Action.ERROR
            if (UpnpRepository.kinoService.isRunning.value && !startingAnimation.isRunning && isStopped) {
                repo.setSearchForDevices(true)
                binding.device.text = ""
            }
        }
        startingAnimation = ValueAnimator.ofObject(ArgbEvaluator(), darkGrey, white).apply {
            duration = 333 // Cycle 1.5 times per second
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                binding.imageView.setColorFilter(animator.animatedValue as Int)
            }
        }
        binding.seekBar.isEnabled = false
        binding.seekBar.progress = 0
        binding.seekBar.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.textPosition.text = Constants.secondsToDuration(progress.toLong())
                }
                updateLoadingSpinnerPosition()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {
                repo.setStreamingFlag(null) //enable triggering SEEK after SEEK
                repo.setUserIsSeeking(true) //stop updating the seekBar position with data from device
            }
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {
                if (repo.streamingFeedbackFlag.value == ActionFeedback.PLAYING) {
                    val progress = seekBar?.progress ?: 0
                    val targetTime = Constants.secondsToDuration(progress.toLong())
                    repo.setSeekBarTarget(targetTime)
                    repo.setStreamingFlag(Action.SEEK)
                    repo.setLoadingState(true) //show the loading spinner
                    isWaitingForSeek = true //prevent the user from SEEKing again, to allow the remote device catching up
                    lifecycleScope.launch {
                        delay(2500.milliseconds)
                        isWaitingForSeek = false
                        repo.setUserIsSeeking(false)
                        repo.setLoadingState(false) //hide the loading spinner
                    }
                } else {
                    repo.setUserIsSeeking(false)
                    val currentPosition = repo.seekBarPosition.value
                    binding.textPosition.text = currentPosition
                    seekBar?.progress = Constants.durationToSeconds(currentPosition).toInt()
                }
            }
        })
        binding.buttonStop.isEnabled = false
        binding.buttonStop.setOnClickListener {
            when (repo.streamingFeedbackFlag.value) {
                ActionFeedback.PLAYING,
                ActionFeedback.PAUSED_PLAYBACK,
                ActionFeedback.TRANSITIONING -> repo.setStreamingFlag(Action.STOP)
            }
        }
        binding.buttonPlay.isEnabled = false
        binding.buttonPlay.setOnClickListener {
            when (repo.streamingFeedbackFlag.value) {
                ActionFeedback.PLAYING -> repo.setStreamingFlag(Action.PAUSE)
                else -> {
                    repo.setLoadingState(true) //show the loading spinner
                    repo.setStreamingFlag(Action.PLAY)
                }
            }
        }
        binding.infoButton.setOnClickListener {
            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.chromecast_info_title)
                .setMessage(R.string.chromecast_info_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
        binding.textView.text = getString(R.string.version_info, BuildConfig.VERSION_NAME)
        binding.textView.setOnClickListener {
            openUrl()
        }
        binding.textView2.text = getString(R.string.tips_message, getString(R.string.btc_address))
        binding.textView2.setOnClickListener {
            copyBtcAddressToClipboard()
        }
        binding.textView2.setOnLongClickListener {
            copyBtcAddressToClipboard()
            true
        }
        binding.menuIcon.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }
        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    val intent = Intent(this, MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    startActivity(intent)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_dlna -> {
                    val intent = Intent(this, DlnaActivity::class.java)
                    startActivity(intent)
//                    binding.drawerLayout.closeDrawer(GravityCompat.START)
//                    true
//                }
//                R.id.nav_chromecast -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_licenses -> {
                    val intent = Intent(this, LicensesActivity::class.java)
                    startActivity(intent)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                else -> false
            }
        }
        binding.mediaFile.setOnClickListener {
            val devices = repo.devices.value
            val selectedDeviceId = repo.selectedDeviceId.value
            val device = selectedDeviceId?.let { devices?.get(it) }
            if (device != null && device.mediaCollection.isNotEmpty()) {
                if (supportFragmentManager.findFragmentByTag("Selector") == null) {
                    val selectedFileId = repo.selectedMediaFileId.value
                    val mediaSource = SelectorMap(device.mediaCollection) { id -> repo.setSelectedMediaFileId(id) }
                    SelectorFragment.newInstance("Select media file", mediaSource, selectedFileId)
                        .show(supportFragmentManager, "Selector")
                }
            } else if (UpnpRepository.kinoService.sharedMediaCollection.value.isEmpty()) {
                Toast.makeText(this, "Start UPnP Kino first", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Select a Chromecast device first", Toast.LENGTH_SHORT).show()
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpnpRepository.kinoService.isRunning.collect { isRunning ->
                    if (!isRunning) {
                        startingAnimation.cancel()
                        binding.imageView.setColorFilter(darkGrey)
                        binding.device.isEnabled = false
                        binding.device.text = ""
                        binding.device.setTextColor(darkGrey)
                        binding.mediaFile.isEnabled = false
                        binding.mediaFile.text = getString(R.string.status_not_running)
                        binding.mediaFile.setTextColor(darkGrey)
                        binding.buttonPlay.isEnabled = false
                        binding.buttonStop.isEnabled = false
                        binding.seekBar.isEnabled = false
                    } else {
                        if (!repo.searchedForDevices.value) {
                            binding.imageView.setColorFilter(darkGrey)
                            binding.device.isEnabled = false
                            binding.device.text = ""
                            binding.device.setTextColor(darkGrey)
                            binding.mediaFile.isEnabled = false
                            binding.mediaFile.text = getString(R.string.status_not_discovered)
                            binding.mediaFile.setTextColor(white)
                            binding.buttonPlay.isEnabled = false
                            binding.buttonStop.isEnabled = false
                            binding.seekBar.isEnabled = false
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpnpRepository.kinoService.isStarting.collect { isStarting ->
                    if (isStarting) {
                        binding.imageView.setColorFilter(darkGrey)
                        binding.device.isEnabled = false
                        binding.device.text = ""
                        binding.device.setTextColor(darkGrey)
                        binding.mediaFile.isEnabled = false
                        binding.mediaFile.text = getString(R.string.status_starting)
                        binding.mediaFile.setTextColor(white)
                        binding.buttonPlay.isEnabled = false
                        binding.buttonStop.isEnabled = false
                        binding.seekBar.isEnabled = false
                    }
                }
            }
        }
        var wasSearching = false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.searchForDevices.map { it to (repo.devices.value?.isNotEmpty() == true) }
                    .distinctUntilChanged().collect { (isSearching, hasDevices) ->
                        if(!UpnpRepository.kinoService.isRunning.value) return@collect
                        if (isSearching) {
                            if (!startingAnimation.isRunning) {
                                startingAnimation.start()
                                Constants.vibrate(this@ChromecastActivity, true)
                            }
                            binding.mediaFile.isEnabled = false
                            binding.mediaFile.text = getString(R.string.status_searching)
                            binding.mediaFile.setTextColor(white)
                        } else {
                            if (startingAnimation.isRunning) {
                                startingAnimation.cancel()
                            }
                            if (wasSearching) {
                                if (hasDevices) {
                                    binding.imageView.setColorFilter(darkGrey)
                                    binding.device.isEnabled = true
                                    binding.device.text = ""
                                    binding.device.setTextColor(darkGrey)
                                    binding.mediaFile.isEnabled = false
                                    binding.mediaFile.text = getString(R.string.status_no_device_selected)
                                    binding.mediaFile.setTextColor(white)
                                    binding.buttonPlay.isEnabled = false
                                    binding.buttonStop.isEnabled = false
                                    binding.seekBar.isEnabled = false
                                    if (supportFragmentManager.findFragmentByTag("Selector") == null) {
                                        val selectedDeviceId = repo.selectedDeviceId.value
                                        val deviceSource = SelectorMap(repo.devices.value ?: emptyMap()) { id -> repo.setSelectedDeviceId(id) }
                                        SelectorFragment.newInstance("Select a Chromecast device", deviceSource, selectedDeviceId)
                                            .show(supportFragmentManager, "Selector")
                                    }
                                } else {
                                    binding.imageView.setColorFilter(darkGrey)
                                    binding.device.isEnabled = false
                                    binding.device.text = ""
                                    binding.device.setTextColor(darkGrey)
                                    binding.mediaFile.isEnabled = false
                                    binding.mediaFile.text = getString(R.string.status_no_devices_found)
                                    binding.mediaFile.setTextColor(white)
                                    binding.buttonPlay.isEnabled = false
                                    binding.buttonStop.isEnabled = false
                                    binding.seekBar.isEnabled = false
                                }
                            }
                        }
                        wasSearching = isSearching
                    }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.selectedDeviceId.collect { selectedDeviceId ->
                    if(!UpnpRepository.kinoService.isRunning.value) return@collect
                    if(repo.searchForDevices.value) return@collect
                    if (selectedDeviceId != null) {
                        val selectedDevice = repo.devices.value?.get(selectedDeviceId) ?: return@collect
                        binding.imageView.setColorFilter(white)
                        binding.device.isEnabled = true
                        binding.device.text = getString(R.string.device_info_format, selectedDevice.friendlyName, selectedDevice.secondaryLabel)
                        binding.device.setTextColor(white)
                        binding.mediaFile.isEnabled = true
                        binding.mediaFile.text = getString(R.string.status_tap_to_select)
                        binding.mediaFile.setTextColor(white)
                        binding.buttonPlay.isEnabled = false
                        binding.buttonStop.isEnabled = false
                        binding.seekBar.isEnabled = false
                    } else {
                        binding.imageView.setColorFilter(darkGrey)
                        binding.device.isEnabled = false
                        binding.device.text = ""
                        binding.device.setTextColor(darkGrey)
                        binding.mediaFile.isEnabled = false
                        if(wasSearching) {
                            binding.mediaFile.text = getString(R.string.status_no_device_selected)
                        } else {
                            binding.mediaFile.text = getString(R.string.status_not_discovered)
                        }
                        binding.mediaFile.setTextColor(white)
                        binding.buttonPlay.isEnabled = false
                        binding.buttonStop.isEnabled = false
                        binding.seekBar.isEnabled = false
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.selectedMediaFileId.collect { selectedMediaFileId ->
                    if(!UpnpRepository.kinoService.isRunning.value) return@collect
                    if(repo.selectedDeviceId.value == null) return@collect
                    if (selectedMediaFileId != null) {
                        val selectedDeviceId = repo.selectedDeviceId.value
                        val selectedDevice = repo.devices.value?.get(selectedDeviceId) ?: return@collect
                        val mediaFile = selectedDevice.mediaCollection[selectedMediaFileId]
                        binding.mediaFile.isEnabled = true
                        binding.mediaFile.text = mediaFile?.name
                        binding.mediaFile.setTextColor(white)
                        binding.buttonPlay.isEnabled = true
                        binding.buttonStop.isEnabled = true
                        binding.seekBar.isEnabled = true
                    } else {
                        binding.mediaFile.isEnabled = false
                        binding.mediaFile.text = getString(R.string.status_tap_to_select)
                        binding.mediaFile.setTextColor(white)
                        binding.buttonPlay.isEnabled = false
                        binding.buttonStop.isEnabled = false
                        binding.seekBar.isEnabled = false
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.streamingFlag.collect { streamingFlag ->
                    if(!UpnpRepository.kinoService.isRunning.value) return@collect
                    if(repo.selectedDeviceId.value == null) return@collect
                    if(repo.selectedMediaFileId.value == null) return@collect
                    when(streamingFlag){
                        Action.PLAY -> {
                            binding.seekBar.isEnabled = true
                        }
                        Action.STOP -> {
                            binding.seekBar.progress = 0
                            binding.textDuration.text = getString(R.string.default_time)
                            binding.textPosition.text = getString(R.string.default_time)
                            binding.seekBar.isEnabled = false
                        }
                        Action.ERROR -> {
                            Constants.vibrate(this@ChromecastActivity, true)
                            binding.imageView.setColorFilter(darkGrey)
                            binding.device.isEnabled = false
                            binding.device.text = ""
                            binding.device.setTextColor(darkGrey)
                            binding.mediaFile.isEnabled = false
                            binding.mediaFile.text = getString(R.string.status_error)
                            binding.mediaFile.setTextColor(darkGrey)
                            binding.buttonPlay.isEnabled = false
                            binding.buttonStop.isEnabled = false
                            binding.seekBar.isEnabled = false
                            binding.seekBar.progress = 0
                            binding.textDuration.text = getString(R.string.default_time)
                            binding.textPosition.text = getString(R.string.default_time)
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.streamingFeedbackFlag.collect { streamingFeedbackFlag ->
                    if(!UpnpRepository.kinoService.isRunning.value) return@collect
                    if(repo.selectedDeviceId.value == null) return@collect
                    if(repo.selectedMediaFileId.value == null) return@collect
                    when (streamingFeedbackFlag) {
                        ActionFeedback.PLAYING -> {
                            binding.buttonPlay.setImageResource(R.drawable.pause_circle)
                            if (repo.seekBarPosition.value == "00:00:00") {
                                repo.setLoadingState(true)
                            }
                        }
                        ActionFeedback.TRANSITIONING -> {
                            binding.buttonPlay.setImageResource(R.drawable.pause_circle)
                            repo.setLoadingState(true)
                        }
                        else -> binding.buttonPlay.setImageResource(R.drawable.play_circle)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.isLoadingState.collect { isLoadingState ->
                    if(!UpnpRepository.kinoService.isRunning.value) return@collect
                    if(repo.selectedDeviceId.value == null) return@collect
                    if(repo.selectedMediaFileId.value == null) return@collect
                    if (isLoadingState) {
                        if (binding.loadingSpinner.visibility != View.VISIBLE) {
                            binding.loadingSpinner.visibility = View.VISIBLE
                            (binding.loadingSpinner.drawable as? Animatable)?.start()
                        }
                        updateLoadingSpinnerPosition()
                    } else {
                        if (binding.loadingSpinner.visibility != View.GONE) {
                            (binding.loadingSpinner.drawable as? Animatable)?.stop()
                            binding.loadingSpinner.visibility = View.GONE
                        }
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.seekBarDuration.collect { seekBarDuration ->
                    when (repo.streamingFlag.value){
                        Action.STOP,
                        Action.PAUSE,
                        Action.ERROR -> return@collect
                    }
                    val totalSeconds = if (seekBarDuration != "00:00:00") Constants.durationToSeconds(seekBarDuration) else 0L
                    binding.textDuration.text = seekBarDuration
                    if (totalSeconds > 0) {
                        binding.seekBar.isEnabled = true
                        binding.seekBar.max = totalSeconds.toInt()
                    } else {
                        binding.seekBar.isEnabled = false
                        binding.seekBar.progress = 0
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.seekBarPosition.collect { seekBarPosition ->
                    when (repo.streamingFlag.value){
                        Action.STOP,
                        Action.PAUSE,
                        Action.ERROR -> return@collect
                    }
                    val totalSeconds = if (repo.seekBarDuration.value != "00:00:00") Constants.durationToSeconds(repo.seekBarDuration.value) else 0L
                    val currentSeconds = if (seekBarPosition != "00:00:00") Constants.durationToSeconds(seekBarPosition) else 0L
                    if (totalSeconds > 0) {
                        if (!repo.userIsSeeking.value && !isWaitingForSeek) {
                            binding.textPosition.text = seekBarPosition
                            binding.seekBar.progress = currentSeconds.toInt()
                        }
                    } else {
                        binding.seekBar.isEnabled = false
                        binding.seekBar.progress = 0
                        if (!repo.userIsSeeking.value && !isWaitingForSeek) {
                            binding.textPosition.text = seekBarPosition
                        }
                    }
                }
            }
        }
    }
    private fun updateLoadingSpinnerPosition() {
        if (binding.loadingSpinner.visibility != View.VISIBLE) return
        binding.seekBar.post {
            if (binding.seekBar.max <= 0) return@post
            val availableWidth = binding.seekBar.width - binding.seekBar.paddingLeft - binding.seekBar.paddingRight
            val progressRatio = binding.seekBar.progress.toFloat() / binding.seekBar.max
            val thumbCenterX = binding.seekBar.paddingLeft + (progressRatio * availableWidth)
            binding.loadingSpinner.translationX = thumbCenterX - (binding.seekBar.width / 2f)
        }
    }
    private fun openUrl() {
        val url = "https://github.com/naive-HA/UpnpKino"
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = url.toUri()
        startActivity(intent)
    }
    private fun copyBtcAddressToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val btcAddress = getString(R.string.btc_address)
        val clip = ClipData.newPlainText("BTC Address", btcAddress)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(
            this,
            getString(R.string.copied_to_clipboard, btcAddress),
            Toast.LENGTH_SHORT
        ).show()
    }
    override fun onResume() {
        super.onResume()
        repo.setChromecastActivityVisible(true)
    }
    override fun onPause() {
        super.onPause()
        repo.setChromecastActivityVisible(false)
    }
    override fun onDestroy() {
        super.onDestroy()
    }
}
