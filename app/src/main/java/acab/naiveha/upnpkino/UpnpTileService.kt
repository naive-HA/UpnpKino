package acab.naiveha.upnpkino

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.net.Inet4Address
import java.net.NetworkInterface

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
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    tile.subtitle = "Starting..."
                }
            } else if (isRunning) {
                tile.state = Tile.STATE_ACTIVE
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    tile.subtitle = "Running"
                }
            } else {
                tile.state = Tile.STATE_INACTIVE
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    tile.subtitle = "Stopped"
                }
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
        } else {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
                    Constants.vibrate(this)
                    return
                }

                val linkProperties = connectivityManager.getLinkProperties(wifiNetwork)
                val address = linkProperties?.linkAddresses?.find { it.address is Inet4Address }?.address
                if (address == null) {
                    Constants.vibrate(this)
                    return
                }

                val preferences = Preferences(this)
                if (preferences.getLocalMovieFolderUri() == null && preferences.getLocalMusicFolderUri() == null) {
                    Constants.vibrate(this)
                    return
                }

                startService(Intent(this, UpnpService::class.java).apply {
                    putExtra("ip_address", address.hostAddress)
                })
            } catch (e: Exception) {
                Constants.vibrate(this)
            }
        }
    }
}
