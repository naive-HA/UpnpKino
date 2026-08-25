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
import acab.naiveha.upnpkino.databinding.ActivityTranscoderBinding
import android.util.Log
import kotlinx.coroutines.launch
import kotlin.collections.get

/*TODO: polish up the code
* do not repeat yourself */

class TranscoderActivity : AppCompatActivity() {
    private val repo = UpnpRepository.transcoder
    private val sharedMediaCollection = UpnpRepository.kinoService.sharedMediaCollection
    private lateinit var binding: ActivityTranscoderBinding
    private lateinit var startingAnimation: ValueAnimator
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityTranscoderBinding.inflate(layoutInflater)
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
        binding.buttonStop.isEnabled = false
        binding.buttonStop.setOnClickListener {
        }
        binding.buttonPlay.isEnabled = false
        binding.buttonPlay.setOnClickListener {
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
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }

                R.id.nav_transcoder -> {
                    binding.drawerLayout.closeDrawer(GravityCompat.START)
                    true
                }

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
        binding.navView.setCheckedItem(R.id.nav_transcoder)
        binding.mediaFile.setOnClickListener {
            Log.d("upnpkino", "tapped the button")
            if (sharedMediaCollection.value.isNotEmpty()) {
                if (supportFragmentManager.findFragmentByTag("Selector") == null) {
                    val selectedFileId = repo.selectedMediaFileId.value
                    val mediaSource = SelectorMap(sharedMediaCollection.value) { id -> repo.setSelectedMediaFileId(id) }
                    SelectorFragment.newInstance("Select media file", mediaSource, selectedFileId)
                        .show(supportFragmentManager, "Selector")
                }
            } else if (UpnpRepository.kinoService.sharedMediaCollection.value.isEmpty()) {
                Toast.makeText(this, getString(R.string.error_start_service_first), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.error_select_dlna_first), Toast.LENGTH_SHORT).show()
            }

        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpnpRepository.kinoService.isRunning.collect { isRunning ->
                    if (!isRunning) {
                        startingAnimation.cancel()
                        binding.imageView.setColorFilter(darkGrey)
                        binding.mediaFile.isEnabled = false
                        binding.mediaFile.text = getString(R.string.status_not_running)
                        binding.mediaFile.setTextColor(darkGrey)
                        binding.buttonPlay.isEnabled = false
                        binding.buttonStop.isEnabled = false
                        binding.seekBar.isEnabled = false
                    }
                    binding.mediaFile.isEnabled = true
                    binding.mediaFile.text = getString(R.string.status_tap_to_select)
                    binding.mediaFile.setTextColor(white)
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                UpnpRepository.kinoService.isStarting.collect { isStarting ->
                    if (isStarting) {
                        binding.imageView.setColorFilter(darkGrey)
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
            }
        }
        lifecycleScope.launch {
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.selectedMediaFileId.collect { selectedMediaFileId ->
                    if(!UpnpRepository.kinoService.isRunning.value) return@collect
                    if (selectedMediaFileId != null) {
                        val mediaFile = sharedMediaCollection.value[selectedMediaFileId]
                        binding.mediaFile.text = mediaFile?.name
                        binding.mediaFile.setTextColor(white)
                        binding.buttonPlay.isEnabled = true
                        binding.buttonStop.isEnabled = true
                        binding.seekBar.isEnabled = true
                    } else {
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
                repo.transcodingFlag.collect { transcodingFlag ->
                    if(!UpnpRepository.kinoService.isRunning.value) return@collect
                    if(repo.selectedMediaFileId.value == null) return@collect
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.seekBarDuration.collect { seekBarDuration ->
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
        repo.setTranscoderActivityVisible(true)
    }
    override fun onPause() {
        super.onPause()
        repo.setTranscoderActivityVisible(false)
    }
    override fun onDestroy() {
        super.onDestroy()
    }
}
