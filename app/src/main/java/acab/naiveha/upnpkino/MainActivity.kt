package acab.naiveha.upnpkino

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import acab.naiveha.upnpkino.databinding.ActivityMainBinding
import android.util.Log
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: Preferences
    private lateinit var startingAnimation: ValueAnimator
    private val requestMultiplePermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        Log.d("upnpkino", "Permission results: $permissions")
        val deniedPermissions = permissions.filter { !it.value }.keys
        val isPartialAccessGranted = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val fromCallback = permissions[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
            val fromCheck = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
            Log.d("upnpkino", "Partial access check: fromCallback=$fromCallback, fromCheck=$fromCheck")
            fromCallback || fromCheck
        } else {
            false
        }
        val realDenials = deniedPermissions.filter {
            val isBroadMedia = it == Manifest.permission.READ_MEDIA_IMAGES || it == Manifest.permission.READ_MEDIA_VIDEO
            if (isPartialAccessGranted && isBroadMedia) {
                Log.d("upnpkino", "Filtering out broad media denial because partial access is granted: $it")
                false
            } else {
                true
            }
        }
        if (realDenials.isNotEmpty()) {
            Toast.makeText(this, "Permissions are required for the app to function properly: $realDenials", Toast.LENGTH_LONG).show()
            Log.d("upnpkino", "Real denials reported to user: $realDenials")
        } else {
            Log.d("upnpkino", "No real denials to report.")
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
        Constants.setDisplaySizing(windowManager, resources, binding.contentGroup.layoutParams)
        preferences = Preferences(this)
        val darkGrey = ContextCompat.getColor(this, R.color.darker_grey)
        val white = ContextCompat.getColor(this, R.color.label_enabled)
        binding.imageView.setColorFilter(darkGrey)
        startingAnimation = ValueAnimator.ofObject(ArgbEvaluator(), darkGrey, white).apply {
            duration = 333 // Cycle 1.5 times per second
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { animator ->
                binding.imageView.setColorFilter(animator.animatedValue as Int)
            }
        }
        val requiredPermissions = mutableListOf<String>()
        requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            requiredPermissions.add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            requiredPermissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            requiredPermissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                requiredPermissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
        } else {
            requiredPermissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()
        if (missingPermissions.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(missingPermissions)
        }
        binding.button.setOnClickListener { toggleService() }
        binding.button2.setOnClickListener {
            preferences.clearLocalMovieFolderUri()
            selectLocalMovieFolderLauncher.launch(null)
        }
        binding.button2.setOnLongClickListener {
            preferences.clearLocalMovieFolderUri()
            Constants.vibrate(this)
            Toast.makeText(this, "Video library cleared", Toast.LENGTH_SHORT).show()
            true
        }
        binding.button3.setOnClickListener {
            preferences.clearLocalMusicFolderUri()
            selectLocalMusicFolderLauncher.launch(null)
        }
        binding.button3.setOnLongClickListener {
            preferences.clearLocalMusicFolderUri()
            Constants.vibrate(this)
            Toast.makeText(this, "Music library cleared", Toast.LENGTH_SHORT).show()
            true
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
        binding.imageView.setOnDoubleTapListener {
            if (UpnpRepository.kinoService.isRunning.value && !startingAnimation.isRunning) {
                UpnpRepository.upnp.setRepeatAliveNotification(true)
            }
        }
        lifecycleScope.launch {
            UpnpRepository.upnp.repeatAliveNotification.collect { repeating ->
                if (repeating) {
                    Toast.makeText(this@MainActivity, "Re-announcing the server to the network", Toast.LENGTH_SHORT).show()
                }
            }
        }
        lifecycleScope.launch {
            combine(UpnpRepository.kinoService.isStarting, UpnpRepository.kinoService.isRunning, UpnpRepository.upnp.repeatAliveNotification) { isStarting, isRunning, repeatAliveNotification ->
                Triple(isStarting, isRunning, repeatAliveNotification)
            }.collect { (isStarting, isRunning, repeatAliveNotification) ->
                if (isStarting && !isRunning) {
                    binding.button.isEnabled = false
                    disableButtons()
                    startingAnimation.start()
                    return@collect
                }
                if (isRunning) {
                    if (repeatAliveNotification) {
                        startingAnimation.start()
                        Constants.vibrate(this@MainActivity, true)
                        return@collect
                    }
                    startingAnimation.cancel()
                    updateButtonState(isRunning)
                    return@collect
                }
                startingAnimation.cancel()
                updateButtonState(isRunning)
            }
        }

        binding.menuIcon.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.navView.setNavigationItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.nav_home -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_dlna -> {
                    val intent = Intent(this, DlnaActivity::class.java)
//                    startActivity(intent)
//                    binding.drawerLayout.closeDrawer(GravityCompat.START)
//                    true
//                }
//                R.id.nav_chromecast -> {
//                    val intent = Intent(this, ChromecastActivity::class.java)
                    startActivity(intent)
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
        Toast.makeText(this, getString(R.string.copied_to_clipboard, btcAddress), Toast.LENGTH_SHORT).show()
    }
    override fun onResume() {
        super.onResume()
    }
    private fun toggleService() {
        binding.button.isEnabled = false
        disableButtons()
        if (UpnpRepository.kinoService.isRunning.value) {
            //if service is running, stop it
            stopService(Intent(this, UpnpService::class.java))
            return
        }
        // else
        //if service is not running, try and start it
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            
            @Suppress("DEPRECATION")
            val wifiNetwork = connectivityManager.allNetworks.find { network ->
                val capabilities = connectivityManager.getNetworkCapabilities(network)
                val linkProperties = connectivityManager.getLinkProperties(network)
                val networkInterface = linkProperties?.let { NetworkInterface.getByName(it.interfaceName) }
                
                capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                networkInterface?.supportsMulticast() == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
            }

            if (wifiNetwork == null) {
                throw Exception("Error: No Wi-Fi or Hotspot network detected")
            }

            val linkProperties = connectivityManager.getLinkProperties(wifiNetwork)
            val address = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address
            if (address == null) {
                throw Exception("Error: Could not get IP address")
            }

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                throw Exception("Error: Notification permission not granted")
            }
            if (android.os.Build.VERSION.SDK_INT >= 35 && ContextCompat.checkSelfPermission(this, "android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED) {
                throw Exception("Error: Local network permission not granted")
            }

            val hasVisualPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val hasImages = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                val hasVideo = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                val hasPartial = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
                Log.d("upnpkino", "toggleService visual check: hasImages=$hasImages, hasVideo=$hasVideo, hasPartial=$hasPartial")
                hasImages || hasVideo || hasPartial
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            }

            if (!hasVisualPermission) {
                throw Exception("Error: Photo/Video storage permission not granted")
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    throw Exception("Error: Audio storage permission not granted")
                }
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

            if (startService(Intent(this, UpnpService::class.java).apply {
                putExtra("ip_address", address.hostAddress)
            }) == null) {
                updateButtonState(false)
            }
        } catch (e: Exception) {
            binding.button.isEnabled = true
            enableButtons()
            Constants.vibrate(this)
            Toast.makeText(this, e.message, Toast.LENGTH_LONG).show()
            return
        }
    }
    private fun disableButtons(){
        binding.button2.isEnabled = false
        binding.button2Label.isEnabled = false
        binding.button3.isEnabled = false
        binding.button3Label.isEnabled = false
    }
    private fun enableButtons(){
        binding.button2.isEnabled = true
        binding.button2Label.isEnabled = true
        binding.button3.isEnabled = true
        binding.button3Label.isEnabled = true
    }
    private fun updateButtonState(isRunning: Boolean) {
        if (isRunning) {
            startingAnimation.cancel()
            val typedValue = TypedValue()
            theme.resolveAttribute(R.attr.serviceRunningIconColor, typedValue, true)
            binding.imageView.setColorFilter(typedValue.data)
            binding.button.text = getString(R.string.stop_upnp_kino)
            binding.button.isEnabled = true
            disableButtons()
            return
        }
        // else
        startingAnimation.cancel()
        binding.imageView.setColorFilter(ContextCompat.getColor(this, R.color.darker_grey))
        binding.button.text = getString(R.string.start_upnp_kino)
        binding.button.isEnabled = true
        enableButtons()
    }
}
