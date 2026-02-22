package acab.naiveha.upnpkino

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import java.net.InetAddress
import kotlin.text.contains

class Configuration(private val context: Context) {
    class metaData(){
        lateinit var uri: Uri
        var name: String = ""
        var type: String = ""
        var size: Long = 0
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
            val preferences = Preferences(context)
            preferences.getFolderUri()?.let {
                sharedTree.clear()
                val rootFolder = DocumentFile.fromTreeUri(context, it)
                sharedTree["0"] = metaData()
                sharedTree["0"]?.uri = rootFolder?.uri!!
                sharedTree["0"]?.name = rootFolder?.name!!
                sharedTree["0"]?.type = "container"
                var rootFolderId: String
                do {rootFolderId = generateRandomId(12)} while (sharedTree.keys.contains(rootFolderId))
                sharedTree["0"]?.children?.add(rootFolderId)
                sharedTree[rootFolderId] = metaData()
                sharedTree[rootFolderId]?.uri = rootFolder?.uri!!
                sharedTree[rootFolderId]?.name = rootFolder?.name!!
                sharedTree[rootFolderId]?.type = "container"
                discoverFolder(rootFolderId)
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

    private fun discoverFolder(parentID: String) {
        val rootDocument = sharedTree[parentID]?.uri?.let { DocumentFile.fromTreeUri(context, it) }
        if (rootDocument == null || rootDocument.isDirectory == false) {
            return
        }
        for (file in rootDocument.listFiles()) {
            if (file.name?.startsWith(".") == true) {
                continue
            }
            var childID: String
            do {childID = generateRandomId(12)} while (sharedTree.keys.contains(childID))
            if (file.isDirectory) {
                sharedTree[childID] = metaData()
                sharedTree[childID]?.uri = file.uri
                sharedTree[childID]?.name = "[+] " + file.name as String
                sharedTree[childID]?.type = "container"
                sharedTree[parentID]?.children?.add(childID)
                discoverFolder(childID)
            } else {
                val extension = file.name?.substringAfterLast('.', "")?.lowercase()
                if (Constants.videoExtensions.contains(extension)) {
                    sharedTree[childID] = metaData()
                    sharedTree[childID]?.uri = file.uri
                    sharedTree[childID]?.name = file.name as String
                    sharedTree[childID]?.type = "item"
                    sharedTree[childID]?.size = file.length()
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, file.uri)
                        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        sharedTree[childID]?.duration = formatDuration(durationMs)
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                        sharedTree[childID]?.resolution = if (width != null && height != null) "${width}x${height}" else "0x0"
                    } catch (e: Exception) {
                        sharedTree[childID]?.duration = "00:00:00"
                        sharedTree[childID]?.resolution = "0x0"
                    } finally {
                        retriever.release()
                    }
                    sharedTree[parentID]?.children?.add(childID)
                }
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
