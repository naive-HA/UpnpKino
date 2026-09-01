package acab.naiveha.upnpkino

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.time.Duration.Companion.milliseconds
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ProgressHolder
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.documentfile.provider.DocumentFile
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.effect.Presentation
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import kotlin.math.min

class TranscoderController(val context: Context, val upnpService: UpnpService) {
    /**
     * Finds a sample video item, prioritizing MKV/HEVC if possible.
     */
    private fun getSampleVideoItem(): MediaCollection.MediaNode.Item? {
        val collection = UpnpRepository.kinoService.sharedMediaCollection.value
        val items = collection.values.filterIsInstance<MediaCollection.MediaNode.Item>()

        // Prioritize MKV/x265 as requested
        return items.find {
            it.name.endsWith(".mkv", ignoreCase = true) &&
                    (it.videoCodec?.contains("hevc", ignoreCase = true) == true ||
                     it.videoCodec?.contains("h265", ignoreCase = true) == true ||
                     it.name.contains("x265", ignoreCase = true) ||
                     it.name.contains("hevc", ignoreCase = true))
        } ?: items.find { it.name.endsWith(".mkv", ignoreCase = true) }
        ?: items.firstOrNull { it.mimeType.startsWith("video/") }
    }

    private fun getSampleVideoUri(): Uri? = getSampleVideoItem()?.uri

    /**
     * Finds a real on-device media item already encoded in [codec] -- the
     * shortest one available -- by walking the same indexed library the DLNA
     * server itself serves from (UpnpRepository.kinoService.sharedMediaCollection).
     * Used by Gate 1b (plan Section 5.2) so both codec legs get a real
     * decode source without needing a bundled/manually-pushed clip --
     * whatever's actually sitting in the user's shared folder is fair game,
     * for either codec.
     *
     * Depends on MediaCollection tagging videoCodec correctly per item --
     * HEVC items were silently coming back with videoCodec=null before the
     * parseFfprobeJson fix landed alongside this (ffprobe's JSON path only
     * ever mapped "h264"; "hevc" and everything else fell through to null).
     * If this stops finding HEVC content that's clearly in the library,
     * check that fix is still in place before assuming the library has none.
     */
    private fun findSampleItemFor(codec: Constants.Transcoder.VideoCodec): MediaCollection.MediaNode.Item? {
        val targetMime = when (codec) {
            Constants.Transcoder.VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            Constants.Transcoder.VideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        }
        val candidates = UpnpRepository.kinoService.sharedMediaCollection.value.values
            .filterIsInstance<MediaCollection.MediaNode.Item>()
            .filter { it.videoCodec == targetMime }

        // Prefer the shortest known-duration candidate under
        // GATE_1B_PREFERRED_MAX_SOURCE_DURATION_MS: this is a real decode of a real
        // file that runs on every capability check, so an unlucky
        // first-in-iteration-order pick (a two-hour movie) shouldn't become the
        // every-startup norm just because it was indexed first. durationMs==0 means
        // duration wasn't resolved (possible via the MediaMetadataRetriever
        // fallback path) -- sorted last along with anything over the ceiling, not
        // first, so "unknown" can't masquerade as "shortest". Nothing here is
        // excluded outright: if every candidate is long or unknown, the first one
        // (by original order) still gets used -- GATE_1B_TEST_DURATION_SECONDS in
        // testVideoDecodeEncodeSurfaceModeGate1b keeps that fallback bounded.
        return candidates
            .sortedBy { item ->
                item.durationMs.takeIf { it in 1..GATE_1B_PREFERRED_MAX_SOURCE_DURATION_MS } ?: Long.MAX_VALUE
            }
            .firstOrNull()
    }

    companion object {
        private const val TAG = "TranscoderController"
        private const val RETRY_DELAY_MS = 500L
        private const val CAPABILITY_SCHEMA_VERSION = 1

        /**
         * Soft ceiling (ms) findSampleItemFor prefers when picking a Gate 1b
         * source: this is a real capability check that runs on every service
         * start, so it shouldn't default to whatever multi-hour file happens
         * to be indexed first. Not a hard requirement -- see findSampleItemFor.
         */
        private const val GATE_1B_PREFERRED_MAX_SOURCE_DURATION_MS = 10 * 60 * 1000L // 10 minutes

        /**
         * Output duration cap for Gate 1b's ffmpeg command (mirrors Gate 0's
         * 2s Media3 clip). The real reason this matters: findSampleItemFor's
         * ceiling above is only a preference, not a guarantee -- a library
         * with nothing short still falls back to its shortest available real
         * file, however long that is. Without a -t bound here, that fallback
         * case would run unbounded until runProcessCapture's 15s timeout
         * fired and got misreported as HANG (a real, scary finding per plan
         * Section 5.1's framing) when it was actually just "still decoding a
         * two-hour movie" -- a false alarm, not a hang.
         */
        private const val GATE_1B_TEST_DURATION_SECONDS = 5

        /** Cheap, static inventory of what the ffmpeg binary was compiled with. */
        data class RuntimeCapabilities(
            val compiledVideoEncoders: Set<Constants.Transcoder.VideoCodec>,
            val compiledAudioEncoders: Set<Constants.Transcoder.AudioCodec>,
            val compiledMuxers: Set<Constants.Transcoder.Container>
        )

        /**
         * Full result of a capability check, tagged with the identity of the
         * binary/OS it was measured against so a cached copy can be trusted
         * or invalidated later — see [isValidCapabilities].
         */
        data class CapabilitySnapshot(
            val appVersionCode: Long,
            val ffmpegBinarySize: Long,
            val ffmpegBinaryMtime: Long,
            val osFingerprint: String,
            val checkedAtMillis: Long,
            val compiled: RuntimeCapabilities,
            /** Resolutions actually confirmed working per video codec (subset of what's compiled). */
            val verifiedVideo: Map<Constants.Transcoder.VideoCodec, Set<Constants.Transcoder.Resolution>>,
            /** Audio codecs actually confirmed working (subset of what's compiled). */
            val verifiedAudio: Set<Constants.Transcoder.AudioCodec>
        ) {
            fun canEncode(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): Boolean =
                resolution in (verifiedVideo[codec] ?: emptySet())

            fun canEncodeAudio(codec: Constants.Transcoder.AudioCodec): Boolean =
                codec in verifiedAudio

            fun toJson(): String {
                val root = JSONObject()
                root.put("schemaVersion", CAPABILITY_SCHEMA_VERSION)
                root.put("appVersionCode", appVersionCode)
                root.put("ffmpegBinarySize", ffmpegBinarySize)
                root.put("ffmpegBinaryMtime", ffmpegBinaryMtime)
                root.put("osFingerprint", osFingerprint)
                root.put("checkedAtMillis", checkedAtMillis)
                root.put("compiledVideoEncoders", JSONArray(compiled.compiledVideoEncoders.map { it.name }))
                root.put("compiledAudioEncoders", JSONArray(compiled.compiledAudioEncoders.map { it.name }))
                root.put("compiledMuxers", JSONArray(compiled.compiledMuxers.map { it.name }))
                val verifiedVideoJson = JSONObject()
                verifiedVideo.forEach { (codec, resolutions) ->
                    verifiedVideoJson.put(codec.name, JSONArray(resolutions.map { it.name }))
                }
                root.put("verifiedVideo", verifiedVideoJson)
                root.put("verifiedAudio", JSONArray(verifiedAudio.map { it.name }))
                return root.toString()
            }

            companion object {
                /** Returns null (rather than throwing) on any parse problem — caller just re-runs the check. */
                fun fromJson(json: String): CapabilitySnapshot? = try {
                    val root = JSONObject(json)
                    check(root.optInt("schemaVersion") == CAPABILITY_SCHEMA_VERSION) { "schema version mismatch" }

                    fun JSONArray.strings() = (0 until length()).map { getString(it) }

                    val compiledVideo = root.getJSONArray("compiledVideoEncoders").strings()
                        .mapNotNull { runCatching { Constants.Transcoder.VideoCodec.valueOf(it) }.getOrNull() }.toSet()
                    val compiledAudio = root.getJSONArray("compiledAudioEncoders").strings()
                        .mapNotNull { runCatching { Constants.Transcoder.AudioCodec.valueOf(it) }.getOrNull() }.toSet()
                    val compiledMuxers = root.getJSONArray("compiledMuxers").strings()
                        .mapNotNull { runCatching { Constants.Transcoder.Container.valueOf(it) }.getOrNull() }.toSet()

                    val verifiedVideoJson = root.getJSONObject("verifiedVideo")
                    val verifiedVideo = mutableMapOf<Constants.Transcoder.VideoCodec, Set<Constants.Transcoder.Resolution>>()
                    verifiedVideoJson.keys().forEach { key ->
                        val codec = runCatching { Constants.Transcoder.VideoCodec.valueOf(key) }.getOrNull() ?: return@forEach
                        val resolutions = verifiedVideoJson.getJSONArray(key).strings()
                            .mapNotNull { runCatching { Constants.Transcoder.Resolution.valueOf(it) }.getOrNull() }.toSet()
                        verifiedVideo[codec] = resolutions
                    }
                    val verifiedAudio = root.getJSONArray("verifiedAudio").strings()
                        .mapNotNull { runCatching { Constants.Transcoder.AudioCodec.valueOf(it) }.getOrNull() }.toSet()

                    CapabilitySnapshot(
                        appVersionCode = root.getLong("appVersionCode"),
                        ffmpegBinarySize = root.getLong("ffmpegBinarySize"),
                        ffmpegBinaryMtime = root.getLong("ffmpegBinaryMtime"),
                        osFingerprint = root.getString("osFingerprint"),
                        checkedAtMillis = root.getLong("checkedAtMillis"),
                        compiled = RuntimeCapabilities(compiledVideo, compiledAudio, compiledMuxers),
                        verifiedVideo = verifiedVideo,
                        verifiedAudio = verifiedAudio
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Discarding unreadable cached capability snapshot: ${e.message}")
                    null
                }
            }
        }
    }

    private val repo = UpnpRepository.transcoder
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ffmpegFile get() = FfmpegInstaller.ffmpegBinary(context)
    private val ffmpegPath get() = ffmpegFile.absolutePath
    private val ffprobeFile get() = FfmpegInstaller.ffprobeBinary(context)
    private val ffprobePath get() = ffprobeFile.absolutePath
    private val workDir get() = context.cacheDir

    /** Device/binary transcode capabilities. Null until the init check completes. */
    @Volatile
    var capabilities: CapabilitySnapshot? = null
        private set

    init {
        scope.launch {
            try {
                Log.d(TAG, "Running fresh transcode capability check")
                
                // Gate 0: Media3 Transformer native resolution transcode
//                val sampleItem = getSampleVideoItem()
//                if (sampleItem != null) {
//                    Log.d(TAG, "GATE 0: Starting Media3 Transformer health check")
//                    gate0(sampleItem)
//                    Log.d(TAG, "GATE 0: Finished Media3 Transformer health check")
//                } else {
//                    Log.d(TAG, "GATE 0 check not conducted due to missing video file")
//                }

                // Gate 1: FFmpeg + Media3 Transformer health checks
                Log.d(TAG, "GATE 1: Starting ffmpeg and Media3 Transformer health checks")
                val encoders = runProcessCapture(listOf(ffmpegPath, "-hide_banner", "-encoders")).stdout
                val muxers = runProcessCapture(listOf(ffmpegPath, "-hide_banner", "-muxers")).stdout
                
                val compiledVideo = Constants.Transcoder.VideoCodec.entries
                    .filter { Regex("\\b${Regex.escape(it.ffmpegEncoder)}\\b").containsMatchIn(encoders) }.toSet()
                val compiledAudio = Constants.Transcoder.AudioCodec.entries
                    .filter { Regex("\\b${Regex.escape(it.ffmpegEncoder)}\\b").containsMatchIn(encoders) }.toSet()
                val compiledMuxers = Constants.Transcoder.Container.entries
                    .filter { Regex("\\b${Regex.escape(it.ffmpegMuxer)}\\b").containsMatchIn(muxers) }.toSet()

                // Gate 1a: synthetic samples (H.264, HEVC, VP9, etc.)
                runGate1aWithSamples()
                Log.d(TAG, "Gate 1a finished")

                // Gate 1b: real library decode -> Surface -> real hardware encode
                for (codec in compiledVideo) {
                    Log.d(TAG, "GATE 1b: Checking $codec")
                    var gate1bItem = findSampleItemFor(codec)
                    
                    // HACK: If no library item, try to find a synthetic sample from Gate 1a
                    if (gate1bItem == null) {
                        val samplesDir = File(workDir, "gate1a_samples")
                        val sampleFile = samplesDir.listFiles()?.find { 
                            it.name.contains(codec.name, ignoreCase = true) && !it.name.contains("4k") 
                        }
                        if (sampleFile != null) {
                            Log.d(TAG, "GATE 1b: Using synthetic sample for $codec: ${sampleFile.name}")
                            gate1bItem = MediaCollection.MediaNode.Item(
                                id = "synthetic", name = sampleFile.name, parent = "",
                                uri = Uri.fromFile(sampleFile), url = "", size = sampleFile.length(),
                                mimeType = "video/mp4", album = "", artist = "", duration = "2s",
                                durationMs = 2000, resolution = "1920x1080", 
                                videoCodec = if (codec == Constants.Transcoder.VideoCodec.H264) "video/avc" else "video/hevc"
                            )
                        }
                    }

                    if (gate1bItem != null) {
                        Log.d(TAG, "GATE 1b: $codec -- using source '${gate1bItem.name}'")
                        val outcome = testVideoDecodeEncodeSurfaceModeGate1b(codec, gate1bItem)
                        Log.d(TAG, "GATE 1b RESULT: Codec $codec via real decode->Surface->encode: $outcome")
                    } else {
                        Log.w(TAG, "GATE 1b: skipping $codec -- no suitable item found.")
                    }
                    delay(1000.milliseconds)
                }

                Log.w(TAG, "Capability check sequence complete")

            } catch (e: Exception) {
                Log.e(TAG, "Capability check crashed: ${e.message}", e)
            } finally {
                Log.e(TAG, "TranscoderController init scope ending")
            }
        }
//        scope.launch {
//            repo.selectedMediaFileId.collect { selectedMediaFileId ->
//                Log.d(TAG, "Selected media file changed: $selectedMediaFileId")
//                if (selectedMediaFileId == null) {
//                    resetTranscoderActivity()
//                } else {
//                    //decide best video and audio codec
//                }
//            }
//        }
//        scope.launch {
//            repo.transcodingFlag.collect { transcodingFlag ->
//            }
//        }
//        scope.launch {
//            repo.isTranscoderActivityVisible.collect { isTranscoderActivityVisible ->
//            }
//        }
    }


    /**
     * True if [cached] was measured against the same ffmpeg binary and OS
     * build this device is running right now. Any mismatch means the
     * cached result no longer describes reality and must be re-measured:
     *   - app/binary changed (new app version, or a locally rebuilt binary
     *     during development without a version bump — caught via the
     *     binary file's own size + mtime, not just the app version code)
     *   - OS/firmware changed (an OEM update can change codec behavior)
     */
    private fun isValidCapabilities(cached: CapabilitySnapshot): Boolean {
        val sameApp = cached.appVersionCode == currentAppVersionCode()
        val sameBinary = cached.ffmpegBinarySize == ffmpegFile.length() &&
            cached.ffmpegBinaryMtime == ffmpegFile.lastModified()
        val sameOs = cached.osFingerprint == Build.FINGERPRINT

        if (!sameApp) Log.d(TAG, "Cached capabilities invalid: app version changed")
        if (!sameBinary) Log.d(TAG, "Cached capabilities invalid: ffmpeg binary changed")
        if (!sameOs) Log.d(TAG, "Cached capabilities invalid: OS build fingerprint changed")

        return sameApp && sameBinary && sameOs
    }

    @OptIn(UnstableApi::class)
    private suspend fun gate0(item: MediaCollection.MediaNode.Item) {
        val startTime = System.currentTimeMillis()

        // Clear cache at the start
        Log.d(TAG, "Clearing cache directory: ${workDir.absolutePath}")
        withContext(Dispatchers.IO) {
            workDir.listFiles()?.forEach { it.deleteRecursively() }
        }

        val collection = UpnpRepository.kinoService.sharedMediaCollection.value
        val parentNode = collection[item.parent] as? MediaCollection.MediaNode.Container
        if (parentNode == null) {
            Log.e(TAG, "Segmented Transcode: Could not find parent folder for ${item.name}")
            return
        }

        val parentDoc = DocumentFile.fromTreeUri(context, parentNode.uri)
        if (parentDoc == null || !parentDoc.isDirectory) {
            Log.e(TAG, "Segmented Transcode: Parent folder URI is invalid or not a directory: ${parentNode.uri}")
            return
        }

        val segmentDurationMs = 30_000L
        val totalDurationMs = item.durationMs
        val segmentsCount = if (totalDurationMs > 0) ((totalDurationMs + segmentDurationMs - 1) / segmentDurationMs).toInt() else 0
        val tsFiles = mutableListOf<File>()

        Log.d(TAG, "Starting segmented transcode for ${item.name}. Total duration: ${totalDurationMs}ms, Segments: $segmentsCount")

        if (segmentsCount == 0) {
            Log.e(TAG, "Segmented Transcode: Item duration is 0 or unknown.")
            return
        }

        val resParts = item.resolution.split("x")
        val inputHeight = if (resParts.size == 2) resParts[1].toIntOrNull() ?: 720 else 720
        
        // Pick an informed bitrate based on resolution constants
        val targetResolution = Constants.Transcoder.Resolution.entries
            .find { it.maxHeight >= inputHeight } ?: Constants.Transcoder.Resolution.UHD_4K
        val targetBitrateBps = targetResolution.targetBitrateKbps * 1000

        var allSegmentsSuccessful = true
        for (i in 0 until segmentsCount) {
            Log.d(TAG, "Segmented Transcode Progress: $i / $segmentsCount segments")
            val startMs = i * segmentDurationMs
            val endMs = min((i + 1) * segmentDurationMs, totalDurationMs)
            val chunkMp4 = File(workDir, "chunk_$i.mp4")
            if (chunkMp4.exists()) withContext(Dispatchers.IO) { chunkMp4.delete() }

            val transformerResult = withContext(Dispatchers.Main) {
                suspendCancellableCoroutine<Boolean> { continuation ->
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .setEncoderFactory(
                            DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(
                                    VideoEncoderSettings.Builder()
                                        .setBitrate(targetBitrateBps)
                                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                                        .build()
                                )
                                .build()
                        )
                        .build()

                    val listener = object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            Log.e(TAG, "Segment $i FAILED: ${exportException.message}", exportException)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    }
                    transformer.addListener(listener)

                    try {
                        val mediaItem = MediaItem.Builder()
                            .setUri(item.uri)
                            .setMimeType(item.mimeType)
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(startMs)
                                    .setEndPositionMs(endMs)
                                    .build()
                            )
                            .build()
                        val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                            .setRemoveAudio(true)
                            .setEffects(
                                Effects(
                                    listOf<AudioProcessor>(),
                                    listOf<Effect>(Presentation.createForHeight(inputHeight))
                                )
                            )
                            .build()

                        transformer.start(editedMediaItem, chunkMp4.absolutePath)

                        // Same fix as gate1a(): don't call transformer.cancel() inline
                        // inside invokeOnCancellation. withTimeoutOrNull callers of this
                        // block block until this handler returns; if the encoder is
                        // wedged (the case a timeout exists to catch), cancel() can
                        // block right along with it. Detach it and don't wait.
                        continuation.invokeOnCancellation {
                            CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch {
                                try {
                                    transformer.cancel()
                                } catch (e: Exception) {
                                    Log.e(TAG, "Segment $i: transformer.cancel() failed during timeout recovery: ${e.message}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Segment $i Setup Error: ${e.message}", e)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            }

            var segmentSuccess = transformerResult
            if (!segmentSuccess) {
                Log.w(TAG, "Segment $i: Transformer failed (Source error?), attempting FFmpeg fallback")
                segmentSuccess = runFfmpegFallbackSegment(
                    index = i,
                    startMs = startMs,
                    endMs = endMs,
                    sourceUri = item.uri,
                    targetCodec = Constants.Transcoder.VideoCodec.H264,
                    targetHeight = inputHeight,
                    targetBitrateBps = targetBitrateBps,
                    outputFile = chunkMp4
                )
            }

            if (segmentSuccess) {
                // Convert to TS immediately for reliable concatenation
                val tsFile = File(workDir, "chunk_$i.ts")
                val convertArgs = listOf(
                    ffmpegPath, "-y", "-hide_banner",
                    "-i", chunkMp4.absolutePath,
                    "-c", "copy",
                    "-bsf:v", "h264_mp4toannexb",
                    "-f", "mpegts", tsFile.absolutePath
                )
                val convertResult = runProcessCapture(convertArgs)
                if (convertResult.exitCode == 0) {
                    tsFiles.add(tsFile)
                    withContext(Dispatchers.IO) { chunkMp4.delete() }
                } else {
                    Log.e(TAG, "Segment $i: Failed to convert to TS. Stderr: ${convertResult.stderr}")
                    allSegmentsSuccessful = false
                    break
                }
                
                // Small delay between segments to allow hardware/buffers to breathe
                delay(500.milliseconds)
            } else {
                allSegmentsSuccessful = false
                Log.e(TAG, "Segmented Transcode: Stopping due to failure in segment $i")
                break
            }
        }

        val outputMp4 = File(workDir, "segmented_output.mp4")
        if (outputMp4.exists()) withContext(Dispatchers.IO) { outputMp4.delete() }

        if (tsFiles.isNotEmpty()) {
            val finalOutputName = if (allSegmentsSuccessful) "output.mp4" else "partial_output.mp4"
            Log.d(TAG, "Segmented Transcode: Concatenating ${tsFiles.size} TS chunks into $finalOutputName")

            val combinedTs = File(workDir, "combined.ts")
            try {
                withContext(Dispatchers.IO) {
                    combinedTs.outputStream().use { output ->
                        tsFiles.forEach { tsFile ->
                            tsFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                    }
                }

                // Remux combined TS to final MP4
                val remuxArgs = listOf(
                    ffmpegPath, "-y", "-hide_banner",
                    "-i", combinedTs.absolutePath,
                    "-c", "copy", outputMp4.absolutePath
                )
                val remuxResult = runProcessCapture(remuxArgs, timeoutMs = 120_000L, logStderrLive = true)

                if (remuxResult.exitCode == 0 && outputMp4.exists() && outputMp4.length() > 0) {
                    Log.d(TAG, "Segmented Transcode: Concatenation SUCCESS. Final size: ${outputMp4.length()}")
                    
                    withContext(Dispatchers.IO) {
                        try {
                            parentDoc.findFile(finalOutputName)?.delete()
                            val outputDoc = parentDoc.createFile("video/mp4", finalOutputName)
                            if (outputDoc != null) {
                                context.contentResolver.openFileDescriptor(outputDoc.uri, "wt")?.use { pfd ->
                                    FileOutputStream(pfd.fileDescriptor).use { outputStream ->
                                        FileInputStream(outputMp4).use { inputStream ->
                                            inputStream.copyTo(outputStream)
                                        }
                                    }
                                }
                                Log.d(TAG, "Segmented Transcode: Final file saved to ${outputDoc.uri}, size: ${outputDoc.length()}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Segmented Transcode: Error moving file to destination: ${e.message}", e)
                        }
                    }
                } else {
                    Log.e(TAG, "Segmented Transcode: Remux FAILED: exitCode=${remuxResult.exitCode}")
                }
            } finally {
                withContext(Dispatchers.IO) {
                    if (combinedTs.exists()) combinedTs.delete()
                    tsFiles.forEach { if (it.exists()) it.delete() }
                    if (outputMp4.exists()) outputMp4.delete()
                }
            }
        }

        val totalDurationSeconds = (System.currentTimeMillis() - startTime) / 1000.0
        Log.d(TAG, "Segmented Transcode PROCESS FINISHED in $totalDurationSeconds seconds. Success: $allSegmentsSuccessful")
    }

    /**
     * Stage 0/4: pipeline sanity check with NO MediaCodec involved at all.
     * rawvideo is a trivial dependency-free passthrough "encoder" (no real
     * compression). Output goes to -f null (discarded) rather than a real
     * container: matroska/mp4 have their own codec-compatibility rules
     * (e.g. matroska refuses raw RGB without -allow_raw_vfw) that are a
     * different question from "does the pipeline itself work" and would
     * give a false negative here. If demux->decode->rawvideo hangs even
     * with nothing to mux, the problem isn't h264_mediacodec/hevc_mediacodec
     * specifically — it's something more fundamental in how this process
     * is being run.
     */
    private suspend fun debugEncodeRawvideoSanityCheck(resolution: Constants.Transcoder.Resolution): ProcessOutput {
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner", "-v", "info",
            "-i", "pipe:0", "-an", "-sn",
            "-vf", "scale=${resolution.maxWidth}:${testHeightFor(resolution)}",
            "-frames:v", "1", "-c:v", "rawvideo", "-f", "null", "-"
        )
        return runProcessCapture(args, timeoutMs = 8_000L, logStderrLive = true, stdinSource = getSampleVideoUri())
    }

    /**
     * Explicit H.264/HEVC level for [resolution]. Without this, ffmpeg's
     * mediacodec wrapper doesn't appear to auto-select a level at all --
     * Android's own CCodec log showed "level = 1" (H.264 Level 1, meant
     * for roughly QCIF content) being requested regardless of resolution
     * or bitrate, which is the likely reason HD_1080 gets rejected outright
     * by the hardware encoder's own validation (Level 1 can't legally carry
     * a 1920x1088 frame). Values below are generous on purpose (comfortably
     * cover the resolution + our target bitrate), not minimal -- declaring
     * more capability than used is spec-legal.
     *
     * H.264: ffmpeg's generic "-level" option uses level*10 (e.g. "40" = 4.0)
     * -- this is the standard convention across every ffmpeg H.264 encoder,
     * high confidence here.
     * HEVC: uses the raw general_level_idc value, which the H.265 spec
     * itself defines as level*30 (e.g. "120" = level 4). Same idea, but
     * less directly verified than the H.264 side -- worth checking the next
     * log's CCodec "level" output actually reflects what was asked for.
     */
    /**
     * FIX: the actual height to request when testing a resolution's encoder
     * capability. Equal to maxHeight for every resolution except HD_1080.
     *
     * HD_1080 (1920x1080) is the only one of our four test resolutions that
     * isn't already macroblock (mod-16) aligned -- SD (576), HD_720 (720),
     * and UHD_4K (2160) all divide evenly by 16; 1080/16=67.5 does not, so
     * the encoder has to pad to 1088 and SPS-crop 8px internally. That
     * padding step itself completes fine (CCodec logs correctly show
     * raw.size.height=1088), but the request then fails at a later step
     * with a bogus coded.pl.level value (20491) nobody asked for -- and it
     * failed IDENTICALLY across three unrelated vendor hardware encoders
     * (Samsung Exynos, Google Tensor, Qualcomm Adreno), while Adreno
     * handles the larger, harder UHD_4K (also already-aligned) perfectly.
     * That combination -- same bug, three unrelated vendors, only the one
     * non-aligned resolution -- points at ffmpeg's own mediacodec wrapper
     * mishandling the crop/pad case, not a device limitation.
     *
     * Requesting the already-padded 1920x1088 directly sidesteps the crop
     * path entirely. It's not the standard "1080p" dimension a renderer
     * would ask for by name, but it's the same content at the same
     * bitrate/level class -- a valid stand-in for capability-testing
     * purposes. Real transcode planning (once built) still targets
     * Resolution.HD_1080's true maxHeight (1080) for renderer-facing
     * decisions; this only affects what dimensions the probe itself uses.
     */
    private fun testHeightFor(resolution: Constants.Transcoder.Resolution): Int =
        if (resolution == Constants.Transcoder.Resolution.HD_1080) 1088 else resolution.maxHeight

    private fun encoderLevelArg(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): String =
        when (codec) {
            Constants.Transcoder.VideoCodec.H264 -> when (resolution) {
                Constants.Transcoder.Resolution.SD -> "30"       // Level 3.0
                Constants.Transcoder.Resolution.HD_720 -> "31"   // Level 3.1
                Constants.Transcoder.Resolution.HD_1080 -> "40"  // Level 4.0
                Constants.Transcoder.Resolution.UHD_4K -> "51"   // Level 5.1
            }
            Constants.Transcoder.VideoCodec.HEVC -> when (resolution) {
                Constants.Transcoder.Resolution.SD -> "60"        // Level 2.0
                Constants.Transcoder.Resolution.HD_720 -> "93"    // Level 3.1
                Constants.Transcoder.Resolution.HD_1080 -> "123"  // Level 4.1
                Constants.Transcoder.Resolution.UHD_4K -> "153"   // Level 5.1
            }
        }

    /**
     * Explicit encoder tuning, replacing whatever ffmpeg's mediacodec wrapper
     * was defaulting to -- level=1 turned out to be one bad default already,
     * so rather than chase defaults one at a time, pin down everything the
     * evidence so far points at or that's good practice to make explicit:
     *   -profile:v   High/Main -- already what was being auto-picked (CCodec
     *                showed profile=8/AVCProfileHigh), made explicit.
     *   -level       see encoderLevelArg above.
     *   -pix_fmt     yuv420p -- the encoder needs YUV, and the sample video's
     *                own decoded format isn't guaranteed to already be it;
     *                let ffmpeg's swscale conversion be explicit, not implicit.
     *   -r           matches the input rate, removes any ambiguity there.
     *   -g           EVERY log so far prints "Use 1 as the default MediaFormat
     *                i-frame-interval, please set gop_size properly (>= fps)"
     *                -- ffmpeg is telling us this is unset and wrong for our
     *                24fps test. Setting it explicitly silences that and
     *                removes one more unknown-default variable.
     *   -bf 0        No B-frames. This is the strongest new lead: "encoder
     *                opens fine, zero frames ever, forever" is the textbook
     *                signature of a reordering buffer that's never flushed --
     *                exactly what happens if B-frames are enabled by default
     *                and EOS isn't triggering a proper drain. Disabling them
     *                removes reordering from the picture entirely.
     */
    private fun encoderTuningArgs(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): List<String> {
        val profile = when (codec) {
            Constants.Transcoder.VideoCodec.H264 -> "high"
            Constants.Transcoder.VideoCodec.HEVC -> "main"
        }
        return listOf(
            "-profile:v", profile,
            "-level", encoderLevelArg(codec, resolution),
            "-pix_fmt", "yuv420p",
            "-r", "24",
            "-g", "24",
            "-bf", "0"
        )
    }

    /**
     * Stage 2/4: the encoder ALONE, with -f null so there is no muxer or
     * file-writing involved whatsoever. Uses the SAME duration as the real
     * target (stage 4), not a single frame: some hardware encoders need
     * several frames queued before they'll flush any output at all (lookahead/
     * buffering), so testing with just 1 frame + immediate EOS is itself an
     * edge case real transcoding never hits, and could produce a false
     * negative that has nothing to do with the muxer question this stage
     * exists to isolate. If this hangs, the hang is inside the encoder/
     * MediaCodec itself with a realistic workload, not mp4/mpegts finalization.
     */
    private suspend fun debugEncodeNullMuxer(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): ProcessOutput {
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner", "-v", "debug",
            "-i", "pipe:0", "-an", "-sn",
            "-vf", "scale=${resolution.maxWidth}:${testHeightFor(resolution)}",
            "-t", "1", "-c:v", codec.ffmpegEncoder, "-b:v", "${resolution.targetBitrateKbps}k"
        ) + encoderTuningArgs(codec, resolution) + listOf("-f", "null", "-")
        return runProcessCapture(args, timeoutMs = 8_000L, logStderrLive = true, stdinSource = getSampleVideoUri())
    }

    /**
     * Stage 1/3: demux + decode ONLY, no video encoder involved at all.
     * Uses ffprobe (not ffmpeg) to open the real sample video (sampleVideoUri,
     * piped in over stdin the same way every other video stage below is now
     * fed) and read its stream info back. If this hangs or fails, the problem
     * is in demuxing/decoding the file itself — h264_mediacodec/hevc_mediacodec
     * are never even reached, so they're ruled out immediately.
     */
    private suspend fun debugProbeSampleSource(resolution: Constants.Transcoder.Resolution): ProcessOutput {
        Log.d(TAG, "Probing real sample source ahead of the ${resolution.name} encoder test")
        val args = listOf(
            ffprobePath, "-hide_banner", "-v", "info",
            "-i", "pipe:0", "-select_streams", "v:0",
            "-show_entries", "stream=codec_name,width,height,pix_fmt",
            "-of", "default=noprint_wrappers=1"
        )
        return runProcessCapture(args, timeoutMs = 8_000L, logStderrLive = true, stdinSource = getSampleVideoUri())
    }

    /**
     * Stage 3/4: encoder + a minimal streamable muxer (mpegts — no seek-back-
     * to-finalize step the way mp4 needs). Same realistic duration as stage 2,
     * so the only variable that changed from stage 2 is "is a muxer involved
     * at all" — isolates encoder<->muxer interaction (extradata/SPS-PPS
     * timing, interleaving) from encoder-alone behavior.
     */
    private suspend fun debugEncodeStreamableMuxer(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): ProcessOutput {
        val tempOut = File(workDir, "cap_check_${codec.name.lowercase()}_${resolution.name.lowercase()}.ts")
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner", "-v", "debug",
            "-i", "pipe:0", "-an", "-sn",
            "-vf", "scale=${resolution.maxWidth}:${testHeightFor(resolution)}",
            "-t", "1", "-c:v", codec.ffmpegEncoder, "-b:v", "${resolution.targetBitrateKbps}k"
        ) + encoderTuningArgs(codec, resolution) + listOf("-f", "mpegts", tempOut.absolutePath)
        val result = runProcessCapture(args, timeoutMs = 8_000L, logStderrLive = true, stdinSource = getSampleVideoUri())
        tempOut.delete()
        return result
    }

    /**
     * Stage 4/3: the real target scenario — a full 1s clip, muxed to mp4
     * (what actual transcoding will produce). If stage 3 passes but this
     * hangs or fails, the problem is specifically in mp4 finalization
     * (moov/trailer writing) rather than the encoder or muxing in general.
     */
    private suspend fun debugEncodeFullClip(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): ProcessOutput {
        val tempOut = File(workDir, "cap_check_${codec.name.lowercase()}_${resolution.name.lowercase()}.mp4")
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner", "-v", "debug",
            "-i", "pipe:0", "-an", "-sn",
            "-vf", "scale=${resolution.maxWidth}:${testHeightFor(resolution)}",
            "-t", "1", "-c:v", codec.ffmpegEncoder, "-b:v", "${resolution.targetBitrateKbps}k"
        ) + encoderTuningArgs(codec, resolution) + listOf("-f", "mp4", tempOut.absolutePath)
        val result = runProcessCapture(args, timeoutMs = 15_000L, logStderrLive = true, stdinSource = getSampleVideoUri())
        tempOut.delete()
        return result
    }

    /** PASS/INCONCLUSIVE/HANG framing straight from plan Section 5.1's outcome list. */
    private enum class Gate1aOutcome { PASS, INCONCLUSIVE, HANG }

    private suspend fun runGate1aWithSamples() {
        val ffmpegDir = ffmpegFile.parentFile ?: return
        val files = ffmpegDir.listFiles()?.filter { it.isFile && it.name.startsWith("test_") } ?: emptyList()
        if (files.isEmpty()) {
            Log.e(TAG, "Gate 1a Samples: No sample files found in ${ffmpegDir.absolutePath}")
        } else {
            val samplesDir = File(workDir, "gate1a_samples")
            samplesDir.mkdirs()
            samplesDir.listFiles()?.forEach { it.delete() }

            Log.d(TAG, "Gate 1a: Starting test loop for ${files.size} samples")
            for ((index, file) in files.withIndex()) {
                Log.d(TAG, "Gate 1a: [${index + 1}/${files.size}] Processing ${file.name}")
                val renamedFile = File(samplesDir, file.name.removeSuffix(".so"))
                try {
                    file.inputStream().use { input ->
                        renamedFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d(TAG, "Gate 1a: [${index + 1}/${files.size}] Testing sample ${renamedFile.name}")
                    val outcome = withTimeoutOrNull(20_000L.milliseconds) {
                        gate1a(renamedFile, Constants.Transcoder.Resolution.HD_720)
                    } ?: Gate1aOutcome.INCONCLUSIVE
                    Log.d(TAG, "Gate 1a: [${index + 1}/${files.size}] Sample ${renamedFile.name} outcome: $outcome")
                } catch (e: Exception) {
                    Log.e(TAG, "Gate 1a: [${index + 1}/${files.size}] Error testing ${file.name}: ${e.message}")
                }
            }
            Log.d(TAG, "Gate 1a: Test loop finished")
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun gate1a(
        sourceFile: File,
        resolution: Constants.Transcoder.Resolution
    ): Gate1aOutcome {
        val mp4Chunks = mutableListOf<File>()
        val tsFiles = mutableListOf<File>()

        try {
            // 1. Determine segmentsCount based on duration
            val durationArgs = listOf(
                ffprobePath, "-hide_banner", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                sourceFile.absolutePath
            )
            val durationResult = runProcessCapture(durationArgs, verbose = false)
            val durationSeconds = durationResult.stdout.trim().toDoubleOrNull() ?: 0.0
            val totalDurationMs = (durationSeconds * 1000).toLong()
            val segmentDurationMs = 1_000L
            // FIX: Ensure we don't create an ultra-short trailing segment (e.g. 1ms)
            // that causes Muxer errors. Only add a segment if there's at least 100ms of data.
            val segmentsCount = (totalDurationMs / segmentDurationMs).toInt() +
                                (if (totalDurationMs % segmentDurationMs >= 100) 1 else 0)

            if (segmentsCount == 0) {
                Log.e(TAG, "Gate 1a: source file duration is 0 or unknown, skipping.")
                return Gate1aOutcome.INCONCLUSIVE
            }

            // 2. Cross-Codec Logic
            val probeArgs = listOf(
                ffprobePath, "-hide_banner", "-v", "error",
                "-select_streams", "v:0",
                "-show_entries", "stream=codec_name",
                "-of", "default=noprint_wrappers=1:nokey=1",
                sourceFile.absolutePath
            )
            val probeResult = runProcessCapture(probeArgs, verbose = false)
            val sourceCodecName = probeResult.stdout.trim().lowercase()

            val isH264 = sourceCodecName.contains("h264") || sourceCodecName.contains("avc")
            val targetCodec = if (isH264) {
                Constants.Transcoder.VideoCodec.HEVC
            } else {
                Constants.Transcoder.VideoCodec.H264
            }

            val targetMime = when (targetCodec) {
                Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264
                Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265
            }

            val bsf = when (targetCodec) {
                Constants.Transcoder.VideoCodec.H264 -> "h264_mp4toannexb"
                Constants.Transcoder.VideoCodec.HEVC -> "hevc_mp4toannexb"
            }

            var isTimedOut = false
            var isFailed = false

            for (i in 0 until segmentsCount) {
                val startMs = i * segmentDurationMs
                val endMs = min((i + 1) * segmentDurationMs, totalDurationMs)
                val chunkMp4 = File(workDir, "gate1a_chunk_$i.mp4")
                mp4Chunks.add(chunkMp4)
                if (chunkMp4.exists()) chunkMp4.delete()

                val segmentResult = withTimeoutOrNull(5_000L.milliseconds) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            val transformer = Transformer.Builder(context)
                                .setVideoMimeType(targetMime)
                                .setEncoderFactory(
                                    DefaultEncoderFactory.Builder(context)
                                        .setRequestedVideoEncoderSettings(
                                            VideoEncoderSettings.Builder()
                                                .setBitrate(resolution.targetBitrateKbps * 1000)
                                                .build()
                                        )
                                        .build()
                                )
                                .build()

                            val listener = object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                    if (continuation.isActive) continuation.resume(true)
                                }

                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    Log.e(TAG, "Gate 1a Segment $i Media3 Error: ${exportException.message}")
                                    if (continuation.isActive) continuation.resume(false)
                                }
                            }
                            transformer.addListener(listener)

                            try {
                                val extension = sourceFile.name.substringAfterLast('.', "").lowercase()
                                val resolvedMimeType = Constants.mimeType[extension]
                                
                                val mediaItem = MediaItem.Builder()
                                    .setUri(Uri.fromFile(sourceFile))
                                    .apply {
                                        if (resolvedMimeType != null) {
                                            setMimeType(resolvedMimeType)
                                        }
                                    }
                                    .setClippingConfiguration(
                                        MediaItem.ClippingConfiguration.Builder()
                                            .setStartPositionMs(startMs)
                                            .setEndPositionMs(endMs)
                                            .build()
                                    )
                                    .build()
                                val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                                    .setRemoveAudio(true)
                                    .setEffects(
                                        Effects(
                                            emptyList(),
                                            listOf(Presentation.createForHeight(resolution.maxHeight))
                                        )
                                    )
                                    .build()

                                transformer.start(editedMediaItem, chunkMp4.absolutePath)

                                // BUG (see log.txt: Gate 1a: [7/16] test_4k.wmv hangs forever,
                                // no HANG outcome is ever logged, no more samples run). Root
                                // cause: invokeOnCancellation's handler runs SYNCHRONOUSLY as
                                // part of cancelling this continuation, and withTimeoutOrNull
                                // -- both the 5s one wrapping this block and the 20s one in
                                // runGate1aWithSamples() -- blocks until that handler returns
                                // before it can report a timeout. The old handler called
                                // transformer.cancel() inline, i.e. a call into Media3/the
                                // underlying HW codec -- exactly the kind of "external library"
                                // this timeout exists to route around. If the encoder is
                                // genuinely wedged (the case a timeout is meant to catch),
                                // cancel() can block right along with it, so the recovery path
                                // hangs waiting on its own cancellation handler and
                                // Gate1aOutcome.HANG (below) is never reached.
                                //
                                // ASSUMPTION flagged explicitly since it isn't provable from
                                // logcat alone: Transformer.cancel() is documented to require
                                // the thread that created/started the Transformer (Main, via
                                // withContext(Dispatchers.Main) above) -- but this handler can
                                // also be invoked by the OUTER 20s withTimeoutOrNull, which
                                // runs on `scope` (Dispatchers.IO). So the old code could also
                                // have been calling cancel() cross-thread, a second independent
                                // way for this call to misbehave/hang.
                                //
                                // Fix: never let the cancel() call itself sit on the critical
                                // path of cancellation. Detach it, force it back onto Main
                                // (matching Transformer's thread-confinement contract), and
                                // don't await it -- a best-effort cancel that itself hangs must
                                // not be allowed to block the coroutine trying to recover from
                                // it. Segment/sample-level bookkeeping already treats this as a
                                // lost segment (isTimedOut = true, below) whether or not this
                                // cancel() ever actually completes.
                                continuation.invokeOnCancellation {
                                    CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch {
                                        try {
                                            transformer.cancel()
                                        } catch (e: Exception) {
                                            Log.e(TAG, "Gate 1a Segment $i: transformer.cancel() failed during timeout recovery: ${e.message}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Gate 1a Segment $i Setup Error: ${e.message}")
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }
                    }
                }

                // TEMP DIAGNOSTIC (not verbose-gated on purpose): log.txt from the [7/16]
                // and [3/16] hangs never shows an "outcome:" line, and never shows the
                // *unconditional* "FFmpeg Fallback Segment N FAILED" log either -- so we
                // can't tell from the existing logs whether control reaches this point at
                // all after onError fires. This line pins that down for the next capture:
                // if it's missing/delayed, the stall is upstream of here (continuation
                // resume/dispatch); if it prints promptly but the FALLBACK_ENTER/EXIT pair
                // below doesn't follow within ~5.1s, the stall is inside
                // runFfmpegFallbackSegment/runProcessCapture despite their timeouts.
                Log.d(TAG, "Gate 1a Segment $i: segmentResult=$segmentResult")

                var currentSegmentSuccess = segmentResult == true
                if (segmentResult == false) {
                    Log.d(TAG, "Gate 1a Segment $i: FALLBACK_ENTER")
                    val fallbackSuccess = runFfmpegFallbackSegment(
                        index = i,
                        startMs = startMs,
                        endMs = endMs,
                        sourceUri = Uri.fromFile(sourceFile),
                        targetCodec = targetCodec,
                        targetHeight = resolution.maxHeight,
                        targetBitrateBps = resolution.targetBitrateKbps * 1000,
                        outputFile = chunkMp4,
                        verbose = false
                    )
                    Log.d(TAG, "Gate 1a Segment $i: FALLBACK_EXIT success=$fallbackSuccess")
                    currentSegmentSuccess = fallbackSuccess
                } else if (segmentResult == null) {
                    isTimedOut = true
                    break
                }

                if (currentSegmentSuccess) {
                    val tsFile = File(workDir, "gate1a_chunk_$i.ts")
                    val convertArgs = listOf(
                        ffmpegPath, "-y", "-hide_banner",
                        "-i", chunkMp4.absolutePath,
                        "-c", "copy",
                        "-bsf:v", bsf,
                        "-f", "mpegts", tsFile.absolutePath
                    )
                    val convertResult = runProcessCapture(convertArgs, verbose = false)
                    if (convertResult.exitCode == 0) {
                        tsFiles.add(tsFile)
                    } else {
                        Log.e(TAG, "Gate 1a Segment $i TS Conversion Failed")
                        isFailed = true
                        break
                    }
                } else {
                    // Fallback failed or timed out
                    isFailed = true
                    break
                }
            }

            val outputMp4 = File(workDir, "gate1a_output.mp4")
            if (outputMp4.exists()) outputMp4.delete()

            var finalSuccess = false
            if (!isTimedOut && !isFailed && tsFiles.size == segmentsCount) {
                val combinedTs = File(workDir, "gate1a_combined.ts")
                try {
                    combinedTs.outputStream().use { output ->
                        tsFiles.forEach { tsFile ->
                            tsFile.inputStream().use { input ->
                                input.copyTo(output)
                            }
                        }
                    }
                    val remuxArgs = listOf(
                        ffmpegPath, "-y", "-hide_banner",
                        "-i", combinedTs.absolutePath,
                        "-c", "copy", outputMp4.absolutePath
                    )
                    val remuxResult = runProcessCapture(remuxArgs, verbose = false)
                    if (remuxResult.exitCode == 0) finalSuccess = true
                } catch (e: Exception) {
                    Log.e(TAG, "Gate 1a Concatenation Failed: ${e.message}")
                } finally {
                    if (combinedTs.exists()) combinedTs.delete()
                }
            }

            return when {
                isTimedOut -> Gate1aOutcome.HANG
                finalSuccess -> Gate1aOutcome.PASS
                else -> Gate1aOutcome.INCONCLUSIVE
            }
        } finally {
            // Cleanup: Ensure all .ts and .mp4 chunks are deleted after the final gate1a_output.mp4 is created.
            mp4Chunks.forEach { if (it.exists()) it.delete() }
            tsFiles.forEach { if (it.exists()) it.delete() }
        }
    }

    /**
     * Gate 1a (Surface-Input Investigation Plan, Section 5.1) -- the cheap first
     * attempt at Q1: does routing ffmpeg's OWN h264_mediacodec/hevc_mediacodec
     * through Surface-mode input avoid Bug A (the ByteBuffer-mode zero-output-
     * forever hang from [testVideoEncoder] below)? Tests synthetic lavfi/testsrc
     * content only -- no real sample file, no stdin -- reusing the same
     * resolution matrix as the existing checks ([testHeightFor] handles the
     * HD_1080 mod-16 quirk here exactly as it does for the ByteBuffer-mode test).
     *
     * `-init_hw_device mediacodec=mediacodec,create_window=1` opens the hardware
     * surface; `format=nv12,hwupload` is the filter-chain attempt at getting a
     * software-generated testsrc frame onto that surface. Per the plan, this
     * corner of ffmpeg (sw->MediaCodec-surface upload) is less documented than
     * the VAAPI/CUDA/QSV equivalents -- treat this exact filter chain as a first
     * attempt to be checked against `ffmpeg -h filter=hwupload` and
     * libavcodec/mediacodecenc.c on a real device, not a settled answer.
     *
     * Deliberately does NOT reuse [encoderTuningArgs] wholesale: once a frame is
     * on the hardware surface, its pixel format is no longer something -pix_fmt
     * controls, so that one flag is dropped here to avoid fighting the filter
     * graph rather than describing it. Profile/level/gop/b-frames are still
     * pinned explicitly, same rationale as the ByteBuffer-mode test.
     *
     * Outcome mapping is exactly the plan's framing (5.1): only a real HANG is
     * grounds to stop and reconsider before Gate 1b; a fast, specific failure
     * is inconclusive for Q1, not a refutation of it -- proceed to 1b regardless.
     */
    private suspend fun oldGate1aCode(
        sourceVideo: String?,
        codec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution
    ): Gate1aOutcome {
        val width = resolution.maxWidth
        val height = testHeightFor(resolution)
        val profile = when (codec) {
            Constants.Transcoder.VideoCodec.H264 -> "high"
            Constants.Transcoder.VideoCodec.HEVC -> "main"
        }
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner", "-v", "debug",
            "-init_hw_device", "mediacodec=mediacodec,create_window=1",
            // -init_hw_device only registers the device; it does not bind it to
            // any filter. hwupload needs an explicit hw_device_ctx to derive
            // frames into, and without -filter_hw_device it fails config_props
            // with EINVAL ("Query format failed ... Invalid argument") before a
            // single frame is touched -- a command-construction bug, not a
            // finding about Q1. See log from the first Gate 1a run for the
            // exact failure signature this was producing.
            "-filter_hw_device", "mediacodec",
            "-f", "lavfi", "-i", "testsrc=size=${width}x${height}:rate=24",
            "-t", "1",
            "-vf", "format=nv12,hwupload",
            "-c:v", codec.ffmpegEncoder,
            "-b:v", "${resolution.targetBitrateKbps}k",
            "-profile:v", profile,
            "-level", encoderLevelArg(codec, resolution),
            "-g", "24",
            "-bf", "0",
            "-f", "null", "-"
        )

        Log.d(TAG, "GATE 1a: ${codec.name}@${resolution.name} -- ${args.joinToString(" ")}")
        val result = runProcessCapture(args, timeoutMs = 8_000L, logStderrLive = true)

        return when {
            result.timedOut -> {
                Log.e(TAG, "GATE 1a: ${codec.name}@${resolution.name} HUNG -- same zero-output signature as Bug A. " +
                        "Concerning for the whole Surface-mode hypothesis; per the plan, stop and reconsider " +
                        "(Section 8) before investing further in Gate 1b/1c.")
                Gate1aOutcome.HANG
            }
            result.exitCode == 0 -> {
                Log.d(TAG, "GATE 1a: ${codec.name}@${resolution.name} PASS -- Surface-mode input produced output. " +
                        "Confirms Q1, proceed to 1b/1c.")
                Gate1aOutcome.PASS
            }
            else -> {
                Log.w(TAG, "GATE 1a: ${codec.name}@${resolution.name} INCONCLUSIVE (fails fast, exitCode=${result.exitCode}) " +
                        "-- not a refutation of Q1; likely means this specific sw-upload path isn't available/mature " +
                        "in this build. Proceed to Gate 1b regardless. stderr:\n${result.stderr}")
                Gate1aOutcome.INCONCLUSIVE
            }
        }
    }

    /**
     * PASS/INCONCLUSIVE/HANG framing, same three cases as [Gate1aOutcome] (plan
     * Section 5.2: "same PASS/FAIL/INCONCLUSIVE framing as 5.1"). Kept as a
     * distinct type even though the cases are identical, because an
     * INCONCLUSIVE here does NOT carry the same "this exact filter corner may
     * just not be mature yet" excuse 1a's INCONCLUSIVE does -- see the log
     * message in the failure branch of [testVideoDecodeEncodeSurfaceModeGate1b].
     */
    private enum class Gate1bOutcome { PASS, INCONCLUSIVE, HANG }

    /**
     * Gate 1b (plan Section 5.2) -- the "gold standard" test for Q1: real
     * hardware decode -> Surface -> real hardware encode, zero-copy, matching
     * exactly what Android's own camera/recording pipeline does.
     */
    private suspend fun testVideoDecodeEncodeSurfaceModeGate1b(
        codec: Constants.Transcoder.VideoCodec,
        item: MediaCollection.MediaNode.Item
    ): Gate1bOutcome {
        val resParts = item.resolution.split("x")
        val inputHeight = if (resParts.size == 2) resParts[1].toIntOrNull() ?: 720 else 720
        
        // Pick an informed resolution constant for tuning
        val resolution = Constants.Transcoder.Resolution.entries
            .find { it.maxHeight >= inputHeight } ?: Constants.Transcoder.Resolution.UHD_4K
            
        val targetBitrateBps = resolution.targetBitrateKbps * 1000

        val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"

        val args = listOf(
            ffmpegPath, "-y", "-hide_banner", "-v", "debug",
            "-hwaccel", "mediacodec",
            "-hwaccel_output_format", "mediacodec",
            "-i", inputPath!!
        ) + listOf(
            "-an", "-sn",
            "-init_hw_device", "mediacodec=mediacodec,create_window=1",
            "-filter_hw_device", "mediacodec",
            "-t", GATE_1B_TEST_DURATION_SECONDS.toString(),
            "-c:v", codec.ffmpegEncoder,
            "-b:v", "${targetBitrateBps / 1000}k",
            "-bitrate_mode", "vbr",
            "-ndk_codec", "1"
        ) + encoderTuningArgs(codec, resolution).filter { it != "-pix_fmt" && it != "yuv420p" } + listOf(
            "-f", "null", "-"
        )

        Log.d(TAG, "GATE 1b: ${codec.name} @ ${resolution.name} (input=$inputPath) -- ${args.joinToString(" ")}")
        // Using a 10s timeout for the process
        val result = runProcessCapture(args, timeoutMs = 10_000L, logStderrLive = true, stdinSource = if (inputPath == "pipe:0") item.uri else null)

        return when {
            result.timedOut -> {
                Log.e(TAG, "GATE 1b: ${codec.name} HUNG -- zero output forever, same signature as Bug A.")
                Gate1bOutcome.HANG
            }
            result.exitCode == 0 -> {
                Log.d(TAG, "GATE 1b: ${codec.name} PASS -- real Surface-mode decode->encode produced output.")
                Gate1bOutcome.PASS
            }
            else -> {
                Log.e(TAG, "GATE 1b: ${codec.name} FAILED, exitCode=${result.exitCode}. stderr:\n${result.stderr}")
                Gate1bOutcome.INCONCLUSIVE
            }
        }
    }

    private suspend fun testVideoEncoder(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): Boolean {
        Log.d(TAG, "########## Testing ${codec.name}@${resolution.name} ##########")

        if (getSampleVideoUri() == null) {
            Log.e(TAG, "${codec.name}@${resolution.name}: no video found in mediaCollection to use as a real source for the encoder checks -- skipping.")
            return false
        }

        Log.d(TAG, "---- Stage 0/4: rawvideo pipeline sanity check (no MediaCodec at all) ----")
        val stage0 = debugEncodeRawvideoSanityCheck(resolution)
        Log.d(TAG, "Stage 0 result: exitCode=${stage0.exitCode} timedOut=${stage0.timedOut}")
        if (stage0.timedOut || stage0.exitCode != 0) {
            Log.e(TAG, "${codec.name}@${resolution.name}: STAGE 0 FAILED — even a trivial rawvideo passthrough hangs/fails. " +
                    "This is NOT specific to $codec/MediaCodec, something more fundamental is wrong with how ffmpeg is being run. stderr:\n${stage0.stderr}")
            return false
        }
        Log.d(TAG, "Stage 0 OK — demux->decode->encode->mux->write pipeline works with no MediaCodec involved.")

        Log.d(TAG, "---- Stage 1/4: demux+decode only (ffprobe, no encoder) ----")
        val stage1 = debugProbeSampleSource(resolution)
        Log.d(TAG, "Stage 1 result: exitCode=${stage1.exitCode} timedOut=${stage1.timedOut}")
        if (stage1.timedOut || stage1.exitCode != 0) {
            Log.e(TAG, "${codec.name}@${resolution.name}: STAGE 1 FAILED — demux/decode itself is broken, before any encoder is ever reached. stdout:\n${stage1.stdout}")
            return false
        }
        Log.d(TAG, "Stage 1 OK — real sample video demux+decode confirmed working. stdout:\n${stage1.stdout}")

        Log.d(TAG, "---- Stage 2/4: encoder ALONE, -f null (no muxer at all) ----")
        val stage2 = debugEncodeNullMuxer(codec, resolution)
        Log.d(TAG, "Stage 2 result: exitCode=${stage2.exitCode} timedOut=${stage2.timedOut}")
        if (stage2.timedOut) {
            Log.e(TAG, "${codec.name}@${resolution.name}: STAGE 2 TIMED OUT — the encoder itself hangs with ZERO muxer involvement. " +
                    "This points squarely at h264_mediacodec/MediaCodec, not at mp4/mpegts finalization.")
            return false
        }
        if (stage2.exitCode != 0) {
            Log.e(TAG, "${codec.name}@${resolution.name}: STAGE 2 FAILED (encoder alone, no muxer, clean exit code ${stage2.exitCode}). stderr:\n${stage2.stderr}")
            return false
        }
        Log.d(TAG, "Stage 2 OK — encoder opens and produces at least one frame with no muxer involved.")

        Log.d(TAG, "---- Stage 3/4: encoder + minimal streamable muxer (mpegts, same duration as stage 2) ----")
        val stage3 = debugEncodeStreamableMuxer(codec, resolution)
        Log.d(TAG, "Stage 3 result: exitCode=${stage3.exitCode} timedOut=${stage3.timedOut}")
        if (stage3.timedOut) {
            Log.e(TAG, "${codec.name}@${resolution.name}: STAGE 3 TIMED OUT — encoder-alone (stage 2) was fine, but adding even the lightweight mpegts " +
                    "muxer hangs. Points at encoder<->muxer interaction (extradata/SPS-PPS timing, interleaving), not the encoder in isolation.")
            return false
        }
        if (stage3.exitCode != 0) {
            Log.e(TAG, "${codec.name}@${resolution.name}: STAGE 3 FAILED (encoder + mpegts, clean exit code ${stage3.exitCode})")
            return false
        }
        Log.d(TAG, "Stage 3 OK — encoder + minimal streamable muxer produces at least one frame.")

        Log.d(TAG, "---- Stage 4/4: full 1s clip, mp4 (the real target) ----")
        val stage4 = debugEncodeFullClip(codec, resolution)
        Log.d(TAG, "Stage 4 result: exitCode=${stage4.exitCode} timedOut=${stage4.timedOut}")
        if (stage4.timedOut) {
            Log.e(TAG, "${codec.name}@${resolution.name}: STAGE 4 TIMED OUT — encoder + mpegts worked (stage 3 OK) but the full clip / mp4 " +
                    "finalization hangs. Points at mp4-specific finalization (moov/trailer writing), NOT the encoder itself.")
            return false
        }
        if (stage4.exitCode != 0) {
            Log.e(TAG, "${codec.name}@${resolution.name}: STAGE 4 FAILED. stderr:\n${stage4.stderr}")
            return false
        }
        Log.d(TAG, "${codec.name}@${resolution.name}: ALL 4 STAGES PASSED")
        return true
    }

    /**
     * Gate 0: Confirm the hardware itself can produce output, via Media3 Transformer.
     * Rationale: split "ffmpeg bug" from "hardware failure".
     */
    @OptIn(UnstableApi::class)
    private suspend fun testVideoEncoderViaMedia3(codec: Constants.Transcoder.VideoCodec, uri: Uri): Boolean = withContext(Dispatchers.Main) {
        val result = suspendCancellableCoroutine { continuation ->
            val mimeType = when (codec) {
                Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264
                Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265
            }

            val outFile = File(workDir, "gate0_media3_${codec.name.lowercase()}.mp4")
            if (outFile.exists()) outFile.delete()

            try {
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(mimeType)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            Log.d(TAG, "Gate 0: Media3 export completed for $codec. File size: ${outFile.length()}")
                            if (continuation.isActive) continuation.resume(true)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            Log.e(TAG, "Gate 0: Media3 export failed for $codec", exportException)
                            if (continuation.isActive) continuation.resume(false)
                        }
                    })
                    .build()

                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .setMimeType(context.contentResolver.getType(uri) ?: "video/mp4")
                    .setClippingConfiguration(
                        MediaItem.ClippingConfiguration.Builder()
                            .setEndPositionMs(2000) // Only transcode first 2s for Gate 0
                            .build()
                    )
                    .build()
                val editedMediaItem = EditedMediaItem.Builder(mediaItem)
                    .setRemoveAudio(true)
                    .build()
                transformer.start(editedMediaItem, outFile.absolutePath)

                continuation.invokeOnCancellation {
                    transformer.cancel()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gate 0: Media3 setup failed for $codec", e)
                if (continuation.isActive) continuation.resume(false)
            }
        }

        // Post-export verification (outside the coroutine bridge to avoid racing with Transformer release)
        if (result) {
            val outFile = File(workDir, "gate0_media3_${codec.name.lowercase()}.mp4")
            val probeResult = debugProbeOutputFile(outFile)
            val isValid = probeResult.exitCode == 0 && outFile.length() > 0
            Log.d(TAG, "Gate 0: Media3 verification for $codec: isValid=$isValid")
            isValid
        } else {
            false
        }
    }

    private suspend fun debugProbeOutputFile(file: File): ProcessOutput {
        val args = listOf(
            ffprobePath, "-hide_banner", "-v", "info",
            "-i", file.absolutePath,
            "-select_streams", "v:0",
            "-show_entries", "stream=codec_name,width,height",
            "-of", "default=noprint_wrappers=1"
        )
        return runProcessCapture(args, timeoutMs = 8_000L, logStderrLive = true)
    }

    private suspend fun testAudioEncoder(codec: Constants.Transcoder.AudioCodec): Boolean {
        for (i in 0 until 2){
            val tempOut = File(workDir, "cap_check_audio_${codec.name.lowercase()}.mp4")
            val args = listOf(
                ffmpegPath, "-y", "-hide_banner",
                "-f", "lavfi", "-i", "sine=frequency=1000:duration=1",
                "-t", "1", "-ac", codec.testChannels.toString(), "-c:a", codec.ffmpegEncoder, "-f", "mp4", tempOut.absolutePath
            )
            val result = runProcessCapture(args)
            tempOut.delete()
            if (result.exitCode == 0) return true
            delay(RETRY_DELAY_MS.milliseconds)
            Log.d(TAG, "audio ${codec.name}: attempt failed")
            Log.d(TAG, "${codec.name}: ${result.stderr}")
        }
        return false
    }

    private fun currentAppVersionCode(): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        -1L
    }

    private data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

    /**
     * @param timeoutMs safety valve ONLY — so a hung process can't stop us from
     *   ever seeing the diagnostic output of earlier stages, and can't hang the
     *   app's coroutine forever. A timeout is logged distinctly (timedOut=true)
     *   and is NOT the same thing as a clean failure; callers should not treat
     *   it as an ordinary "unsupported" result without saying so explicitly.
     * @param logStderrLive log each stderr line as ffmpeg/ffprobe emits it,
     *   instead of only seeing the whole buffer after the process exits — this
     *   is what lets us tell how far a hung process actually got.
     */
    private suspend fun runProcessCapture(
        args: List<String>,
        timeoutMs: Long = 15_000L,
        logStderrLive: Boolean = false,
        stdinSource: Uri? = null,
        verbose: Boolean = true
    ): ProcessOutput {
        if (verbose) Log.d(TAG, "Starting ProcessBuilder: ${args.joinToString(" ")}")
        val process = try {
            ProcessBuilder(args).start()
        } catch (e: Exception) {
            return ProcessOutput(-1, "", "Failed to start process: ${e.message}")
        }
        Log.d(TAG, "RPC_PROCESS_STARTED tag=${System.identityHashCode(process)}")

        val stderrBuilder = StringBuilder()
        val stdoutBuilder = StringBuilder()
        val lock = Any()

        // BUG (see log.txt: "Gate 1a Segment 0: FALLBACK_ENTER" logs, then nothing --
        // no FALLBACK_EXIT, no TIMED OUT log, no anything -- for 55+s until the app is
        // manually killed). This function's own polling loop below is supposed to make
        // an unbounded hang structurally impossible: it only checks wall-clock time and
        // delay()s, with no dependency on the child process or its streams behaving.
        // It was still hanging, which means the loop itself wasn't getting to run.
        //
        // Root cause: the reader/writer coroutines used to be launched on the SHARED
        // Dispatchers.IO pool, deliberately never joined ("do NOT join it") so a stuck
        // one couldn't hang *this* call directly. But closing a stream from another
        // thread while a thread is blocked inside a synchronous read()/write() on that
        // same fd is not reliably interrupted by the JVM/Android -- plain java.io
        // streams (unlike NIO channels) generally ignore it. So a stuck reader, or the
        // stdin-piping writer in runFfmpegFallbackSegment (which can be left writing
        // into a full pipe if ffmpeg stops consuming before exiting), could strand a
        // thread in the shared pool permanently -- one per unlucky call. Gate 1a
        // hammers this pool hard: every sample does several of these calls (duration
        // probe, codec probe, ts-mux, remux) plus a fallback call on any error, so
        // leaks accumulate across the 16-sample loop. Once the shared pool is fully
        // exhausted, ANY coroutine dispatched to Dispatchers.IO can't run -- including
        // the delay(100) polling loop below, on a *later* call to this same function.
        // That's the hang: the watchdog was sharing the resource it was meant to guard.
        //
        // Fix: give the reader/writer coroutines their own small, disposable executor
        // per call instead of the shared pool, so a leaked thread is isolated instead
        // of starving everything else that depends on Dispatchers.IO -- and run the
        // watchdog loop itself on Dispatchers.Default so it can never be a victim of
        // that starvation, even if some other IO-pool consumer we don't control leaks.
        val ioExecutor = Executors.newFixedThreadPool(3)
        val detachedIoDispatcher = ioExecutor.asCoroutineDispatcher()
        val detachedIoScope = CoroutineScope(detachedIoDispatcher + SupervisorJob())

        detachedIoScope.launch {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        synchronized(lock) { stderrBuilder.appendLine(line) }
                        if (logStderrLive && verbose) Log.d(TAG, "  stderr> $line")
                    }
                }
            } catch (e: Exception) {}
        }

        detachedIoScope.launch {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        synchronized(lock) { stdoutBuilder.appendLine(line) }
                    }
                }
            } catch (e: Exception) {}
        }

        stdinSource?.let { uri ->
            detachedIoScope.launch {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            process.outputStream.use { output -> input.copyTo(output) }
                        }
                    }
                } catch (e: Exception) {}
            }
        }

        // Polling loop with a hard timeout, deliberately on Dispatchers.Default (see
        // note above) rather than whichever dispatcher the caller happens to be on.
        // Confirmed working reliably now (log.txt: RPC_WATCHDOG_DONE fires at ~5.01s
        // every time, including the still-hanging case) -- so the remaining hang is
        // somewhere in the cleanup below, which had zero tracing before now.
        val rpcTag = System.identityHashCode(process)
        Log.d(TAG, "RPC_WATCHDOG_START tag=$rpcTag timeoutMs=$timeoutMs stdin=${stdinSource != null}")
        var exitCode: Int? = null
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                try {
                    exitCode = process.exitValue()
                    return@withContext
                } catch (e: IllegalThreadStateException) {
                    // Process still running
                    delay(100)
                }
            }
        }
        Log.d(TAG, "RPC_WATCHDOG_DONE tag=$rpcTag exitCode=$exitCode")

        if (exitCode == null) {
            if (verbose) Log.e(TAG, "TIMED OUT after ${timeoutMs}ms — killing process.")
            Log.d(TAG, "RPC_KILL_REQUESTED tag=$rpcTag")

            // CONFIRMED (log.txt: RPC_WATCHDOG_DONE fires, RPC_KILLED never does):
            // process.destroyForcibly() itself can hang if a writer coroutine is still
            // blocked deep in a native write() on process.outputStream when we call it
            // -- exactly the case a timeout exists to catch. runFfmpegFallbackSegment
            // now avoids this for file:// sources by skipping stdin entirely, but
            // gate0() feeds this with content:// SAF documents (see item.uri there),
            // which can't be opened by a raw path -- so it always goes through stdin
            // and stays exposed to this. Fix at this level instead of relying on every
            // caller happening to have a file:// source: detach the whole kill+close
            // teardown and don't wait on it. We already know exitCode is null; nothing
            // below depends on the kill having actually completed by the time we
            // return.
            CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                try { process.destroyForcibly() } catch (e: Exception) {}
                try { process.inputStream.close() } catch (e: Exception) {}
                try { process.errorStream.close() } catch (e: Exception) {}
                try { process.outputStream.close() } catch (e: Exception) {}
                Log.d(TAG, "RPC_DETACHED_TEARDOWN_DONE tag=$rpcTag")
            }
        } else {
            // Normal exit within budget: the process wasn't killed, so any stdin
            // writer either finished or already hit a broken pipe on its own -- safe
            // to close synchronously here, as already exercised successfully by every
            // non-timeout case in the logs so far.
            try { process.inputStream.close() } catch (e: Exception) {}
            try { process.errorStream.close() } catch (e: Exception) {}
            try { process.outputStream.close() } catch (e: Exception) {}
        }
        Log.d(TAG, "RPC_STREAMS_HANDLED tag=$rpcTag")
        
        // Cancel detached scope but do NOT join it, then tear down its dedicated
        // executor too. shutdownNow() interrupts anything interruptible (helps NIO,
        // won't help a blocked plain java.io read) -- either way, any thread that
        // stays stuck now leaks in isolation instead of starving Dispatchers.IO.
        detachedIoScope.cancel()
        ioExecutor.shutdownNow()
        Log.d(TAG, "RPC_SCOPE_CANCELLED tag=$rpcTag")

        val finalStderr = synchronized(lock) { stderrBuilder.toString() }
        val finalStdout = synchronized(lock) { stdoutBuilder.toString() }
        Log.d(TAG, "RPC_RESULT_BUILT tag=$rpcTag")
        
        return if (exitCode == null) {
            ProcessOutput(exitCode = -1, stdout = finalStdout, stderr = finalStderr, timedOut = true)
        } else {
            ProcessOutput(exitCode!!, finalStdout, finalStderr)
        }
    }


    private suspend fun runFfmpegFallbackSegment(
        index: Int,
        startMs: Long,
        endMs: Long,
        sourceUri: Uri,
        targetCodec: Constants.Transcoder.VideoCodec,
        targetHeight: Int,
        targetBitrateBps: Int,
        outputFile: File,
        verbose: Boolean = true
    ): Boolean {
        val durationS = (endMs - startMs) / 1000.0
        val startS = startMs / 1000.0

        // Find a suitable resolution for tuning args
        val tuningRes = Constants.Transcoder.Resolution.values().find { it.maxHeight == targetHeight }
            ?: Constants.Transcoder.Resolution.HD_720

        // BUG (see log.txt: RPC_WATCHDOG_DONE fires correctly at ~5.01s for [7/16]
        // test_4k.wmv's fallback, but RPC_KILLED -- the very next statement after
        // process.destroyForcibly() in runProcessCapture -- never prints; the app is
        // stuck there until manually killed). destroyForcibly() itself is what hangs.
        //
        // Root cause: this call pipes the source into ffmpeg's stdin via a detached
        // writer coroutine (input.copyTo(output) onto process.outputStream). ffmpeg
        // only reads as much as -t/-ss need before it stops draining the pipe; for a
        // large source (test_4k.wmv, unlike the smaller samples that recovered fine
        // every time) the writer thread is reliably still blocked deep in a native
        // write() on a full pipe when the 5s watchdog fires. Killing a Process while
        // another thread is blocked writing to its stdin is a known rough edge on
        // Android/ART -- destroy()/destroyForcibly() can contend on internal locking
        // with that blocked write and hang the calling thread right along with it,
        // defeating the timeout's entire purpose.
        //
        // Fix: when sourceUri is a plain file:// URI, skip the pipe entirely and let
        // ffmpeg read it directly -- sourceFile.absolutePath is already used this way
        // for the duration/codec probes elsewhere in gate1a(), so there's no reason
        // this call needs stdin for that case. gate0() calls this same function with
        // item.uri, which may be content:// or a remote URL and genuinely can't be
        // opened by path -- that case is untouched and keeps going through stdin.
        val useDirectPath = sourceUri.scheme == "file" && sourceUri.path != null
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner",
            "-ss", startS.toString(),
            "-t", durationS.toString(),
            "-i", if (useDirectPath) sourceUri.path!! else "pipe:0",
            "-an", "-sn",
            "-vf", "scale=-2:$targetHeight",
            "-c:v", targetCodec.ffmpegEncoder,
            "-b:v", "${targetBitrateBps / 1000}k"
        ) + encoderTuningArgs(targetCodec, tuningRes) + listOf("-f", "mp4", outputFile.absolutePath)

        if (verbose) Log.d(TAG, "FFmpeg Fallback Segment $index: ${args.joinToString(" ")}")
        val result = runProcessCapture(
            args,
            timeoutMs = 5_000L,
            logStderrLive = true,
            stdinSource = if (useDirectPath) null else sourceUri,
            verbose = verbose
        )
        val success = result.exitCode == 0 && outputFile.exists() && outputFile.length() > 0
        if (!success) {
            Log.e(TAG, "FFmpeg Fallback Segment $index FAILED: exitCode=${result.exitCode}")
        } else {
            if (verbose) Log.d(TAG, "FFmpeg Fallback Segment $index SUCCESS")
        }
        return success
    }


    private fun updateSeekBarPosition(position: String, duration: String) {
    }
    fun release() {
        resetTranscoderActivity()
        scope.cancel()
    }
    private fun resetTranscoderActivity() {
        stopPlaying()
    }
    private fun stopPlaying(){
        Log.d(TAG, "stopPlaying")
        stopPlayingLocal()
    }
    private fun stopPlayingLocal(){
        repo.setSeekBarDuration("00:00:00")
        repo.setSeekBarPosition("00:00:00")
    }
}
