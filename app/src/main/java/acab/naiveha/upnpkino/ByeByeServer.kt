package acab.naiveha.upnpkino

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

class ByeByeServer(private val context: Context, private val address: InetAddress?) : Runnable {
    private var hostAddress: InetAddress? = null
    private val group: InetAddress = InetAddress.getByName("239.255.255.250")

    override fun run() {
        Log.d("upnpstreamer", "begin")
        hostAddress = address
        try {
            val byeSocket = MulticastSocket(1900)
            byeSocket.setReuseAddress(true)
            val networkInterface = NetworkInterface.getByInetAddress(hostAddress)
            val socketAddress = InetSocketAddress(group, 1900)

            byeSocket.joinGroup(socketAddress, networkInterface)

            val notification = upnpByeByeR()
            val notificationData = notification.toByteArray(Charsets.UTF_8)
            Log.d("upnpnstreamer", notification)
            val notifyPacket = DatagramPacket(
                notificationData,
                notificationData.size,
                group,
                1900
            )
            Log.d("upnpstreamer", "sending")
            byeSocket.send(notifyPacket)
            Log.d("upnpstreamer", "sent")
            byeSocket.leaveGroup(socketAddress, networkInterface)
            byeSocket.close()
        } catch (e: Exception) {
            Log.e("upnpstreamer", "Failed to send byebye notification.", e)
        }

    }

    private fun upnpByeByeR(): String {
        return ("NOTIFY * HTTP/1.1\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: http://192.168.2.214:65432/desc.xml\r\n" +
                "NT: upnp:rootdevice\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "NTS: ssdp:byebye\r\n" +
                "USN: uuid:::upnp:rootdevice\r\n" +
                "SERVER: Linux/6.1.155 UPnP/1.0 UPnPstreamer/1.0\r\n\r\n")
    }

    private fun upnpByeByeCD(address: InetAddress?): String {
        return ("NOTIFY * HTTP/1.1\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: http://${address?.hostAddress}:65432/desc.xml\r\n" +
                "NT: urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "NTS: ssdp:byebye\r\n" +
                "USN: uuid:::urn:schemas-upnp-org:service:ContentDirectory:1\r\n" +
                "SERVER: Linux/6.1.155 UPnP/1.0 UPnPstreamer/1.0\r\n\r\n")
    }

    private fun upnpByeByeMS(address: InetAddress?): String {
        return ("NOTIFY * HTTP/1.1\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: http://${address?.hostAddress}:65432/desc.xml\r\n" +
                "NT: urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "NTS: ssdp:byebye\r\n" +
                "USN: uuid:::urn:schemas-upnp-org:device:MediaServer:1\r\n" +
                "SERVER: Linux/6.1.155 UPnP/1.0 UPnPstreamer/1.0\r\n\r\n")
    }

    private fun upnpByeByeNone(address: InetAddress?): String {
        return ("NOTIFY * HTTP/1.1\r\n" +
                "CACHE-CONTROL: max-age=1800\r\n" +
                "LOCATION: http://${address?.hostAddress}:65432/desc.xml\r\n" +
                "NT: uuid:\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "NTS: ssdp:byebye\r\n" +
                "USN: uuid:\r\n" +
                "SERVER: Linux/6.1.155 UPnP/1.0 UPnPstreamer/1.0\r\n\r\n")
    }
}