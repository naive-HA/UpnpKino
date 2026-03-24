package acab.naiveha.upnpkino

import android.content.Context
import android.media.MediaMetadataRetriever
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
import androidx.core.net.toUri

class HttpServer(private val context: Context, val upnpService: UpnpService) {

    private var server: Server? = null
    var listeningPort = 0

    private fun sendXmlResponse(response: Response, callback: Callback, xml: String) {
        response.status = HttpStatus.OK_200
        response.headers.put(HttpHeader.CONTENT_TYPE, "text/xml; charset=utf-8")
        val bytes = xml.toByteArray(Charsets.UTF_8)
        response.headers.put(HttpHeader.CONTENT_LENGTH, bytes.size.toString())
        val outStream = Content.Sink.asOutputStream(response)
        outStream.write(bytes)
        callback.succeeded()
    }

    fun start(onStarted: (port: Int, status: String) -> Unit) {
        val threadPool = QueuedThreadPool()
        threadPool.minThreads = 8
        threadPool.maxThreads = 200
        server = Server(threadPool)
        if (server == null){
            onStarted(-1, "Error: Media server failed to start")
            return
        }

        val connector = ServerConnector(server)
        connector.port = upnpService.configuration.getHttpServerPort() // initial port = 0, random
        server?.addConnector(connector)

        server?.handler = object : Handler.Abstract() {
            override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                val method = request.method
                val path = request.httpURI.path
                if (method == "HEAD") {
                    when (path){
                        "/${upnpService.configuration.uuid}/icon" -> {
                            response.status = HttpStatus.OK_200
                            val iconUri = "android.resource://${context.packageName}/${R.raw.icon}".toUri()
                            val mmr = MediaMetadataRetriever()
                            var mimeType = "image/png"
                            try {
                                mmr.setDataSource(context, iconUri)
                                mimeType = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "image/png"
                            } catch (e: Exception) {
                                // Fallback to hardcoded if retriever fails
                            } finally {
                                mmr.release()
                            }
                            response.headers.put(HttpHeader.CONTENT_TYPE, mimeType)
                            response.headers.put(HttpHeader.CONTENT_LENGTH, context.resources.openRawResourceFd(R.raw.icon).length.toString())
                            callback.succeeded()
                            return true
                        }
                    }
                }
                else if (method == "GET") {
                    when (path) {
                        "/" -> {
                            sendXmlResponse(response, callback, upnpService.upnpMessages.draftServiceServerDescription())
                            return true
                        }
                        "/${upnpService.configuration.uuid}/icon" -> {
                            val inputStream = context.resources.openRawResource(R.raw.icon)
                            response.status = HttpStatus.OK_200
                            val iconUri = "android.resource://${context.packageName}/${R.raw.icon}".toUri()
                            val retriever = MediaMetadataRetriever()
                            var mimeType = "image/png"
                            try {
                                retriever.setDataSource(context, iconUri)
                                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "image/png"
                            } catch (e: Exception) {
                                // Fallback to hardcoded if retriever fails
                            } finally {
                                retriever.release()
                            }
                            response.headers.put(HttpHeader.CONTENT_TYPE, mimeType)
                            response.headers.put(HttpHeader.CONTENT_LENGTH, context.resources.openRawResourceFd(R.raw.icon).length.toString())
                            val outStream = Content.Sink.asOutputStream(response)
                            inputStream.copyTo(outStream)
                            inputStream.close()
                            callback.succeeded()
                            return true
                        }
                        "/${upnpService.configuration.uuid}/ContentDirectory/scpd.xml" -> {
                            sendXmlResponse(response, callback, upnpService.upnpMessages.draftContentDirectoryScpdDescription())
                            return true
                        }
                        "/${upnpService.configuration.uuid}/MediaReceiverRegistrar/scpd.xml" -> {
                            sendXmlResponse(response, callback, upnpService.upnpMessages.draftMediaReceiverRegistrarScpdDescription())
                            return true
                        }
                        "/${upnpService.configuration.uuid}/ConnectionManager/scpd.xml" -> {
                            sendXmlResponse(response, callback, upnpService.upnpMessages.draftConnectionManagerScpdDescription())
                            return true
                        }
                        else -> {
                            val targetName = path.substringAfterLast('/').substringBeforeLast('.')
                            val targetExtension = path.substringAfterLast('/').substringAfterLast('.')

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
                }
                else if (method == "POST") {
                    val inputStream = Content.Source.asInputStream(request)
                    val payload = inputStream.bufferedReader().use { it.readText() }
                    if (payload.isEmpty() || !upnpService.upnpMessages.isXmlValid(payload)){
                        response.status = HttpStatus.BAD_REQUEST_400
                        callback.succeeded()
                        return true
                    }

                    when (path) {
                        "/${upnpService.configuration.uuid}/ContentDirectory/control.xml" -> {
                            sendXmlResponse(response, callback, upnpService.upnpMessages.draftContentDirectoryControlDescription(payload))
                            return true
                        }
                        "/${upnpService.configuration.uuid}/ConnectionManager/control.xml" -> {
                            // not yet implemented
                        }
                        "/${upnpService.configuration.uuid}/ContentDirectory/event.xml" -> {
                            // not yet implemented
                        }
                        "/${upnpService.configuration.uuid}/ConnectionManager/event.xml" -> {
                            // not yet implemented
                        }
                    }
                }
                response.status = HttpStatus.METHOD_NOT_ALLOWED_405
                callback.succeeded()
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
