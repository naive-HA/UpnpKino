package acab.naiveha.upnpkino

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import android.util.Log
import kotlin.time.Duration.Companion.milliseconds

class MulticastServer(private val upnpService: UpnpService) {

    private var socket: MulticastSocket? = null
    private var socketAddress: InetSocketAddress? = null
    private var networkInterface: NetworkInterface? = null
    private val group: InetAddress = InetAddress.getByName("239.255.255.250")
    private var controlServerThread: Thread? = null
    private val outboundMessages = Channel<String>(Channel.UNLIMITED)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun start(): Pair<Int, String> = withContext(Dispatchers.IO) {
        scope.launch {
            combine(UpnpRepository.upnp.repeatAliveNotification,
                UpnpRepository.dlna.searchForDevices) { repeatAliveNotification, searchForDevices ->
                repeatAliveNotification to searchForDevices
            }.collect { (repeatAliveNotification, searchForDevices) ->
                if (repeatAliveNotification) {
                    repeatAliveNotification()
                }
                if (searchForDevices) {
                    repeatMsearchNotification()
                }
            }
        }
        try {
            Log.d("MulticastServer", "Binding multicast socket to port 1900")
            socket = MulticastSocket(null)
            socket?.reuseAddress = true
            socket?.bind(InetSocketAddress(1900))
            socket?.receiveBufferSize = 32768
            socket?.soTimeout = 1000
            
            val inetAddress = upnpService.configuration.getInetAddress()
            if (inetAddress == null) {
                Log.e("MulticastServer", "Failed to get network interface: IP address is null")
                return@withContext Pair(-1, "Error: Control server failed to start. Failed to get network interface, IP address is null")
            }
            networkInterface = NetworkInterface.getByInetAddress(inetAddress)
            if (networkInterface == null) {
                Log.e("MulticastServer", "Could not find a network interface for IP address: ${inetAddress.hostAddress}")
                return@withContext Pair(-1, "Error: Control server failed to start. Could not find a network interface for IP address: ${inetAddress.hostAddress}")
            }

            if (!networkInterface!!.supportsMulticast()) {
                Log.e("MulticastServer", "Network interface ${networkInterface!!.displayName} does not support multicast.")
                return@withContext Pair(-1, "Error: Control server failed to start. Network interface ${networkInterface!!.displayName} does not support multicast.")
            }
            
            // Set the network interface for outgoing multicast packets
            socket?.networkInterface = networkInterface
            
            socketAddress = InetSocketAddress(group, 1900)
            val initialMessages = upnpService.upnpMessages.draftNotifyMessage("alive")
            
            Log.d("MulticastServer", "Joining multicast group: $group on interface ${networkInterface?.displayName}")
            socket?.joinGroup(socketAddress, networkInterface)
            
            //notify the network of server's presence
            Log.d("MulticastServer", "Sending initial 'alive' notifications")
            for (notification in initialMessages) {
                val notificationData = notification.toByteArray(Charsets.UTF_8)
                val notifyPacket = DatagramPacket(
                    notificationData,
                    notificationData.size,
                    group,
                    1900
                )
                socket?.send(notifyPacket)
            }
        } catch (e: Exception) {
            Log.e("MulticastServer", "Failed to start multicast server", e)
            return@withContext Pair(-1, "Error: Control server failed to start. ${e.toString()}")
        }
        controlServerThread = Thread {
            Log.d("MulticastServer", "Starting control server thread")
            while (Thread.currentThread().isInterrupted.not()) {
                try {
                    val buffer = ByteArray(4096)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    val currentInetAddress = upnpService.configuration.getInetAddress()
                    if (packet.address != currentInetAddress && !packet.address.isLoopbackAddress) {
                        Log.v("MulticastServer", "Received packet from ${packet.address}:${packet.port}")
                        val inputStream = ByteArrayInputStream(packet.data, 0, packet.length)
                        val headers = mutableListOf<String>()
                        while (true) {
                            val line = readLine(inputStream)
                            if (line.isBlank()) break
                            headers.add(line)
                        }
                        if (headers.isNotEmpty()) {
                            val requestPayload = headers.joinToString("\n")
                            Log.v("MulticastServer", "Multicast request headers:\n$requestPayload")
                            val responses = upnpService.upnpMessages.parseUpnpMulticastRequest(requestPayload)
                            for (response in responses) {
                                val responseData = response.toByteArray(Charsets.UTF_8)
                                Log.v("MulticastServer", "Responding to ${packet.address}:${packet.port}")
                                //respond to the address and port
                                socket?.send(
                                    DatagramPacket(
                                        responseData,
                                        responseData.size,
                                        packet.address,
                                        packet.port
                                    )
                                )
                            }
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    // This is expected, continue loop
                } catch (e: Exception) {
                    Log.v("MulticastServer", "Exception in receive loop: ${e.message}")
                    // Ignore other receiving errors
                }
                while (true) {
                    val notification = outboundMessages.tryReceive().getOrNull() ?: break
                    try {
                        Log.v("MulticastServer", "Sending outbound notification from queue")
                        val notificationData = notification.toByteArray(Charsets.UTF_8)
                        val notifyPacket = DatagramPacket(
                            notificationData,
                            notificationData.size,
                            group,
                            1900
                        )
                        socket?.send(notifyPacket)
                    } catch (e: Exception) {
                        Log.e("MulticastServer", "Failed to send outbound notification", e)
                    }
                }
            }
            
            Log.d("MulticastServer", "Control server thread exiting, sending 'byebye' notifications")
            val finalMessages = upnpService.upnpMessages.draftNotifyMessage("byebye")
            try {
                for (notification in finalMessages) {
                    val notificationData = notification.toByteArray(Charsets.UTF_8)
                    val notifyPacket = DatagramPacket(
                        notificationData,
                        notificationData.size,
                        group,
                        1900
                    )
                    socket?.send(notifyPacket)
                }
            } catch (e: Exception) {
                //ignore. we are shutting down anyway
            }
            socket?.let { sock ->
                socketAddress?.let { addr ->
                    networkInterface?.let { netIf ->
                        try {
                            Log.d("MulticastServer", "Leaving multicast group")
                            sock.leaveGroup(addr, netIf)
                        } catch (e: Exception) {
                            //ignore. we are shutting down anyway
                        }
                    }
                }
                Log.d("MulticastServer", "Closing multicast socket")
                sock.close()
            }
        }
        controlServerThread?.start()
        Pair(1900, "Control server started successfully")
    }

    fun stop() {
        scope.cancel()
        controlServerThread?.interrupt()
    }

    private fun readLine(stream: ByteArrayInputStream): String {
        val lineBuffer = mutableListOf<Byte>()
        var byte: Int
        while (stream.read().also { byte = it } != -1) {
            if (byte == '\n'.code) break
            lineBuffer.add(byte.toByte())
        }
        if (lineBuffer.isNotEmpty() && lineBuffer.last() == '\r'.code.toByte()) {
            lineBuffer.removeAt(lineBuffer.size - 1)
        }
        return String(lineBuffer.toByteArray(), Charsets.UTF_8)
    }
    private fun repeatAliveNotification(){
        scope.launch {
            upnpService.upnpMessages.draftNotifyMessage("alive").forEach { outboundMessages.send(it) }
            delay(8000.milliseconds)
            UpnpRepository.upnp.setRepeatAliveNotification(false)
        }
    }
    private fun repeatMsearchNotification() {
        scope.launch {
            upnpService.upnpMessages.draftMsearchMessage().forEach { outboundMessages.send(it) }
            delay(8000.milliseconds)
            UpnpRepository.dlna.setSearchForDevices(false)
        }
    }
}
