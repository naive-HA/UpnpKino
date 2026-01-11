package acab.naiveha.upnpkino

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address
import java.net.InetAddress

class UpnpService : Service() {
    companion object {
        private val _isRunning = MutableStateFlow(false)
        val isRunning = _isRunning.asStateFlow()
        private const val CHANNEL_ID = "UpnpKinoServiceChannel"
        private val _events = MutableSharedFlow<String>()
        val events = _events.asSharedFlow()
    }
    private lateinit var controlServer: ControlServer
    private lateinit var serviceServer: ServiceServer
    private lateinit var mediaServer: MediaServer
    lateinit var configuration: Configuration
    lateinit var preferences: Preferences
    lateinit var upnpMessages: UpnpMessages
    private var wakeLock: PowerManager.WakeLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private lateinit var connectivityManager: ConnectivityManager
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            getWifiIpAddress(network)?.let {
                configuration.setInetAddress(it)
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            vibrate()
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(this@UpnpService, "Error: Network disconnected. Reconnect WiFi and restart the service", Toast.LENGTH_LONG).show()
            }
            stopSelf()
        }
    }

    suspend fun postEvent(event: String) {
        _events.emit(event)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        //initialize preferences
        //Preferences is static
        preferences = Preferences(this@UpnpService)

        //initialize configuration
        //configuration is dynamic
        configuration = Configuration(this@UpnpService)

        //initialize upnpMessages
        upnpMessages = UpnpMessages(this@UpnpService, this@UpnpService)

        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    @SuppressLint("WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        _isRunning.value = false
        try {
            createNotificationChannel()
            startForeground(1, createNotification("Starting servers..."))
        } catch(e: Exception){
            return START_NOT_STICKY
        }
        configuration.readSharedFolder {//onFinish
            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            //acquire locks
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "UpnpStreamer::WakeLock")
            wakeLock?.acquire()

            val wifiManager = getSystemService(WIFI_SERVICE) as WifiManager
            multicastLock = wifiManager.createMulticastLock("UpnpStreamer::MulticastLock")
            multicastLock?.setReferenceCounted(true)
            multicastLock?.acquire()

            //to do: register a listener for changes to storage/shared folder
            //if any changes, update the configuration
            //to do: put a lock on the shared folder to prevent deletion while service is running
            //first start the ServiceServer on a random port
            serviceServer = ServiceServer(this@UpnpService, this@UpnpService)
            serviceServer.start { servicePort, status ->
                if (servicePort == -1) {
                    vibrate()
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(this@UpnpService, status, Toast.LENGTH_LONG).show()
                    }
                    stopSelf()
                } else {
                    configuration.setServiceServerPort(servicePort)

                    //then start the media server on a random port
                    mediaServer = MediaServer(this@UpnpService, this@UpnpService)
                    mediaServer.start { mediaPort, status ->
                        if (mediaPort == -1) {
                            vibrate()
                            Handler(Looper.getMainLooper()).post {
                                Toast.makeText(this@UpnpService, status, Toast.LENGTH_LONG).show()
                            }
                            stopSelf()
                        } else {
                            configuration.setMediaServerPort(mediaPort)

                            //finally, start the control server
                            controlServer = ControlServer(this@UpnpService)
                            controlServer.start { controlPort, status ->
                                if (controlPort == -1) {
                                    vibrate()
                                    Handler(Looper.getMainLooper()).post {
                                        Toast.makeText(this@UpnpService, status, Toast.LENGTH_LONG).show()
                                    }
                                    stopSelf()
                                } else {
                                    val ipAddress = configuration.getIpAddress()
                                    updateNotification("Running on $ipAddress")
                                    _isRunning.value = true
                                }
                            }
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        if (this::mediaServer.isInitialized) {
            mediaServer.stop()
        }
        if (this::serviceServer.isInitialized) {
            serviceServer.stop()
        }
        if (this::controlServer.isInitialized) {
            controlServer.stop()
        }

        //remove locks
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
        wakeLock = null
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
        }
        multicastLock = null

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            //ignore. we are shutting down anyway
        }
        _isRunning.value = false
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        vibrate()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@UpnpService, "Error: Memory low. Shutting down...", Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        vibrate()
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(this@UpnpService, "Error: Memory low. Shutting down...", Toast.LENGTH_LONG).show()
        }
        stopSelf()
    }

    private fun getWifiIpAddress(network: Network): InetAddress? {
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        for (linkAddress in linkProperties.linkAddresses) {
            if (linkAddress.address is Inet4Address) {
                return linkAddress.address
            }
        }
        return null
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

    private fun vibrate() {
        val vibrationEffect = VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500), -1)
        val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        val vibrator = vibratorManager.defaultVibrator
        vibrator.vibrate(vibrationEffect)
    }
}
