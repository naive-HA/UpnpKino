package acab.naiveha.upnpkino

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.lang.Thread.sleep
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

class ControlServer(private val upnpService: UpnpService) {

    private var socket: MulticastSocket? = null
    private var socketAddress: InetSocketAddress? = null
    private var networkInterface: NetworkInterface? = null
    private val group: InetAddress = InetAddress.getByName("239.255.255.250")
    private var controlServerThread: Thread? = null
    private var messages = emptyList<String>()
    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null

    fun start(onStarted: (port: Int, status: String) -> Unit) {
        job = UpnpService.events.onEach {
            if (it == "acab.naiveha.upnpkino.RepeatAliveNotification") {
                repeatAliveNotification()
            }
        }.launchIn(scope)

        socket = MulticastSocket(1900)
        socket?.reuseAddress = true
        socket?.receiveBufferSize = 32768
        socket?.soTimeout = 1000
        val inetAddress = upnpService.configuration.getInetAddress()
        if (inetAddress == null) {
            onStarted(-1, "Error: Control server failed to start. Failed to get network interface, IP address is null")
            return
        }
        networkInterface = NetworkInterface.getByInetAddress(inetAddress)
        if (networkInterface == null) {
            onStarted(-1, "Error: Control server failed to start. Could not find a network interface for IP address: ${inetAddress.hostAddress}")
            return
        }

        if (!networkInterface!!.supportsMulticast()) {
            onStarted(-1, "Error: Control server failed to start. Network interface ${networkInterface!!.displayName} does not support multicast.")
            return
        }
        socketAddress = InetSocketAddress(group, 1900)
        messages = upnpService.upnpMessages.draftControlServerNotifyMessage("alive")
        try {
            socket?.joinGroup(socketAddress, networkInterface)
            //notify the network of server's presence
            for (notification in messages) {
                val notificationData = notification.toByteArray(Charsets.UTF_8)
                val notifyPacket = DatagramPacket(
                    notificationData,
                    notificationData.size,
                    group,
                    1900
                )
                socket?.send(notifyPacket)
            }
            messages = emptyList()
        } catch (e: Exception) {
            onStarted(-1, "Error: Control server failed to start. ${e.toString()}")
            return
        }
        controlServerThread = Thread {
            while (Thread.currentThread().isInterrupted.not()) {
                try {
                    //649 bytes as per standard
                    val buffer = ByteArray(649)
                    val packet = DatagramPacket(buffer, buffer.size)
                    //blocking receive
                    //timeout set for 1 second
                    socket?.receive(packet)
                    val inputStream = ByteArrayInputStream(packet.data, 0, packet.length)
                    val headers = mutableListOf<String>()
                    while (true) {
                        val line = readLine(inputStream)
                        if (line.isBlank()) break
                        headers.add(line)
                    }
                    if (headers.isEmpty()) continue

                    Log.d("upnpkino", headers.joinToString("\n"))

                    val responses = upnpService.upnpMessages.parseControlServerRequest(headers.joinToString("\n"))
                    for (response in responses) {
                        val responseData = response.toByteArray(Charsets.UTF_8)
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
                } catch (e: SocketTimeoutException) {
                    // This is expected, continue loop
                    // breaks if thread is interrupted: see while
                    for (notification in messages) {
                        val notificationData = notification.toByteArray(Charsets.UTF_8)
                        val notifyPacket = DatagramPacket(
                            notificationData,
                            notificationData.size,
                            group,
                            1900
                        )
                        socket?.send(notifyPacket)
                    }
                    messages = emptyList()
                } catch (e: Exception) {
                    continue
                }
            }
            messages = upnpService.upnpMessages.draftControlServerNotifyMessage("byebye")
            try {
                for (notification in messages) {
                    val notificationData = notification.toByteArray(Charsets.UTF_8)
                    val notifyPacket = DatagramPacket(
                        notificationData,
                        notificationData.size,
                        group,
                        1900
                    )
                    socket?.send(notifyPacket)
                }
                messages = emptyList()
            } catch (e: Exception) {
                //ignore. we are shutting down anyway
            }
            socket?.let { sock ->
                socketAddress?.let { addr ->
                    networkInterface?.let { netIf ->
                        try {
                            sock.leaveGroup(addr, netIf)
                        } catch (e: Exception) {
                            //ignore. we are shutting down anyway
                        }
                    }
                }
                sock.close()
            }
        }
        controlServerThread?.start()
        onStarted(1900, "Control server started successfully")
    }

    fun stop() {
        job?.cancel()
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
        messages = upnpService.upnpMessages.draftControlServerNotifyMessage("byebye")
        scope.launch {
            upnpService.postEvent("acab.naiveha.upnpkino.AnimateImageView")
        }
        upnpService.configuration.readSharedFolder {
            Thread {
                do{sleep(200)} while(messages.isEmpty().not())
                messages = upnpService.upnpMessages.draftControlServerNotifyMessage("alive")
                scope.launch {
                    upnpService.postEvent("acab.naiveha.upnpkino.StopAnimateImageView")
                }
            }.start()
        }
    }
}