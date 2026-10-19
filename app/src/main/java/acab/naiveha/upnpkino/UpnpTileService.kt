package acab.naiveha.upnpkino

import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class UpnpTileService : TileService() {
    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var isRunningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        isRunningJob = combine(UpnpRepository.kinoService.isRunning, UpnpRepository.kinoService.isStarting) { isRunning, isStarting ->
            isRunning to isStarting
        }.onEach { (isRunning, isStarting) ->
            val tile = qsTile ?: return@onEach
            tile.label = "UPnP Kino"
            if (isStarting && !isRunning) {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Starting..."
            } else if (isRunning) {
                tile.state = Tile.STATE_ACTIVE
                tile.subtitle = "Running"
            } else {
                tile.state = Tile.STATE_INACTIVE
                tile.subtitle = "Stopped"
            }
            tile.updateTile()
        }.launchIn(serviceScope)
    }

    override fun onStopListening() {
        super.onStopListening()
        isRunningJob?.cancel()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) {
            unlockAndRun {
                performAction()
            }
        } else {
            performAction()
        }
    }

    private fun performAction() {
        if (UpnpRepository.kinoService.isRunning.value) {
            stopService(Intent(this, UpnpService::class.java))
            Constants.vibrate(this)
        } else if (UpnpRepository.kinoService.isStarting.value) {
            return
        } else {
            try {
                when (val outcome = StartPreconditions.check(this, Preferences(this))) {
                    is StartPreconditions.Outcome.Blocked -> {
                        Constants.vibrate(this)
                        Toast.makeText(this, outcome.message, Toast.LENGTH_LONG).show()
                    }
                    is StartPreconditions.Outcome.Ready -> {
                        startService(Intent(this, UpnpService::class.java).apply {
                            putExtra("ip_address", outcome.ipAddress)
                        })
                    }
                }
            } catch (e: Exception) {
                Constants.vibrate(this)
            }
        }
    }
}
