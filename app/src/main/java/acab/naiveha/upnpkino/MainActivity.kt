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

    private val selectFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            binding.status.text = "Press 'Start UPnP Kino' to begin"
            binding.button2.clearColorFilter()
            val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
            contentResolver.takePersistableUriPermission(it, takeFlags)
            preferences.saveFolderUri(it)
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

        //button to select folder
        binding.button2.setOnClickListener {
            selectFolderLauncher.launch(null)
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

        binding.imageView.setOnClickListener {
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
    }

    private fun openUrl() {
        val url = "https://github.com/naive-HA/UpnpKino"
        val intent = Intent(Intent.ACTION_VIEW)
        intent.data = Uri.parse(url)
        startActivity(intent)
    }

    private fun copyBtcAddressToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("BTC Address", "1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh")
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "BTC address: 1HwgShr1TniuBxNQwy2xAhpQaNuZhtw6sh copied to clipboard", Toast.LENGTH_SHORT).show()
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
        if (UpnpService.isRunning.value) {
            binding.button.isEnabled = false
            binding.button2.isEnabled = false
            binding.status.text = "Shutting down... Please wait!"
            //if service is running, stop it
            stopService(Intent(this, UpnpService::class.java))
        } else {
            binding.button.isEnabled = false
            binding.button2.isEnabled = false
            binding.status.text = "Starting up... Please wait!"
            //if service is not running, try and start it
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(network)

            //if wifi is not connected, it does not make sense to start
            if (networkCapabilities == null || !networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                binding.button.isEnabled = true
                binding.button2.isEnabled = true
                binding.status.text = "Error: WiFi not connected"
                vibrate()
                Toast.makeText(this, "A Wi-Fi connection is required to start the service.", Toast.LENGTH_LONG).show()
                return
            }

            val linkProperties = connectivityManager.getLinkProperties(network)
            val networkInterface = linkProperties?.let { NetworkInterface.getByName(it.interfaceName) }
            if (networkInterface == null || !networkInterface.supportsMulticast()) {
                binding.button.isEnabled = true
                binding.button2.isEnabled = true
                binding.status.text = "Error: Network does not support multicast"
                vibrate()
                Toast.makeText(this, "Your network does not support multicast.", Toast.LENGTH_LONG).show()
                return
            }
            if (!networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)) {
                binding.button.isEnabled = true
                binding.button2.isEnabled = true
                binding.status.text = "Error: Network traffic may be restricted"
                vibrate()
                Toast.makeText(this, "Your network is restricted and may block local traffic.", Toast.LENGTH_LONG).show()
                return
            }

            //if permission to show notifications was not granted,
            //do not start foreground service
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                binding.button.isEnabled = true
                binding.button2.isEnabled = true
                binding.status.text = "Error: Notification permission not granted"
                vibrate()
                Toast.makeText(this, "Notification permission is required to start the service.", Toast.LENGTH_LONG).show()
                return
            }
            
            val storagePermission =
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED

            if (!storagePermission) {
                binding.button.isEnabled = true
                binding.button2.isEnabled = true
                binding.status.text = "Error: Storage permission not granted"
                vibrate()
                Toast.makeText(this, "Storage permission is required to start the service.", Toast.LENGTH_LONG).show()
                return
            }
            
            if (preferences.getFolderUri() == null) {
                binding.button.isEnabled = true
                binding.button2.isEnabled = true
                binding.status.text = "Error: No folder selected"
                vibrate()
                Toast.makeText(this, "A folder needs to be selected to be shared.", Toast.LENGTH_LONG).show()
                binding.button2.setColorFilter(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                return
            }
            //if all checks out, start the foreground service
            //to do: pass the configuration object to service
            startingAnimation.start()
            startService(Intent(this, UpnpService::class.java))
        }
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
            binding.button2.isEnabled = false
        } else {
            startingAnimation.cancel()
            binding.imageView.setColorFilter(ContextCompat.getColor(this, R.color.darker_grey))
            binding.button.text = "Start UPnP Kino"
            binding.status.text = "Press 'Start UPnP Kino' to begin"
            binding.button.isEnabled = true
            binding.button2.isEnabled = true
        }
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
