package acab.naiveha.upnpkino

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import java.net.InetAddress

class Configuration(private val context: Context) {
    class metaData(){
        lateinit var uri: Uri
        var name: String = ""
        var type: String = ""
        var size: Long = 0L
        var album: String = ""
        var artist: String = ""
        var duration: String = ""
        var resolution: String = ""
        var children: MutableList<String> = mutableListOf()
    }
    var sharedTree: MutableMap<String, metaData> = emptyMap<String, metaData>().toMutableMap()
    var ip: InetAddress? = null
    var servicePort: Int = 0
    var mediaPort: Int = 0
    val deviceName = "UPnP Kino by naive-HA (${Build.MODEL})"
    val osName = "${System.getProperty("os.name")}/${System.getProperty("os.version")}"
    fun readSharedFolder(onFinished: () -> Unit) {
        Thread {
            sharedTree.clear()
            sharedTree["0"] = metaData()
            sharedTree["0"]?.type = "container"
            sharedTree["0"]?.children?.add("1")
            sharedTree["1"] = metaData()
            sharedTree["1"]?.type = "container"
            sharedTree["1"]?.name = "UPnP Kino"
            sharedTree["1"]?.children?.add("11")
            sharedTree["1"]?.children?.add("12")
            val preferences = Preferences(context)
            preferences.getLocalMovieFolderUri()?.let {
                val rootFolder = DocumentFile.fromTreeUri(context, it)
                sharedTree["11"] = metaData()
                sharedTree["11"]?.uri = rootFolder?.uri!!
                sharedTree["11"]?.name = "Movies"
                sharedTree["11"]?.type = "container"
                discoverFolder("11", Constants.movieExtensions)
            }
            preferences.getLocalMusicFolderUri()?.let {
                val rootFolder = DocumentFile.fromTreeUri(context, it)
                sharedTree["12"] = metaData()
                sharedTree["12"]?.uri = rootFolder?.uri!!
                sharedTree["12"]?.name = "Music"
                sharedTree["12"]?.type = "container"
                discoverFolder("12", Constants.musicExtensions)
            }
            do {
                var foundEmptyFolder = false
                val listOfIDs = sharedTree.keys.toList()
                for (id1 in listOfIDs) {
                    if (sharedTree[id1]?.type == "container" && sharedTree[id1]?.children?.isEmpty() == true) {
                        for (id2 in sharedTree.keys.toList()){
                            sharedTree[id2]?.children?.remove(id1)
                        }
                        sharedTree.remove(id1)
                        foundEmptyFolder = true
                    }
                }
            } while (foundEmptyFolder)
            onFinished()
        }.start()
    }
    private fun discoverFolder(parentID: String, fileExtensions: Set<String>) {
        val rootDocument = sharedTree[parentID]?.uri?.let { DocumentFile.fromTreeUri(context, it) }
        if (rootDocument == null || !rootDocument.isDirectory) {
            return
        }
        for (file in rootDocument.listFiles()) {
            if (file.name?.startsWith(".") == true) {continue}
            var childID: String
            do {childID = generateRandomId(12)} while (sharedTree.keys.contains(childID))
            if (file.isDirectory) {
                sharedTree[childID] = metaData()
                sharedTree[childID]?.uri = file.uri
                sharedTree[childID]?.name = "[+] " + (file.name as String).xmlEscape()
                sharedTree[childID]?.type = "container"
                sharedTree[parentID]?.children?.add(childID)
                discoverFolder(childID, fileExtensions)
                continue
            }
            //else
            val extension = file.name?.substringAfterLast('.', "")?.lowercase()
            if (fileExtensions.contains(extension)) {
                sharedTree[parentID]?.children?.add(childID)
                sharedTree[childID] = metaData()
                sharedTree[childID]?.uri = file.uri
                sharedTree[childID]?.name = (file.name as String).xmlEscape()
                sharedTree[childID]?.type = "item"
                sharedTree[childID]?.size = file.length()
                sharedTree[childID]?.album = ""
                sharedTree[childID]?.artist = ""
                sharedTree[childID]?.duration = "00:00:00"
                sharedTree[childID]?.resolution = "0x0"
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, file.uri)
                } catch (e: Exception) {
                    retriever.release()
                    continue
                }
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
                sharedTree[childID]?.album = album.xmlEscape()
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
                sharedTree[childID]?.artist = artist.xmlEscape()
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                sharedTree[childID]?.duration = formatDuration(durationMs)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "0"
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "0"
                sharedTree[childID]?.resolution = "${width}x${height}"
                retriever.release()
            }
        }
    }
    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }
    private fun String.xmlEscape(): String {
        return this.replace("&", "&amp;amp;")
            .replace("<", "&amp;lt;")
            .replace(">", "&amp;gt;")
            .replace("\"", "&amp;quot;")
            .replace("'", "&amp;apos;")
    }
    private fun generateRandomId(length: Int): String {
        val allowedChars = ('A'..'Z') + ('a'..'z') + ('0'..'9')
        return (1..length).map { allowedChars.random() }.joinToString("")
    }
    fun setServiceServerPort(port: Int) {
        servicePort = port
    }
    fun getServiceServerPort(): Int {
        return servicePort
    }
    fun setMediaServerPort(port: Int) {
        mediaPort = port
    }
    fun getMediaServerPort(): Int {
        return mediaPort
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
}
