package acab.naiveha.upnpkino

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.WindowMetrics
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import acab.naiveha.upnpkino.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.net.NetworkInterface
import kotlin.math.min
import kotlin.math.max
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: Preferences
    private lateinit var startingAnimation: ValueAnimator
    private val upnpService = UpnpService()
    private val requestMultiplePermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.any { !it }) {
            Toast.makeText(this, "Permissions are required for the app to function properly.", Toast.LENGTH_LONG).show()
        }
    }
    private val selectLocalMovieFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(it, takeFlags)
            preferences.saveLocalMovieFolderUri(it)
        }
    }
    private val selectLocalMusicFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(it, takeFlags)
            preferences.saveLocalMusicFolderUri(it)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        val displayMetrics = DisplayMetrics()
        val windowMetrics: WindowMetrics = windowManager.currentWindowMetrics
        val bounds = windowMetrics.bounds
        displayMetrics.widthPixels = bounds.width()
        displayMetrics.heightPixels = bounds.height()
        displayMetrics.density = resources.displayMetrics.density
        val displayDensity = max(displayMetrics.density, 1f)
        val screenWidthDp = displayMetrics.widthPixels / displayDensity
        val screenHeightDp = 0.95f * displayMetrics.heightPixels / displayDensity
        val params = binding.contentGroup.layoutParams
        params.width = (min(screenWidthDp, 1150f) * displayDensity).toInt()
        params.height = (min(screenHeightDp, 2650f) * displayDensity).toInt()
        binding.contentGroup.layoutParams = params
        //initialize preferences
        //Preferences is static
        preferences = Preferences(this)
        val darkGrey = ContextCompat.getColor(this, R.color.darker_grey)
        val white = ContextCompat.getColor(this, android.R.color.white)
        binding.imageView.setColorFilter(darkGrey)
        startingAnimation = ValueAnimator.ofObject(ArgbEvaluator(), darkGrey, white).apply {
            duration = 333 // Cycle 1.5 times per second
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                binding.imageView.setColorFilter(animator.animatedValue as Int)
            }
        }
        //request permission to show notifications (needed for foreground service)
        //and to read the storage (needed to stream video files)
        val requiredPermissions = mutableListOf<String>()
        requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        requiredPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
        requiredPermissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        requiredPermissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missingPermissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(missingPermissions)
        }
        //main button starting the foreground service
        binding.button.setOnClickListener { toggleService() }

        //button to select folder local movie folder
        binding.button2.setOnClickListener {
            preferences.clearLocalMovieFolderUri()
            selectLocalMovieFolderLauncher.launch(null)
        }
        //button to select folder local music folder
        binding.button3.setOnClickListener {
            preferences.clearLocalMusicFolderUri()
            selectLocalMusicFolderLauncher.launch(null)
        }
        binding.textView.text = "UPnP Kino by naiveHA ${BuildConfig.VERSION_NAME}\nhttps://github.com/naive-HA/UpnpKino"
        binding.textView.setOnClickListener {
            openUrl()
        }
        binding.textView2.setOnClickListener {
            copyBtcAddressToClipboard()
        }
        binding.textView2.setOnLongClickListener {
            copyBtcAddressToClipboard()
            true
        }
        binding.imageView.setOnDoubleTapListener {
            if (UpnpService.isRunning.value) {
                lifecycleScope.launch {
                    upnpService.postEvent("acab.naiveha.upnpkino.RepeatAliveNotification")
                }
                vibrate(true)
            }
        }
        lifecycleScope.launch {
            UpnpService.isRunning.collect { isRunning ->
                updateButtonState(isRunning)
            }
        }
        lifecycleScope.launch {
            UpnpService.events.collect { event ->
                when (event) {
                    "acab.naiveha.upnpkino.AnimateImageView" -> {
                        startingAnimation.start()
                    }
                    "acab.naiveha.upnpkino.StopAnimateImageView" -> {
                        startingAnimation.cancel()
                        updateButtonState(UpnpService.isRunning.value)
                    }
                }
            }
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
        val clip = ClipData.newPlainText("BTC Address", "1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Copied to clipboard: 1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh", Toast.LENGTH_SHORT).show()
    }
    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            UpnpService.isRunning.collect { isRunning ->
                updateButtonState(isRunning)
            }
        }
    }
    private fun toggleService() {
        binding.button.isEnabled = false
        disableButtons()
        if (UpnpService.isRunning.value) {
            binding.status.text = "Shutting down... Please wait!"
            //if service is running, stop it
            stopService(Intent(this, UpnpService::class.java))
            return
        }
        // else
        binding.status.text = "Starting up... Please wait!"
        //if service is not running, try and start it
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork
        val networkCapabilities = connectivityManager.getNetworkCapabilities(network)
        try {
            //if Wi-Fi is not connected, it does not make sense to start
            if (networkCapabilities == null || !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                throw Exception("Error: WiFi not connected")
            }
            val linkProperties = connectivityManager.getLinkProperties(network)
            val networkInterface = linkProperties?.let { NetworkInterface.getByName(it.interfaceName) }
            if (networkInterface == null || !networkInterface.supportsMulticast()) {
                throw Exception("Error: Network does not support multicast")
            }
            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) {
                throw Exception("Error: Network traffic may be restricted")
            }
            //if permission to show notifications was not granted,
            //do not start foreground service
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                throw Exception("Error: Notification permission not granted")
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                throw Exception("Error: Storage permission not granted")
            }
            if (preferences.getLocalMovieFolderUri() == null && preferences.getLocalMusicFolderUri() == null) {
                throw Exception("Error: No folder selected")
            }
            if (preferences.getLocalMovieFolderUri() != null && DocumentFile.fromTreeUri(this, preferences.getLocalMovieFolderUri()!!)?.exists() != true) {
                throw Exception("Error: Local movies folder does not exist")
            }
            if (preferences.getLocalMusicFolderUri() != null && DocumentFile.fromTreeUri(this, preferences.getLocalMusicFolderUri()!!)?.exists() != true) {
                throw Exception("Error: Local music folder does not exist")
            }

        } catch (e: Exception) {
            binding.status.text = ""
            binding.button.isEnabled = true
            enableButtons()
            vibrate()
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }
        //if all checks out, start the foreground service
        //to do: pass the configuration object to service
        startingAnimation.start()
        startService(Intent(this, UpnpService::class.java))
    }
    private fun disableButtons(){
        binding.button2.isEnabled = false
        binding.button2Label.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
        binding.button3.isEnabled = false
        binding.button3Label.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
    }
    private fun enableButtons(){
        binding.button2.isEnabled = true
        binding.button2Label.setTextColor(ContextCompat.getColor(this, android.R.color.white))
        binding.button3.isEnabled = true
        binding.button3Label.setTextColor(ContextCompat.getColor(this, android.R.color.white))
    }
    private fun updateButtonState(isRunning: Boolean) {
        if (isRunning) {
            startingAnimation.cancel()
            val typedValue = TypedValue()
            theme.resolveAttribute(R.attr.serviceRunningIconColor, typedValue, true)
            binding.imageView.setColorFilter(typedValue.data)
            binding.button.text = "Stop UPnP Kino"
            binding.status.text = "Success! All systems running"
            binding.button.isEnabled = true
            disableButtons()
            return
        }
        // else
        startingAnimation.cancel()
        binding.imageView.setColorFilter(ContextCompat.getColor(this, R.color.darker_grey))
        binding.button.text = "Start UPnP Kino"
        binding.status.text = ""
        binding.button.isEnabled = true
        enableButtons()
    }
    private fun vibrate(short: Boolean = false) {
        val vibrationEffect = if (short) {
            VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1)
        }

        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val vibrator = vibratorManager.defaultVibrator
        vibrator.vibrate(vibrationEffect)
    }
}