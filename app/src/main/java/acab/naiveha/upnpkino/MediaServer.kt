package acab.naiveha.upnpkino

import android.content.Context
import org.eclipse.jetty.io.EofException
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.Handler
import java.io.FileInputStream
import java.io.IOException
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpStatus
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.QueuedThreadPool
import kotlin.math.min

class MediaServer(private val context: Context, val upnpService: UpnpService) {

    private var server: Server? = null
    var listeningPort = 0

    fun start(onStarted: (port: Int, status: String) -> Unit) {
//        server = Server(InetSocketAddress(upnpService.configuration.getMediaServerPort()))
        // Configure a standard thread pool for the server.
        val threadPool = QueuedThreadPool()
        threadPool.minThreads = 8
        threadPool.maxThreads = 200 // Default max threads value in Jetty 9.4 is 200
        server = Server(threadPool)
        if (server == null){
            onStarted(-1, "Error: Media server failed to start")
            return
        }

        // The connector now needs to be configured manually.
        val connector = ServerConnector(server)
        connector.port = upnpService.configuration.getMediaServerPort()
        server?.addConnector(connector)

        server?.handler = object : Handler.Abstract() {
            override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                val target = request.httpURI.path
                val path = target.drop(1)
                val targetName = path.substringBeforeLast('.')
                val targetExtension = path.substringAfterLast('.')

                if (!upnpService.configuration.sharedTree.keys.contains(targetName) || upnpService.configuration.sharedTree[targetName]?.type != "item") {
                    response.status = HttpStatus.NOT_FOUND_404
                    callback.succeeded()
                    return true
                }

                val uri = upnpService.configuration.sharedTree[targetName]?.uri
                if (uri == null) {
                    response.status = HttpStatus.INTERNAL_SERVER_ERROR_500
                    callback.succeeded()
                    return true
                }

                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        val fileSize = upnpService.configuration.sharedTree[targetName]?.size
                        val rangeHeader = request.headers.get(HttpHeader.RANGE.asString())

                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            val range = rangeHeader.substring(6).split("-")
                            val start = range[0].toLong()

                            if (start >= fileSize!!) {
                                response.status = HttpStatus.RANGE_NOT_SATISFIABLE_416
                                response.headers.put(HttpHeader.CONTENT_RANGE.asString(), "bytes */$fileSize")
                            } else {
                                val end = if (range.size > 1 && range[1].isNotEmpty()) range[1].toLong() else fileSize - 1
                                response.status = HttpStatus.PARTIAL_CONTENT_206
                                response.headers.put(HttpHeader.CONTENT_RANGE.asString(), "bytes $start-$end/$fileSize")
                                response.headers.put(HttpHeader.CONTENT_LENGTH.asString(), (end - start + 1).toString())

                                FileInputStream(pfd.fileDescriptor).use { inStream ->
                                    inStream.skip(start)
                                    val buffer = ByteArray(8192)
                                    var bytesLeft = end - start + 1
                                    val outStream = Content.Sink.asOutputStream(response)//response.httpOutput
                                    while (bytesLeft > 0) {
                                        val toRead = min(bytesLeft, buffer.size.toLong()).toInt()
                                        val read = inStream.read(buffer, 0, toRead)
                                        if (read == -1) break
                                        outStream.write(buffer, 0, read)
                                        bytesLeft -= read
                                    }
                                }
                            }
                        } else {
                            response.status = HttpStatus.OK_200
                            response.headers.put(HttpHeader.CONTENT_TYPE.asString(), Constants.mimeType[targetExtension])
                            response.headers.put(HttpHeader.CONTENT_LENGTH.asString(), fileSize.toString())
                            val outStream = Content.Sink.asOutputStream(response)
                            FileInputStream(pfd.fileDescriptor).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                    }
                } catch (e: EofException) {
                    // This is expected when a client disconnects during streaming.
                } catch (e: IOException) {
                    // This is also expected when a client disconnects, often seen as a "Broken pipe".
                } catch (e: Exception) {
                    //An unexpected error occurred while streaming
                    if (!response.isCommitted) {
                        response.status = HttpStatus.INTERNAL_SERVER_ERROR_500
                    }
                } finally {
                    callback.succeeded()
                }
                return true
            }
        }

        Thread {
            try {
                server?.start()
                listeningPort = (server?.connectors?.get(0) as? ServerConnector)?.localPort ?: -1
                onStarted(listeningPort, "Media server started successfully")
                server?.join()
            } catch (e: Exception) {
                onStarted(-1, "Error: Media server failed to start. ${e.toString()}")
            }
        }.start()
    }

    fun stop() {
        try {
            server?.stop()
        } catch (e: Exception) {
            //ignore. we are shutting down anyway
        }
    }
}
