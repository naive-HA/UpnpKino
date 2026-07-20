package acab.naiveha.upnpkino

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import org.eclipse.jetty.io.EofException
import org.eclipse.jetty.server.Request
import org.eclipse.jetty.server.Response
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.server.ServerConnector
import org.eclipse.jetty.server.Handler
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jetty.http.HttpHeader
import org.eclipse.jetty.http.HttpStatus
import org.eclipse.jetty.io.Content
import org.eclipse.jetty.util.Callback
import org.eclipse.jetty.util.thread.QueuedThreadPool
import kotlin.math.min
import androidx.core.net.toUri
import java.nio.ByteBuffer
import java.nio.ByteOrder

class HttpServer(private val context: Context, val upnpService: UpnpService) {
    private var server: Server? = null
    var listeningPort = 0

    private fun sendXmlResponse(response: Response, callback: Callback, xml: String) {
        Log.v("HttpServer", "Sending XML response: bytes=${xml.length}")
        response.status = HttpStatus.OK_200
        response.headers.put(HttpHeader.CONTENT_TYPE, "text/xml; charset=utf-8")
        val bytes = xml.toByteArray(Charsets.UTF_8)
        response.headers.put(HttpHeader.CONTENT_LENGTH, bytes.size.toString())
        val outStream = Content.Sink.asOutputStream(response)
        outStream.write(bytes)
        callback.succeeded()
    }

    suspend fun start(): Pair<Int, String> = withContext(Dispatchers.IO) {
        try {
            val threadPool = QueuedThreadPool()
            threadPool.minThreads = 2
            threadPool.maxThreads = 30
            val s = Server(threadPool)
            server = s

            val connector = ServerConnector(s)
            connector.port = upnpService.configuration.getHttpServerPort()
            s.addConnector(connector)

            s.handler = object : Handler.Abstract() {
                override fun handle(request: Request, response: Response, callback: Callback): Boolean {
                    val method = request.method
                    val path = request.httpURI.path
                    Log.v("HttpServer", "Incoming request: method=$method path=$path remote=${Request.getRemoteAddr(request)}")
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
                                context.resources.openRawResourceFd(R.raw.icon).use { fd ->
                                    response.headers.put(HttpHeader.CONTENT_LENGTH, fd.length.toString())
                                }
                                callback.succeeded()
                                return true
                            }
                            "/${upnpService.configuration.uuid}/splash" -> {
                                response.status = HttpStatus.OK_200
                                response.headers.put(HttpHeader.CONTENT_TYPE, "audio/wav")
                                callback.succeeded()
                                return true
                            }
                        }
                    }
                    else if (method == "GET") {
                        when (path) {
                            "/" -> {
                                sendXmlResponse(response, callback, upnpService.upnpMessages.draftUpnpDescription())
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
                                } finally {
                                    retriever.release()
                                }
                                response.headers.put(HttpHeader.CONTENT_TYPE, mimeType)
                                context.resources.openRawResourceFd(R.raw.icon).use { fd ->
                                    response.headers.put(HttpHeader.CONTENT_LENGTH, fd.length.toString())
                                }
                                val outStream = Content.Sink.asOutputStream(response)
                                inputStream.copyTo(outStream)
                                inputStream.close()
                                callback.succeeded()
                                return true
                            }
                            "/${upnpService.configuration.uuid}/splash" -> {
                                val sampleRate = 8_000
                                val fakeSize   = Int.MAX_VALUE          // claims ~74 h; receiver disconnects when real LOAD fires
                                val header = ByteBuffer.allocate(44).apply {
                                    order(ByteOrder.LITTLE_ENDIAN)
                                    put("RIFF".toByteArray(Charsets.US_ASCII))
                                    putInt(36 + fakeSize)               // RIFF chunk size
                                    put("WAVE".toByteArray(Charsets.US_ASCII))
                                    put("fmt ".toByteArray(Charsets.US_ASCII))
                                    putInt(16)                          // fmt subchunk size (PCM)
                                    putShort(1)                         // AudioFormat = PCM
                                    putShort(1)                         // NumChannels = mono
                                    putInt(sampleRate)                  // SampleRate
                                    putInt(sampleRate)                  // ByteRate  (mono 8-bit: SampleRate × 1 × 1)
                                    putShort(1)                         // BlockAlign
                                    putShort(8)                         // BitsPerSample
                                    put("data".toByteArray(Charsets.US_ASCII))
                                    putInt(fakeSize)                    // data subchunk size
                                }.array()

                                response.status = HttpStatus.OK_200
                                response.headers.put(HttpHeader.CONTENT_TYPE, "audio/wav")
                                // No Content-Length → chunked / open-ended stream

                                val silence   = ByteArray(4_096) { 0x80.toByte() }   // 0x80 = silence for unsigned 8-bit PCM
                                val outStream = Content.Sink.asOutputStream(response)
                                try {
                                    outStream.write(header)
                                    outStream.flush()
                                    while (true) {
                                        outStream.write(silence)
                                        outStream.flush()
                                    }
                                } catch (_: EofException) {
                                } catch (_: IOException) {
                                } finally {
                                    callback.succeeded()
                                }
                                return true
                            }
                            "/${upnpService.configuration.uuid}/ContentDirectory/scpd.xml" -> {
                                response.status = HttpStatus.OK_200
                                sendXmlResponse(response, callback, upnpService.upnpMessages.draftContentDirectoryScpdDescription())
                                return true
                            }
                            "/${upnpService.configuration.uuid}/MediaReceiverRegistrar/scpd.xml" -> {
                                response.status = HttpStatus.OK_200
                                sendXmlResponse(response, callback, upnpService.upnpMessages.draftMediaReceiverRegistrarScpdDescription())
                                return true
                            }
                            "/${upnpService.configuration.uuid}/ConnectionManager/scpd.xml" -> {
                                response.status = HttpStatus.OK_200
                                sendXmlResponse(response, callback, upnpService.upnpMessages.draftConnectionManagerScpdDescription())
                                return true
                            }
                            else -> {
                                val sharedMediaCollection = UpnpRepository.kinoService.sharedMediaCollection.value
                                val targetName = path.substringBeforeLast('/').substringAfterLast('/')
                                val node = sharedMediaCollection[targetName]

                                if (node !is Configuration.MediaNode.Item) {
                                    Log.w("HttpServer", "Node not found or not an item for target: $targetName")
                                    response.status = HttpStatus.METHOD_NOT_ALLOWED_405
                                    callback.succeeded()
                                    return true
                                }

                                val uri = node.uri
                                if (uri == Uri.EMPTY) {
                                    Log.e("HttpServer", "URI is empty for media item: ${node.name}")
                                    response.status = HttpStatus.INTERNAL_SERVER_ERROR_500
                                    callback.succeeded()
                                    return true
                                }

                                try {
                                    Log.v("HttpServer", "Serving media item: name=${node.name} size=${node.size} mime=${node.mimeType}")
                                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                                        val fileSize = node.size
                                        val rangeHeader = request.headers.get(HttpHeader.RANGE.asString())
                                        val mimeType = node.mimeType

                                        if (rangeHeader != null) {
                                            Log.v("HttpServer", "Range request: $rangeHeader")
                                            val rangeRegex = Regex("^bytes=(\\d+)-(\\d*)$")
                                            val match = rangeRegex.find(rangeHeader)
                                            if (match == null) {
                                                Log.w("HttpServer", "Invalid range header format: $rangeHeader")
                                                response.status = HttpStatus.RANGE_NOT_SATISFIABLE_416
                                                response.headers.put(HttpHeader.CONTENT_RANGE.asString(), "bytes */$fileSize")
                                            } else {
                                                val start = match.groupValues[1].toLongOrNull()
                                                val endStr = match.groupValues[2]
                                                var end = if (endStr.isNotEmpty()) endStr.toLongOrNull() else fileSize - 1

                                                if (start == null || end == null || start >= fileSize || start > end) {
                                                    Log.w("HttpServer", "Range not satisfiable: start=$start end=$end size=$fileSize")
                                                    response.status = HttpStatus.RANGE_NOT_SATISFIABLE_416
                                                    response.headers.put(HttpHeader.CONTENT_RANGE.asString(), "bytes */$fileSize")
                                                } else {
                                                    if (end >= fileSize) end = fileSize - 1
                                                    Log.v("HttpServer", "Serving partial content: $start-$end/$fileSize")
                                                    response.status = HttpStatus.PARTIAL_CONTENT_206
                                                    response.headers.put(HttpHeader.CONTENT_TYPE, mimeType)
                                                    response.headers.put(HttpHeader.CONTENT_RANGE.asString(), "bytes $start-$end/$fileSize")
                                                    response.headers.put(HttpHeader.CONTENT_LENGTH.asString(), (end - start + 1).toString())

                                                    FileInputStream(pfd.fileDescriptor).use { inStream ->
                                                        inStream.skip(start)
                                                        val buffer = ByteArray(8192)
                                                        var bytesLeft = end - start + 1
                                                        val outStream = Content.Sink.asOutputStream(response)
                                                        while (bytesLeft > 0) {
                                                            val toRead = min(bytesLeft, buffer.size.toLong()).toInt()
                                                            val read = inStream.read(buffer, 0, toRead)
                                                            if (read == -1) break
                                                            outStream.write(buffer, 0, read)
                                                            bytesLeft -= read
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            Log.v("HttpServer", "Full file request: $path")
                                            response.status = HttpStatus.OK_200
                                            response.headers.put(HttpHeader.CONTENT_TYPE, mimeType)
                                            response.headers.put(HttpHeader.CONTENT_LENGTH.asString(), fileSize.toString())
                                            val outStream = Content.Sink.asOutputStream(response)
                                            FileInputStream(pfd.fileDescriptor).use { inStream ->
                                                inStream.copyTo(outStream)
                                            }
                                        }
                                    }
                                } catch (e: EofException) {
                                    Log.v("HttpServer", "Client disconnected (EOF) during media stream")
                                } catch (e: IOException) {
                                    Log.v("HttpServer", "Client disconnected (IO) during media stream: ${e.message}")
                                } catch (e: Exception) {
                                    Log.e("HttpServer", "Unexpected error serving media", e)
                                    //An unexpected error occurred while streaming
                                    if (!response.isCommitted) {
                                        response.status = HttpStatus.INTERNAL_SERVER_ERROR_500
                                    }
                                    // Check if file still exists. If not, stop the service.
                                    try {
                                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)?.let { documentFile ->
                                            if (!documentFile.exists()) {
                                                Log.w("HttpServer", "Shared file no longer exists: $uri")
                                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                    android.widget.Toast.makeText(context, "Error: Shared file not found. Stopping service...", android.widget.Toast.LENGTH_LONG).show()
                                                    upnpService.stopSelf()
                                                }
                                            }
                                        }
                                    } catch (fileException: Exception) {
                                        // ignore
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
                        if (payload.isEmpty()){
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
                    else if (method == "NOTIFY") {
                        val inputStream = Content.Source.asInputStream(request)
                        val payload = inputStream.bufferedReader().use { it.readText() }
                        if (path == "/${upnpService.configuration.uuid}/dlnaSubscriptionCallback") {
                            val remoteAddr = Request.getRemoteAddr(request) ?: ""
                            upnpService.dlnaController.handleMediaEvent(remoteAddr, payload)
                            response.status = HttpStatus.OK_200
                            callback.succeeded()
                            return true
                        }
                    }
                    response.status = HttpStatus.METHOD_NOT_ALLOWED_405
                    callback.succeeded()
                    return true
                }
            }

            s.start()
            listeningPort = (s.connectors[0] as ServerConnector).localPort
            Log.d("HttpServer", "Media server started successfully on port $listeningPort")
            Pair(listeningPort, "Media server started successfully")
        } catch (e: Exception) {
            Log.e("HttpServer", "Error: Media server failed to start", e)
            Pair(-1, "Error: Media server failed to start. ${e.toString()}")
        }
    }

    fun stop() {
        try {
            server?.stop()
        } catch (e: Exception) {
            //ignore. we are shutting down anyway
        }
    }
}
