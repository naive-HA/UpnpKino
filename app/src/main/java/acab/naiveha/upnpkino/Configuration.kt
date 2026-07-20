package acab.naiveha.upnpkino

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.util.Locale
import kotlin.collections.set
import android.util.Log

class Configuration(val context: Context, val upnpService: UpnpService) {
    sealed class MediaNode : SelectorItem {
        abstract val id: String
        abstract val name: String
        abstract val parent: String
        abstract val uri: Uri
        override val selectionId: String get() = id
        override val displayLabel: String get() = name
        override val parentId: String get() = parent
        data class Container(
            override val id: String,
            override val name: String,
            override val parent: String,
            override val uri: Uri,
            override val children: List<String>
        ) : MediaNode() {
            override val secondaryLabel: String get() = "${children.size} items"
            override val iconResId: Int get() = R.drawable.ic_folder
            override val isContainer: Boolean get() = true
        }
        data class Item(
            override val id: String,
            override val name: String,
            override val parent: String,
            override val uri: Uri,
            val url: String,
            val size: Long,
            val mimeType: String,
            val album: String,
            val artist: String,
            val duration: String,
            val resolution: String
        ) : MediaNode() {
            override val secondaryLabel: String get() = duration
            override val iconResId: Int
                get() = if (mimeType.startsWith("video/")) R.drawable.ic_video_file else R.drawable.ic_audio_file
            override val isContainer: Boolean get() = false
        }
    }
    private val sharedMediaCollection = mutableMapOf<String, MediaNode>()
    var ip: InetAddress? = null
    var httpPort: Int = 0
    val deviceName = "UPnP Kino by naive-HA (${Build.MODEL})"
    val osName = "${System.getProperty("os.name")}/${System.getProperty("os.version")}"
    val uuid: String by lazy {
        upnpService.preferences.getDeviceUuid()
    }
    private val random = kotlin.random.Random(System.nanoTime())
    suspend fun readSharedFolder() = withContext(Dispatchers.IO) {
        Log.d("Configuration", "readSharedFolder started")
        sharedMediaCollection.clear()
        val rootChildren = mutableListOf("1")
        sharedMediaCollection["0"] = MediaNode.Container("0", "UPnP Kino", "0", Uri.EMPTY, rootChildren)
        val libraryChildren = mutableListOf<String>()
        sharedMediaCollection["1"] = MediaNode.Container("1", "Library", "0", Uri.EMPTY, libraryChildren)
        upnpService.preferences.getLocalMovieFolderUri()?.let { uri ->
            val id = "11"
            libraryChildren.add(id)
            val retriever = MediaMetadataRetriever()
            try {
                discoverFolder(id, "Movies", "1", uri, Constants.movieExtensions, retriever)
            } finally {
                retriever.release()
            }
        }
        upnpService.preferences.getLocalMusicFolderUri()?.let { uri ->
            val id = "12"
            libraryChildren.add(id)
            val retriever = MediaMetadataRetriever()
            try {
                discoverFolder(id, "Music", "1", uri, Constants.musicExtensions, retriever)
            } finally {
                retriever.release()
            }
        }
        var modified: Boolean
        do {
            modified = false
            val nodesToRemove = mutableListOf<String>()
            for ((id, node) in sharedMediaCollection) {
                if (node is MediaNode.Container && node.children.isEmpty() && id != "0" && id != "1") {
                    nodesToRemove.add(id)
                    modified = true
                }
            }
            for (idToRemove in nodesToRemove) {
                sharedMediaCollection.remove(idToRemove)
                for ((id, node) in sharedMediaCollection) {
                    if (node is MediaNode.Container && node.children.contains(idToRemove)) {
                        val newChildren = node.children.toMutableList()
                        newChildren.remove(idToRemove)
                        sharedMediaCollection[id] = node.copy(children = newChildren)
                    }
                }
            }
        } while (modified)



        //if sharedMediaCollection has no MediaNode.Item, stop UpnpService




        Log.d("Configuration", "readSharedFolder finished: discovered ${sharedMediaCollection.size} nodes")
        withContext(Dispatchers.Main) {
            UpnpRepository.kinoService.setSharedMediaCollection(sharedMediaCollection.toMap())
        }
    }
    private fun discoverFolder(
        id: String,
        name: String,
        parentId: String,
        uri: Uri,
        extensions: Set<String>,
        retriever: MediaMetadataRetriever
    ) {
        val childrenIds = mutableListOf<String>()
        val rootDocument = DocumentFile.fromTreeUri(context, uri) ?: return
        for (file in rootDocument.listFiles()) {
            if (file.name?.startsWith(".") == true) continue
            val childId = generateRandomId(12, sharedMediaCollection.keys)
            if (file.isDirectory) {
                discoverFolder(childId, "[+] " + (file.name as String), id, file.uri, extensions, retriever)
                if (sharedMediaCollection.containsKey(childId)) {
                    childrenIds.add(childId)
                }
            } else {
                val extension = file.name?.substringAfterLast('.', "")?.lowercase()
                if (extensions.contains(extension)) {
                    var album = ""
                    var artist = ""
                    var duration = "00:00:00"
                    var resolution = "0x0"
                    try {
                        context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
                        }
                        album = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "")
                        artist = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "")
                        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        duration = formatDuration(durationMs)
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "0"
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "0"
                        resolution = "${width}x${height}"
                    } catch (e: Exception) {
                        Log.e("Configuration", "Error processing file: ${file.name} (URI: ${file.uri})")
                    }
                    val mimeType = Constants.mimeType[extension] ?: "application/octet-stream"
                    val item = MediaNode.Item(
                        id = childId,
                        name = (file.name as String),
                        parent = id,
                        uri = file.uri,
                        url = "http://${getIpAddress()}:${getHttpServerPort()}/$uuid/${childId}/${name.urlEscape()}",
                        size = file.length(),
                        mimeType = mimeType,
                        album = album,
                        artist = artist,
                        duration = duration,
                        resolution = resolution
                    )
                    sharedMediaCollection[childId] = item
                    childrenIds.add(childId)
                }
            }
        }
        if (childrenIds.isNotEmpty() || id == "11" || id == "12") {
            sharedMediaCollection[id] = MediaNode.Container(id, name, parentId, uri, childrenIds)
        }
    }
    fun release() {
    }
//    private fun chromecastRequiresTranscoding(uri: Uri): Boolean {
//        // * ac3, eac3, dts, dts-hd - no audio
//        val extractor = MediaExtractor()
//        try {
//            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
//                try {
//                    extractor.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
//                } catch (e: Exception) {
//                    Log.w("Configuration", "MediaExtractor does not support this container (e.g. AVI/WMV). Assuming transcoding is required for URI: $uri")
//                    return true
//                }
//            }
//            val trackCount = extractor.trackCount
//            for (i in 0 until trackCount) {
//                val format = extractor.getTrackFormat(i)
//                val mime = format.getString(MediaFormat.KEY_MIME) ?: "unknown"
//                if (mime.startsWith("audio/")) {
//                    return when (mime) {
//                        "audio/aac",
//                        "audio/mp4",
//                        "audio/mp4a-latm",
//                        "audio/mpeg",
//                        "audio/opus",
//                        "audio/vorbis",
//                        "audio/flac"
//                            -> {
//                            false
//                        }
//                        else -> {
//                            true
//                        }
//                    }
//                }
//            }
//        } catch (e: Exception) {
//            e.printStackTrace()
//        } finally {
//            extractor.release()
//        }
//        return true
//    }
    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
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
    private fun String.urlEscape(): String{
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._!*();:@&=+$%,/?#"
        val processed = this.map { char ->
            if (char in allowedChars || char.isWhitespace()) char else '.'
        }.joinToString("")
        return processed.replace(" ", "%20")
    }
}
