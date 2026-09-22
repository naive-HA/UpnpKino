package acab.naiveha.upnpkino

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.nio.ByteBuffer

class MediaCollection(val context: Context, val upnpService: UpnpService) {
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
            val durationMs: Long,
            val resolution: String,
            val videoCodec: Constants.Transcoder.VideoCodec = Constants.Transcoder.VideoCodec.UNKNOWN,
            val videoBitrate: Int? = null,
            val audioCodec: Constants.Transcoder.AudioCodec = Constants.Transcoder.AudioCodec.UNKNOWN,
            val channelCount: Int? = null,
            val isFastStart: Boolean = true,
            val audioTracks: List<AudioTrack> = emptyList(),
            val subtitleTracks: List<SubtitleTrack> = emptyList(),
            val dlnaProfileVideo: List<String> = emptyList(),
            val dlnaProfileAudio: List<String> = emptyList(),
            val dlnaTranscodingProfile: SelectorItem? = null
        ) : MediaNode() {
            override val secondaryLabel: String get() = duration
            override val iconResId: Int
                get() = if (mimeType.startsWith("video/")) R.drawable.ic_video_file else R.drawable.ic_audio_file
            override val isContainer: Boolean get() = false
        }
    }

    data class AudioTrack(
        val index: Int,
        val codec: Constants.Transcoder.AudioCodec,
        val channelCount: Int?,
        val language: String?
    )

    data class SubtitleTrack(
        val index: Int,
        val codec: String,
        val language: String?
    )

    private val repo = UpnpRepository.kinoService

    private val sharedMediaCollection = mutableMapOf<String, MediaNode>()

    suspend fun readSharedFolder() = withContext(Dispatchers.IO) {
        Log.d("MediaCollection", "readSharedFolder started")
        sharedMediaCollection.clear()
        val rootChildren = mutableListOf("1")
        sharedMediaCollection["0"] =
            MediaNode.Container("0", "UPnP Kino", "0", Uri.EMPTY, rootChildren)
        val libraryChildren = mutableListOf<String>()
        sharedMediaCollection["1"] =
            MediaNode.Container("1", "Library", "0", Uri.EMPTY, libraryChildren)
        var noOfFiles = 0
        upnpService.preferences.getLocalMovieFolderUri()?.let { uri ->
            noOfFiles += indexFolder(uri, Constants.movieExtensions)
        }
        upnpService.preferences.getLocalMusicFolderUri()?.let { uri ->
            noOfFiles += indexFolder(uri, Constants.musicExtensions)
        }
        repo.setNoOfSharedMediaFiles(noOfFiles)
        upnpService.preferences.getLocalMovieFolderUri()?.let { uri ->
            val id = "11"
            libraryChildren.add(id)
            discoverFolder(id, "Movies", "1", uri, Constants.movieExtensions)
        }
        upnpService.preferences.getLocalMusicFolderUri()?.let { uri ->
            val id = "12"
            libraryChildren.add(id)
            discoverFolder(id, "Music", "1", uri, Constants.musicExtensions)
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
        //TODO: check if sharedMediaCollection has no MediaNode.Item: stop UpnpService
        Log.d(
            "MediaCollection",
            "readSharedFolder finished: discovered ${sharedMediaCollection.size} nodes"
        )
        withContext(Dispatchers.Main) {
            UpnpRepository.kinoService.setSharedMediaCollection(sharedMediaCollection.toMap())
        }
    }

    private fun indexFolder(uri: Uri, extensions: Set<String>): Int {
        var i = 0
        try {
            val rootDocument = DocumentFile.fromTreeUri(context, uri) ?: return 0
            for (file in rootDocument.listFiles()) {
                if (file.name?.startsWith(".") == true) continue
                if (file.isDirectory) {
                    i += indexFolder(file.uri, extensions)
                } else {
                    val extension = file.name?.substringAfterLast('.', "")?.lowercase()
                    if (extensions.contains(extension)) {
                        i += 1
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MediaCollection", "Error indexing folder: ${uri.path}")
        }
        return i
    }

    private fun discoverFolder(
        id: String,
        name: String,
        parentId: String,
        uri: Uri,
        extensions: Set<String>
    ) {
        val retriever = MediaMetadataRetriever()
        try {
            val childrenIds = mutableListOf<String>()
            val rootDocument = DocumentFile.fromTreeUri(context, uri) ?: return
            for (file in rootDocument.listFiles()) {
                if (file.name?.startsWith(".") == true) continue
                val childId = upnpService.configuration.generateRandomId(12, sharedMediaCollection.keys)
                if (file.isDirectory) {
                    discoverFolder(childId, "[+] " + (file.name as String), id, file.uri, extensions)
                    if (sharedMediaCollection.containsKey(childId)) {
                        childrenIds.add(childId)
                    }
                } else {
                    val extension = file.name?.substringAfterLast('.', "")?.lowercase()
                    if (extensions.contains(extension)) {
                        repo.setNoOfIndexedMediaFiles()
                        var album = ""
                        var artist = ""
                        var duration = "00:00:00"
                        var durationMs = 0L
                        var actualSize = file.length()
                        if (actualSize == Constants.UNKNOWN_FILE_SIZE) actualSize = 0L
                        try {
                            context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                                actualSize = Constants.getFileSize(pfd)
                                Log.v("MediaCollection", "Processing ${file.name}: resolved size=$actualSize (from stat=${pfd.statSize}, doc=${file.length()})")
                            }
                        } catch (e: Exception) {
                            Log.e("MediaCollection", "Error resolving file size: ${file.name} (URI: ${file.uri})")
                        }

                        var videoCodec = Constants.Transcoder.VideoCodec.UNKNOWN
                        var videoProfileIdc: Int? = null
                        var width = 0
                        var height = 0
                        var videoBitrate: Int? = null
                        var audioCodec = Constants.Transcoder.AudioCodec.UNKNOWN
                        var channelCount: Int? = null
                        var audioTracks = listOf<AudioTrack>()
                        var subtitleTracks = listOf<SubtitleTrack>()
                        var isFastStart = true

                        var ffprobeSucceeded = false
                        try {
                            val ffprobeResult = context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                                val result = probeWithFfprobe(pfd)
                                // Detect trailing moov for MP4/MOV
                                if (extension == "mp4" || extension == "mov" || extension == "m4a") {
                                    isFastStart = checkMp4FastStart(pfd)
                                }
                                result
                            }
                            if (ffprobeResult != null) {
                                ffprobeSucceeded = true
                                videoCodec = ffprobeResult.videoCodec
                                videoProfileIdc = ffprobeResult.videoProfileIdc
                                width = ffprobeResult.width
                                height = ffprobeResult.height
                                videoBitrate = ffprobeResult.videoBitrate
                                audioCodec = ffprobeResult.audioCodec
                                channelCount = ffprobeResult.channelCount
                                audioTracks = ffprobeResult.audioTracks
                                subtitleTracks = ffprobeResult.subtitleTracks
                                album = ffprobeResult.album
                                artist = ffprobeResult.artist
                                durationMs = ffprobeResult.durationMs
                                duration = formatDuration(durationMs)
                            }
                        } catch (e: Exception) {
                            Log.e("MediaCollection", "Error probing with ffprobe: ${file.name} (URI: ${file.uri})")
                        }

                        if (!ffprobeSucceeded || album.isEmpty() || artist.isEmpty() || durationMs == 0L || width == 0 || height == 0) {
                            try {
                                context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                                    if (actualSize > 0) {
                                        retriever.setDataSource(pfd.fileDescriptor, 0, actualSize)
                                    } else {
                                        retriever.setDataSource(context, file.uri)
                                    }
                                }
                                if (album.isEmpty()) album = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: "")
                                if (artist.isEmpty()) artist = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "")
                                if (durationMs == 0L) {
                                    durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    duration = formatDuration(durationMs)
                                }
                                if (width == 0) width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                                if (height == 0) height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            } catch (e: Exception) {
                                Log.e("MediaCollection", "Error reading tags with retriever: ${file.name} (URI: ${file.uri})")
                            }
                        }

                        if (!ffprobeSucceeded || videoCodec == Constants.Transcoder.VideoCodec.UNKNOWN || videoProfileIdc == null || width == 0 || height == 0 || videoBitrate == null
                            || audioCodec == Constants.Transcoder.AudioCodec.UNKNOWN || channelCount == null || audioTracks.isEmpty() || subtitleTracks.isEmpty() || durationMs == 0L) {
                            try {
                                val fallbackResult = context.contentResolver.openFileDescriptor(file.uri, "r")?.use { pfd ->
                                    extractWithMediaExtractor(pfd, actualSize, file.uri)
                                }
                                if (fallbackResult != null) {
                                    if (videoCodec == Constants.Transcoder.VideoCodec.UNKNOWN) videoCodec = fallbackResult.videoCodec
                                    if (videoProfileIdc == null) videoProfileIdc = fallbackResult.videoProfileIdc
                                    if (width == 0) width = fallbackResult.width
                                    if (height == 0) height = fallbackResult.height
                                    if (videoBitrate == null) videoBitrate = fallbackResult.videoBitrate
                                    if (audioCodec == Constants.Transcoder.AudioCodec.UNKNOWN) audioCodec = fallbackResult.audioCodec
                                    if (channelCount == null) channelCount = fallbackResult.channelCount
                                    if (audioTracks.isEmpty()) audioTracks = fallbackResult.audioTracks
                                    if (subtitleTracks.isEmpty()) subtitleTracks = fallbackResult.subtitleTracks
                                    if (durationMs == 0L) {
                                        durationMs = fallbackResult.durationMs
                                        duration = formatDuration(durationMs)
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e("MediaCollection", "Error extracting profile params with MediaExtractor: ${file.name} (URI: ${file.uri})")
                            }
                        }

                        if (videoBitrate == null && videoCodec != Constants.Transcoder.VideoCodec.UNKNOWN && audioCodec == Constants.Transcoder.AudioCodec.UNKNOWN) {
                            videoBitrate = computeFallbackBitrate(actualSize, durationMs)
                        }
                        val resolution = "${width}x${height}"
                        val dlnaProfileVideo = buildDlnaVideoTokens(videoCodec, videoProfileIdc, width, height, videoBitrate)
                        val dlnaProfileAudio = buildDlnaAudioTokens(audioCodec, channelCount)

                        var mimeType = Constants.mimeType[extension] ?: "application/octet-stream"
                        if (mimeType == "application/octet-stream") {
                            mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "application/octet-stream"
                        }

                        val item = MediaNode.Item(
                            id = childId,
                            name = (file.name as String),
                            parent = id,
                            uri = file.uri,
                            url = "http://${upnpService.configuration.getIpAddress()}:${upnpService.configuration.getHttpServerPort()}/${upnpService.configuration.uuid}/${childId}/${urlEscape(name)}",
                            size = actualSize,
                            mimeType = mimeType,
                            album = album,
                            artist = artist,
                            duration = duration,
                            durationMs = durationMs,
                            resolution = resolution,
                            videoCodec = videoCodec,
                            videoBitrate = videoBitrate,
                            audioCodec = audioCodec,
                            channelCount = channelCount,
                            isFastStart = isFastStart,
                            audioTracks = audioTracks,
                            subtitleTracks = subtitleTracks,
                            dlnaProfileVideo = dlnaProfileVideo,
                            dlnaProfileAudio = dlnaProfileAudio
                        )
                        sharedMediaCollection[childId] = item
                        childrenIds.add(childId)
                    }
                }
            }
            if (childrenIds.isNotEmpty() || id == "11" || id == "12") {
                sharedMediaCollection[id] = MediaNode.Container(id, name, parentId, uri, childrenIds)
            }
        } finally {
            retriever.release()
        }
    }

    fun urlEscape(s: String): String {
        val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._!*();:@&=+$%,/?#"
        val processed = s.map { char ->
            if (char in allowedChars || char.isWhitespace()) char else '.'
        }.joinToString("")
        return processed.replace(" ", "%20")
    }

    private fun computeFallbackBitrate(fileSizeBytes: Long, durationMs: Long): Int? {
        if (durationMs <= 0) return null
        val durationSec = durationMs / 1000.0
        return ((fileSizeBytes * 8) / durationSec).toInt()
    }

    private fun parseAvcProfileIdc(csd0: ByteBuffer): Int? {
        val buffer = csd0.duplicate()
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        var i = 0
        while (i < bytes.size - 4) {
            val startCodeLen = when {
                bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 0.toByte() && bytes[i + 3] == 1.toByte() -> 4
                bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte() && bytes[i + 2] == 1.toByte() -> 3
                else -> 0
            }
            if (startCodeLen > 0) {
                val nalStart = i + startCodeLen
                if (nalStart + 1 < bytes.size) {
                    val nalType = bytes[nalStart].toInt() and 0x1F
                    if (nalType == 7) {
                        return bytes[nalStart + 1].toInt() and 0xFF
                    }
                }
                i += startCodeLen
            } else {
                i++
            }
        }
        return null
    }

    private fun buildDlnaVideoTokens(
        videoCodec: Constants.Transcoder.VideoCodec,
        videoProfileIdc: Int?,
        width: Int,
        height: Int,
        videoBitrate: Int?
    ): List<String> {
        if (videoCodec != Constants.Transcoder.VideoCodec.H264) return emptyList()
        val tokens = mutableListOf("AVC")
        when (videoProfileIdc) {
            66 -> tokens.add("BL")
            77 -> tokens.add("MP")
            100 -> tokens.add("HP")
        }
        if (videoBitrate != null) {
            when {
                width in 1..720 && height in 1..576 && videoBitrate <= 10_000_000 -> tokens.add("SD")
                width in 1..1920 && height in 1..1152 && videoBitrate <= 20_000_000 -> tokens.add("HD")
            }
        }
        return tokens
    }

    private fun buildDlnaAudioTokens(audioCodec: Constants.Transcoder.AudioCodec, channelCount: Int?): List<String> {
        return when (audioCodec) {
            Constants.Transcoder.AudioCodec.AAC ->
                if ((channelCount ?: 0) >= 6) listOf("AAC", "MULT5") else listOf("AAC")
            Constants.Transcoder.AudioCodec.MP3 -> listOf("MP3")
            else -> emptyList()
        }
    }

    private data class ExtractionResult(
        val videoCodec: Constants.Transcoder.VideoCodec,
        val videoProfileIdc: Int?,
        val width: Int,
        val height: Int,
        val videoBitrate: Int?,
        val audioCodec: Constants.Transcoder.AudioCodec,
        val channelCount: Int?,
        val audioTracks: List<AudioTrack>,
        val subtitleTracks: List<SubtitleTrack>,
        val album: String = "",
        val artist: String = "",
        val durationMs: Long = 0L
    )

    private fun extractWithMediaExtractor(pfd: ParcelFileDescriptor, actualSize: Long, fileUri: Uri): ExtractionResult {
        var videoCodec = Constants.Transcoder.VideoCodec.UNKNOWN
        var videoProfileIdc: Int? = null
        var width = 0
        var height = 0
        var videoBitrate: Int? = null
        var audioCodec = Constants.Transcoder.AudioCodec.UNKNOWN
        var channelCount: Int? = null
        val audioTracks = mutableListOf<AudioTrack>()
        val subtitleTracks = mutableListOf<SubtitleTrack>()
        var durationMs = 0L

        val extractor = MediaExtractor()
        try {
            if (actualSize > 0) {
                extractor.setDataSource(pfd.fileDescriptor, 0, actualSize)
            } else {
                extractor.setDataSource(context, fileUri, null)
            }
            for (trackIndex in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    val trackDurationMs = format.getLong(MediaFormat.KEY_DURATION) / 1000
                    if (trackDurationMs > durationMs) durationMs = trackDurationMs
                }

                when {
                    mime.startsWith("video/") && videoCodec == Constants.Transcoder.VideoCodec.UNKNOWN -> {
                        videoCodec = extractVideoCodec(mime)
                        width = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                        height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                        videoBitrate = if (format.containsKey(MediaFormat.KEY_BIT_RATE)) {
                            format.getInteger(MediaFormat.KEY_BIT_RATE)
                        } else null
                        if (mime == MediaFormat.MIMETYPE_VIDEO_AVC && format.containsKey("csd-0")) {
                            format.getByteBuffer("csd-0")?.let { csd0 ->
                                videoProfileIdc = parseAvcProfileIdc(csd0)
                            }
                        }
                    }
                    mime.startsWith("audio/") -> {
                        val normalized = extractAudioCodec(mime)
                        val trackChannelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null
                        val trackLanguage = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) format.getString(MediaFormat.KEY_LANGUAGE) else null
                        if (audioCodec == Constants.Transcoder.AudioCodec.UNKNOWN) {
                            audioCodec = normalized
                            channelCount = trackChannelCount
                        }
                        audioTracks.add(AudioTrack(trackIndex, normalized, trackChannelCount, trackLanguage))
                    }
                    mime.startsWith("text/") || mime == "application/x-subrip" -> {
                        val trackLanguage = if (format.containsKey(MediaFormat.KEY_LANGUAGE)) format.getString(MediaFormat.KEY_LANGUAGE) else null
                        subtitleTracks.add(SubtitleTrack(trackIndex, mime, trackLanguage))
                    }
                }
            }
        } finally {
            extractor.release()
        }

        return ExtractionResult(videoCodec, videoProfileIdc, width, height, videoBitrate, audioCodec, channelCount, audioTracks, subtitleTracks, durationMs = durationMs)
    }

    private fun probeWithFfprobe(pfd: ParcelFileDescriptor): ExtractionResult? {
        val ffprobePath = FfmpegInstaller.ffprobeBinary(context).absolutePath
        if (!File(ffprobePath).exists()) {
            Log.w("MediaCollection", "ffprobe binary not found at $ffprobePath")
            return null
        }
        return try {
            val process = ProcessBuilder(
                ffprobePath,
                "-hide_banner",
                "-v", "error",
                "-print_format", "json",
                "-show_format",
                "-show_streams",
                "pipe:0"
            ).start()

            val stdoutBuffer = StringBuilder()
            val stderrBuffer = StringBuilder()

            val stdoutThread = Thread {
                process.inputStream.bufferedReader().forEachLine { stdoutBuffer.appendLine(it) }
            }
            val stderrThread = Thread {
                process.errorStream.bufferedReader().forEachLine { stderrBuffer.appendLine(it) }
            }

            stdoutThread.start()
            stderrThread.start()

            val stdinThread = Thread {
                try {
                    FileInputStream(pfd.fileDescriptor).use { input ->
                        process.outputStream.use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (e: Exception) {
                }
            }
            stdinThread.start()

            val finished = process.waitFor(20, TimeUnit.SECONDS)
            stdoutThread.join(1000)
            stderrThread.join(1000)

            if (!finished) {
                process.destroyForcibly()
                Log.w("MediaCollection", "ffprobe timed out")
                return null
            }

            val exitCode = process.exitValue()
            val output = stdoutBuffer.toString()
            val errorOutput = stderrBuffer.toString()

            if (exitCode != 0 || output.isBlank()) {
                Log.w("MediaCollection", "ffprobe exited $exitCode. \nStdout: ${output.take(1000)}\nStderr: ${errorOutput.takeLast(2000)}")
                return null
            }
            Log.v("MediaCollection", "ffprobe successful (JSON length: ${output.length})")
            parseFfprobeJson(output)
        } catch (e: Exception) {
            Log.w("MediaCollection", "ffprobe execution failed: ${e.message}", e)
            null
        }
    }

    private fun parseFfprobeJson(json: String): ExtractionResult {
        val root = JSONObject(json)
        var videoCodec = Constants.Transcoder.VideoCodec.UNKNOWN
        var videoProfileIdc: Int? = null
        var width = 0
        var height = 0
        var videoBitrate: Int? = null
        var audioCodec = Constants.Transcoder.AudioCodec.UNKNOWN
        var channelCount: Int? = null
        val audioTracks = mutableListOf<AudioTrack>()
        val subtitleTracks = mutableListOf<SubtitleTrack>()

        val streams = root.optJSONArray("streams") ?: JSONArray()
        for (i in 0 until streams.length()) {
            val stream = streams.optJSONObject(i) ?: continue
            val index = stream.optInt("index", i)
            val codecName = stream.optString("codec_name", "")
            val language = stream.optJSONObject("tags")?.optString("language")?.takeIf { it.isNotBlank() }
            when (stream.optString("codec_type", "")) {
                "video" -> if (videoCodec == Constants.Transcoder.VideoCodec.UNKNOWN) {
                    videoCodec = extractVideoCodec(codecName)
                    width = stream.optInt("width", 0)
                    height = stream.optInt("height", 0)
                    videoBitrate = stream.optString("bit_rate").toIntOrNull()
                    videoProfileIdc = when (stream.optString("profile")) {
                        "Baseline" -> 66
                        "Main" -> 77
                        "High" -> 100
                        else -> null
                    }
                }
                "audio" -> {
                    val trackChannelCount = if (stream.has("channels")) stream.optInt("channels") else null
                    val normalized = extractAudioCodec(codecName)
                    if (audioCodec == Constants.Transcoder.AudioCodec.UNKNOWN) {
                        audioCodec = normalized
                        channelCount = trackChannelCount
                    }
                    audioTracks.add(AudioTrack(index, normalized, trackChannelCount, language))
                }
                "subtitle" -> {
                    val subtitleCodecMime = when (codecName) {
                        "subrip" -> "application/x-subrip"
                        "webvtt" -> MediaFormat.MIMETYPE_TEXT_VTT
                        else -> codecName
                    }
                    subtitleTracks.add(SubtitleTrack(index, subtitleCodecMime, language))
                }
            }
        }

        val formatObj = root.optJSONObject("format")
        val formatTags = formatObj?.optJSONObject("tags")
        val album = formatTags?.optString("album", "") ?: ""
        val artist = formatTags?.optString("artist", "") ?: ""
        val durationMs = formatObj?.optString("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() } ?: 0L

        return ExtractionResult(
            videoCodec, videoProfileIdc, width, height, videoBitrate, audioCodec, channelCount,
            audioTracks, subtitleTracks, album, artist, durationMs
        )
    }

    private fun formatDuration(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    /**
     * Inspects an MP4/MOV file's atoms to see if the 'moov' atom appears before 'mdat'.
     * If 'mdat' appears first, the file is unoptimized for streaming/piping.
     */
    fun resolveSeekByTime(item: MediaNode.Item, timeMs: Long): Pair<Long, Long> {
        if (item.durationMs <= 0) return Pair(0L, 0L)
        val time = timeMs.coerceIn(0, item.durationMs)
        val ratio = time.toDouble() / item.durationMs.toDouble()
        val byteOffset = (item.size * ratio).toLong().coerceIn(0, item.size - 1)
        return Pair(time, byteOffset)
    }

    fun resolveSeekByByte(item: MediaNode.Item, startByte: Long): Pair<Long, Long> {
        if (item.size <= 0) return Pair(0L, 0L)
        val byteOffset = startByte.coerceIn(0, item.size - 1)
        val ratio = byteOffset.toDouble() / item.size.toDouble()
        val timeMs = (item.durationMs * ratio).toLong().coerceIn(0, item.durationMs)
        return Pair(timeMs, byteOffset)
    }

    fun extractVideoCodec(mime: String?): Constants.Transcoder.VideoCodec {
        if (mime == null) return Constants.Transcoder.VideoCodec.UNKNOWN
        val lower = mime.lowercase()
        // 1. Priority variant matching
        if (lower.contains("avc") || lower.contains("h264")) return Constants.Transcoder.VideoCodec.H264
        if (lower.contains("hevc") || lower.contains("h265")) return Constants.Transcoder.VideoCodec.HEVC
        if (lower.contains("wmv") || lower.contains("vc1")) return Constants.Transcoder.VideoCodec.WMV3
        
        // 2. Token extraction and Enum matching
        val token = lower.substringAfterLast("/").removePrefix("x-").removePrefix("vnd.ffmpeg.")
        return Constants.Transcoder.VideoCodec.entries.find { 
            it.codecId == token || it.name.lowercase() == token || lower.contains(it.codecId)
        } ?: Constants.Transcoder.VideoCodec.UNKNOWN
    }

    fun extractAudioCodec(mime: String?): Constants.Transcoder.AudioCodec {
        if (mime == null) return Constants.Transcoder.AudioCodec.UNKNOWN
        val lower = mime.lowercase()
        // 1. Priority variant matching
        if (lower.contains("aac") || lower.contains("mp4a-latm")) return Constants.Transcoder.AudioCodec.AAC
        if (lower.contains("mp3") || lower.contains("mpeg")) return Constants.Transcoder.AudioCodec.MP3
        if (lower.contains("ac3")) return Constants.Transcoder.AudioCodec.AC3
        if (lower.contains("dts") || lower.contains("dca")) return Constants.Transcoder.AudioCodec.DCA
        
        // 2. Token extraction and Enum matching
        val token = lower.substringAfterLast("/").removePrefix("x-").removePrefix("vnd.ffmpeg.")
        return Constants.Transcoder.AudioCodec.entries.find { 
            it.codecId == token || it.name.lowercase() == token || lower.contains(it.codecId)
        } ?: Constants.Transcoder.AudioCodec.UNKNOWN
    }

    private fun checkMp4FastStart(pfd: ParcelFileDescriptor): Boolean {
        try {
            val fis = FileInputStream(pfd.fileDescriptor)
            val buffer = ByteArray(8)
            var offset = 0L
            var moovPos = -1L
            var mdatPos = -1L
            
            // We only care about the first ~1MB for this optimization check
            while (offset < 1024 * 1024) {
                val read = fis.read(buffer)
                if (read < 8) break
                
                val size = ((buffer[0].toInt() and 0xFF).toLong() shl 24) or
                           ((buffer[1].toInt() and 0xFF).toLong() shl 16) or
                           ((buffer[2].toInt() and 0xFF).toLong() shl 8) or
                           (buffer[3].toInt() and 0xFF).toLong()
                
                val type = String(buffer, 4, 4)
                
                if (type == "moov") {
                    moovPos = offset
                    break // Found it!
                }
                if (type == "mdat") {
                    mdatPos = offset
                }
                
                if (size < 8) break // Invalid atom
                
                // Skip the rest of the atom
                val skip = size - 8
                fis.skip(skip)
                offset += size
            }
            
            // If we found mdat but not moov yet, it's at the end.
            if (mdatPos != -1L && (moovPos == -1L || moovPos > mdatPos)) return false
            return true
        } catch (e: Exception) {
            return true // Default to safe if check fails
        }
    }
}
