package acab.naiveha.upnpkino

import android.content.Context
import android.os.Build
import java.net.InetAddress

class Configuration(val context: Context, val upnpService: UpnpService) {
    var ip: InetAddress? = null
    var httpPort: Int = 0
    val deviceName = "UPnP Kino by naive-HA (${Build.MODEL})"
    val osName = "${System.getProperty("os.name")}/${System.getProperty("os.version")}"
    val uuid: String by lazy {
        upnpService.preferences.getDeviceUuid()
    }
    private val random = kotlin.random.Random(System.nanoTime())

    fun release() { }

    fun setHttpServerPort(port: Int) {
        httpPort = port
    }

    fun getHttpServerPort(): Int {
        return httpPort
    }

    fun setInetAddress(ipAddress: InetAddress?) {
        ip = ipAddress
    }

    fun getInetAddress(): InetAddress? {
        return ip
    }

    fun getIpAddress(): String? {
        return ip?.hostAddress
    }

    fun generateRandomId(length: Int, existingIds: Set<String>): String {
        var id = generateRandomLabel(length)
        while (existingIds.contains(id)) {
            id = generateRandomLabel(length)
        }
        return id
    }

    fun generateRandomLabel(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length).map { allowedChars.random(random) }.joinToString("")
    }
}
