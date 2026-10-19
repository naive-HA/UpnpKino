package acab.naiveha.upnpkino

import android.util.Log
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

class BoundSocketFactory(
    private val addressProvider: () -> InetAddress?
) : SocketFactory() {
    private val defaultFactory = getDefault()

    fun createAndBindSocket(): Socket {
        val socket = defaultFactory.createSocket()
        bindSocket(socket)
        return socket
    }

    private fun bindSocket(socket: Socket) {
        val localAddr = addressProvider() ?: return
        if (socket.isBound) return
        try {
            socket.bind(InetSocketAddress(localAddr, 0))
        } catch (e: Exception) {
            Log.w("BoundSocketFactory", "Failed to bind socket to local address $localAddr: ${e.message}")
        }
    }

    override fun createSocket(): Socket {
        val socket = defaultFactory.createSocket()
        bindSocket(socket)
        return socket
    }

    override fun createSocket(host: String, port: Int): Socket {
        val socket = defaultFactory.createSocket()
        bindSocket(socket)
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket {
        val socket = defaultFactory.createSocket(host, port, localHost, localPort)
        return socket
    }

    override fun createSocket(host: InetAddress, port: Int): Socket {
        val socket = defaultFactory.createSocket()
        bindSocket(socket)
        socket.connect(InetSocketAddress(host, port))
        return socket
    }

    override fun createSocket(host: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val socket = defaultFactory.createSocket(host, port, localAddress, localPort)
        return socket
    }
}
