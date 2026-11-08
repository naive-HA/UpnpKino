package acab.naiveha.upnpkino

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import androidx.core.net.toUri

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }
    private lateinit var binding: ActivityMainBinding
    private lateinit var preferences: Preferences
    private lateinit var startingAnimation: ValueAnimator
    private val startPermissionsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        Log.d(TAG, "Start permission results: $permissions")
        if (!StartPreconditions.hasLocalNetworkPermission(this)) {
            reportLocalNetworkDenied()
            abortStart()
            return@registerForActivityResult
        }
        if (!hasNotificationPermission()) {
            Toast.makeText(this, R.string.warning_notifications_denied, Toast.LENGTH_LONG).show()
        }
        startServiceIfReady()
    }
    private val selectLocalMovieFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            if (persistFolderGrant(it)) preferences.saveLocalMovieFolderUri(it)
        }
    }
    private val selectLocalMusicFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        uri?.let {
            if (persistFolderGrant(it)) preferences.saveLocalMusicFolderUri(it)
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
        binding.button.setOnClickListener { toggleService() }
        binding.button2.setOnClickListener {
            selectLocalMovieFolderLauncher.launch(null)
        }
        binding.button2Label.setOnClickListener {
            selectLocalMovieFolderLauncher.launch(null)
        }
        binding.button2.setOnLongClickListener {
            preferences.clearLocalMovieFolderUri()
            Constants.vibrate(this)
            Toast.makeText(this, getString(R.string.toast_video_library_cleared), Toast.LENGTH_SHORT).show()
            true
        }
        binding.button2Label.setOnLongClickListener {
            preferences.clearLocalMovieFolderUri()
            Constants.vibrate(this)
            Toast.makeText(this, getString(R.string.toast_video_library_cleared), Toast.LENGTH_SHORT).show()
            true
        }
        binding.button3.setOnClickListener {
            selectLocalMusicFolderLauncher.launch(null)
        }
        binding.button3Label.setOnClickListener {
            selectLocalMusicFolderLauncher.launch(null)
        }
        binding.button3.setOnLongClickListener {
            preferences.clearLocalMusicFolderUri()
            Constants.vibrate(this)
            Toast.makeText(this, getString(R.string.toast_music_library_cleared), Toast.LENGTH_SHORT).show()
            true
        }
        binding.button3Label.setOnLongClickListener {
            preferences.clearLocalMusicFolderUri()
            Constants.vibrate(this)
            Toast.makeText(this, getString(R.string.toast_music_library_cleared), Toast.LENGTH_SHORT).show()
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
            combine(UpnpRepository.kinoService.noOfSharedMediaFiles, UpnpRepository.kinoService.noOfIndexedMediaFiles) { total, indexed ->
                Pair(total, indexed)
            }.collect { (total, indexed) ->
                if (total > 0) {
                    binding.status.text = getString(R.string.status_indexing, indexed, total)
                } else {
                    binding.status.text = ""
                }
            }
        }
        lifecycleScope.launch {
            UpnpRepository.upnp.repeatAliveNotification.collect { repeating ->
                if (repeating) {
                    Toast.makeText(this@MainActivity, getString(R.string.toast_reannouncing), Toast.LENGTH_SHORT).show()
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
                    binding.status.text = getString(R.string.status_starting)
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
                binding.status.text = ""
                UpnpRepository.kinoService.resetNoOfIndexedMediaFiles()
                UpnpRepository.kinoService.resetNoOfSharedMediaFiles()
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
                    startActivity(intent)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
//                R.id.nav_chromecast -> {
//                    val intent = Intent(this, ChromecastActivity::class.java)
//                    startActivity(intent)
//                    binding.drawerLayout.closeDrawer(GravityCompat.START)
//                    true
//                }
//                R.id.nav_transcoder -> {
//                    val intent = Intent(this, TranscoderActivity::class.java)
//                    startActivity(intent)
//                    binding.drawerLayout.closeDrawer(GravityCompat.START)
//                    true
//                }
                R.id.nav_licenses -> {
                    val intent = Intent(this, LicensesActivity::class.java)
                    startActivity(intent)
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }
                R.id.nav_about -> {
                    val intent = Intent(this, AboutActivity::class.java)
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
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // no browser: show the address instead of crashing
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
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
    override fun onDestroy() {
        if (::startingAnimation.isInitialized) startingAnimation.cancel()
        super.onDestroy()
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
        //if service is not running, ask for whatever start still needs, then try and start it
        val missing = missingStartPermissions()
        if (missing.isNotEmpty()) {
            startPermissionsLauncher.launch(missing)
            return
        }
        startServiceIfReady()
    }
    private fun startServiceIfReady() {
        try {
            when (val outcome = StartPreconditions.check(this, preferences)) {
                is StartPreconditions.Outcome.Blocked -> {
                    abortStart()
                    Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                }
                is StartPreconditions.Outcome.Ready -> {
                    if (startService(Intent(this, UpnpService::class.java).apply {putExtra("ip_address", outcome.ipAddress)}) == null) {
                        updateButtonState(false)
                    }
                }
            }
        } catch (e: Exception) {
            abortStart()
            Toast.makeText(this, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
        }
    }
    private fun abortStart() {
        binding.button.isEnabled = true
        enableButtons()
        Constants.vibrate(this)
    }
    private fun hasNotificationPermission(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
    private fun missingStartPermissions(): Array<String> {
        val missing = mutableListOf<String>()
        if (!hasNotificationPermission()) missing.add("android.permission.POST_NOTIFICATIONS")
        if (!StartPreconditions.hasLocalNetworkPermission(this)) missing.add(StartPreconditions.LOCAL_NETWORK_PERMISSION)
        return missing.toTypedArray()
    }
    private fun reportLocalNetworkDenied() {
        if (shouldShowRequestPermissionRationale(StartPreconditions.LOCAL_NETWORK_PERMISSION)) {
            Toast.makeText(this, R.string.error_local_network_permission, Toast.LENGTH_LONG).show()
        } else {
            Snackbar.make(binding.root, R.string.error_local_network_permission_settings, Snackbar.LENGTH_LONG)
                .setAction(R.string.action_open_settings) { openAppSettings() }
                .show()
        }
    }
    private fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.error_local_network_permission_settings, Toast.LENGTH_LONG).show()
        }
    }
    private fun persistFolderGrant(uri: Uri): Boolean {
        val attempts = intArrayOf(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        for (flags in attempts) {
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "takePersistableUriPermission(flags=$flags) refused for $uri: ${e.message}")
            }
        }
        Toast.makeText(this, R.string.error_folder_grant_failed, Toast.LENGTH_LONG).show()
        return false
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
