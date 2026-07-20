package acab.naiveha.upnpkino

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress

class UpnpService : Service() {
    companion object {
        private const val CHANNEL_ID = "UpnpKinoServiceChannel"
    }
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var multicastServer: MulticastServer
    private lateinit var httpServer: HttpServer
    lateinit var configuration: Configuration
    lateinit var preferences: Preferences
    lateinit var upnpMessages: UpnpMessages
    lateinit var dlnaController: DlnaController
    lateinit var chromecastController: ChromecastController
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            Constants.vibrate(this@UpnpService)
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@UpnpService, "Error: Network disconnected. Reconnect WiFi and restart the service", Toast.LENGTH_LONG).show()
            }
            stopSelf()
        }
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("UpnpService", "onCreate")
        preferences = Preferences(this@UpnpService)
        configuration = Configuration(this@UpnpService, this@UpnpService)
        upnpMessages = UpnpMessages(this@UpnpService, this@UpnpService)
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ipAddressStr = intent?.getStringExtra("ip_address")
        Log.d("UpnpService", "onStartCommand: ipAddress=$ipAddressStr")
        if (ipAddressStr == null) {
            Log.e("UpnpService", "onStartCommand: ipAddress is null, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }

        UpnpRepository.kinoService.setStarting(true)
        createNotificationChannel()
        startForeground(1, createNotification("Starting servers..."))

        serviceScope.launch {
            val ipAddress = try {
                kotlinx.coroutines.withContext(Dispatchers.IO) {
                    InetAddress.getByName(ipAddressStr)
                }
            } catch (e: Exception) {
                null
            }

            if (ipAddress == null) {
                Log.e("UpnpService", "Failed to resolve IP address: $ipAddressStr")
                stopSelf()
                return@launch
            }

            configuration.setInetAddress(ipAddress)
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            Log.d("UpnpService", "Registering network callback")
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

            FfmpegInstaller.install(this@UpnpService)

            //to do: register a listener for changes to storage/shared folder
            //if any changes, update the configuration
            //to do: put a lock on the shared folder to prevent deletion while service is running
            httpServer = HttpServer(this@UpnpService, this@UpnpService)
            Log.d("UpnpService", "Starting HTTP server")
            val (httpPort, status) = httpServer.start()
            if (httpPort == -1) {
                Log.e("UpnpService", "HTTP server failed to start: $status")
                Constants.vibrate(this@UpnpService)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@UpnpService, status, Toast.LENGTH_LONG).show()
                }
                stopSelf()
            } else {
                configuration.setHttpServerPort(httpPort)
                Log.d("UpnpService", "HTTP server started on port $httpPort. Reading shared folder.")
                configuration.readSharedFolder()
                dlnaController = DlnaController(this@UpnpService, this@UpnpService)
                chromecastController = ChromecastController(this@UpnpService, this@UpnpService)
                multicastServer = MulticastServer(this@UpnpService)
                Log.d("UpnpService", "Starting multicast server")
                val (multicastPort, multicastStatus) = multicastServer.start()
                if (multicastPort == -1) {
                    Log.e("UpnpService", "Multicast server failed to start: $multicastStatus")
                    Constants.vibrate(this@UpnpService)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@UpnpService, multicastStatus, Toast.LENGTH_LONG).show()
                        Log.e("upnpkino", multicastStatus)
                    }
                    stopSelf()
                } else {
                    val powerManager = getSystemService(POWER_SERVICE) as PowerManager
                    wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UpnpStreamer::WakeLock")
                    wakeLock?.acquire()

                    val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
                    multicastLock = wifiManager.createMulticastLock("UpnpStreamer::MulticastLock")
                    multicastLock?.setReferenceCounted(true)
                    multicastLock?.acquire()

                    val currentIpAddress = configuration.getIpAddress()
                    Log.d("UpnpService", "Service successfully started on $currentIpAddress")
                    updateNotification("Running on $currentIpAddress")
                    UpnpRepository.kinoService.setStarting(false)
                    UpnpRepository.kinoService.setRunning(true)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d("UpnpService", "onDestroy")
        if (this::dlnaController.isInitialized) {
            Log.d("UpnpService", "Releasing DlnaController")
            dlnaController.release()
        }
        if (this::chromecastController.isInitialized) {
            Log.d("UpnpService", "Releasing ChromecastController")
            chromecastController.release()
        }
        super.onDestroy()
        serviceScope.cancel()
        if (this::configuration.isInitialized) {
            Log.d("UpnpService", "Releasing Configuration")
            configuration.release()
        }
        if (this::httpServer.isInitialized) {
            Log.d("UpnpService", "Stopping HttpServer")
            httpServer.stop()
        }
        if (this::multicastServer.isInitialized) {
            Log.d("UpnpService", "Stopping MulticastServer")
            multicastServer.stop()
        }
        if (this::connectivityManager.isInitialized) {
            try {
                Log.d("UpnpService", "Unregistering network callback")
                connectivityManager.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                //ignore. we are shutting down anyway
            }
        }
        if (this::preferences.isInitialized) {
             // preferences don't have release but good for consistency
        }
        if (this::upnpMessages.isInitialized) {
             // upnpMessages don't have release but good for consistency
        }

        // Release locks
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        multicastLock = null

        UpnpRepository.stop()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)

            if (memoryInfo.lowMemory) {
                Constants.vibrate(this@UpnpService)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@UpnpService, "Error: Memory low. Shutting down...", Toast.LENGTH_LONG).show()
                }
                stopSelf()
            }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Constants.vibrate(this@UpnpService)
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@UpnpService, "Error: Memory low. Shutting down...", Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "UPnP Kino",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(contentText: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("UPnP Kino")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_video)
            .setContentIntent(pendingIntent)
            .setOngoing(true)

        builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        return builder.build()
    }

    private fun updateNotification(text: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(1, createNotification(text))
    }
}
