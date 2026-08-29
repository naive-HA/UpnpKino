package acab.naiveha.upnpkino

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
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

    companion object {
        private const val TAG = "TranscoderController"
        private const val RETRY_DELAY_MS = 500L
        private const val CAPABILITY_SCHEMA_VERSION = 1

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
//                val fresh = runFullCapabilityCheck()
//                val compiled = inspectCompiledCapabilities()


                val encoders = runProcessCapture(listOf(ffmpegPath, "-hide_banner", "-encoders")).stdout
                val muxers = runProcessCapture(listOf(ffmpegPath, "-hide_banner", "-muxers")).stdout

                // One-time dump of every private option these two encoder wrappers expose.
                // We've been setting level/profile/bf/g/pix_fmt/r from general H.264/HEVC
                // knowledge, but never actually checked what ffmpeg's own h264_mediacodec/
                // hevc_mediacodec wrappers say they support -- there could be a timeout,
                // an async-vs-sync mode toggle, or something else relevant to the hang
                // that we don't know exists yet. Cheap to check.
                val h264Help = runProcessCapture(listOf(ffmpegPath, "-hide_banner", "-h", "encoder=h264_mediacodec")).stdout
                Log.d(TAG, "h264_mediacodec private options:\n$h264Help")
                val hevcHelp = runProcessCapture(listOf(ffmpegPath, "-hide_banner", "-h", "encoder=hevc_mediacodec")).stdout
                Log.d(TAG, "hevc_mediacodec private options:\n$hevcHelp")

                val compiledVideo = Constants.Transcoder.VideoCodec.entries
                    .filter { Regex("\\b${Regex.escape(it.ffmpegEncoder)}\\b").containsMatchIn(encoders) }.toSet()
                val compiledAudio = Constants.Transcoder.AudioCodec.entries
                    .filter { Regex("\\b${Regex.escape(it.ffmpegEncoder)}\\b").containsMatchIn(encoders) }.toSet()
                val compiledMuxers = Constants.Transcoder.Container.entries
                    .filter { Regex("\\b${Regex.escape(it.ffmpegMuxer)}\\b").containsMatchIn(muxers) }.toSet()

//                val compiled = RuntimeCapabilities(compiledVideo, compiledAudio, compiledMuxers)
//
//                Log.d(TAG, "Compiled capabilities: video=${compiled.compiledVideoEncoders} " +
//                        "audio=${compiled.compiledAudioEncoders} muxers=${compiled.compiledMuxers}")
                Log.d(TAG, "Starting to check video codec")
                /*
                val verifiedVideo = mutableMapOf<Constants.Transcoder.VideoCodec, Set<Constants.Transcoder.Resolution>>()
                for (codec in compiledVideo) {
                    Log.d(TAG, "Checking $codec")
                    val working = Constants.Transcoder.Resolution.entries.filter { testVideoEncoder(codec, it) }.toSet()
                    Log.d(TAG, "Checking $codec at: $working")
                    verifiedVideo[codec] = working
                    Log.d(TAG, "Live-verified $codec at: $working")
                }
                */

                // Gate 0: Confirm hardware itself can produce output via Media3 Transformer
                Log.d(TAG, "GATE 0: Starting Media3 Transformer health check")
                
                var sampleUri: Uri? = null
                withTimeoutOrNull(30_000) { // Wait up to 30s for indexing
                    while (sampleUri == null) {
                        sampleUri = getSampleVideoUri()
                        if (sampleUri == null) delay(1000)
                    }
                }
                
                Log.d(TAG, "GATE 0: Using sample video: $sampleUri")
                if (sampleUri != null) {
                    for (codec in compiledVideo) {
                        val media3Works = testVideoEncoderViaMedia3(codec, sampleUri!!)
                        Log.d(TAG, "GATE 0 RESULT: Codec $codec via Media3 Transformer: ${if (media3Works) "PASS" else "FAIL"}")
                        delay(2000)
                    }
                } else {
                    Log.e(TAG, "GATE 0 ABORTED: No video file found for testing after 30s.")
                }
                // For now, we stop here as per Gate 0 instructions.
                // The verifiedVideo map remains empty or we can fill it based on Media3 results if we want to proceed, 
                // but Gate 0 is about confirmation.
                val verifiedVideo = mutableMapOf<Constants.Transcoder.VideoCodec, Set<Constants.Transcoder.Resolution>>()


                val verifiedAudio = compiledAudio.filter { testAudioEncoder(it) }.toSet()
                Log.d(TAG, "Live-verified audio: $verifiedAudio")

                val fullTranscodeItem = getSampleVideoItem()
                if (fullTranscodeItem != null) {
                    debugMeasureFullTranscodeH264(fullTranscodeItem)
                }


//                val fresh = CapabilitySnapshot(
//                    appVersionCode = currentAppVersionCode(),
//                    ffmpegBinarySize = ffmpegFile.length(),
//                    ffmpegBinaryMtime = ffmpegFile.lastModified(),
//                    osFingerprint = Build.FINGERPRINT,
//                    checkedAtMillis = System.currentTimeMillis(),
//                    compiled = compiled,
//                    verifiedVideo = verifiedVideo,
//                    verifiedAudio = verifiedAudio
//                )
//                upnpService.preferences.saveTranscoderCapabilities(fresh.toJson())





//                val cached = upnpService.preferences.getTranscoderCapabilities()?.let { CapabilitySnapshot.fromJson(it) }
//                capabilities = if (cached != null && isValidCapabilities(cached)) {
//                    Log.d(TAG, "Trusting cached transcode capabilities from ${cached.checkedAtMillis}")
//                    cached
//                } else {
//                    Log.d(TAG, "Running fresh transcode capability check")
//                    val fresh = runFullCapabilityCheck()
//                    upnpService.preferences.saveTranscoderCapabilities(fresh.toJson())
//                    fresh
//                }
//                Log.d(TAG, "Transcode capabilities ready: video=${capabilities?.verifiedVideo} audio=${capabilities?.verifiedAudio}")
            } catch (e: Exception) {
                Log.e(TAG, "Capability check failed: ${e.message}", e)
            }

//            checkCapabilities()
        }
        scope.launch {
            repo.selectedMediaFileId.collect { selectedMediaFileId ->
                Log.d(TAG, "Selected media file changed: $selectedMediaFileId")
                if (selectedMediaFileId == null) {
                    resetTranscoderActivity()
                } else {
                    //decide best video and audio codec
                }
            }
        }
        scope.launch {
            repo.transcodingFlag.collect { transcodingFlag ->
            }
        }
        scope.launch {
            repo.isTranscoderActivityVisible.collect { isTranscoderActivityVisible ->
            }
        }
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
    private suspend fun debugMeasureFullTranscodeH264(item: MediaCollection.MediaNode.Item) = withContext(Dispatchers.Main) {
        val startTime = System.currentTimeMillis()

        // Clear cache at the start
        Log.d(TAG, "Clearing cache directory: ${workDir.absolutePath}")
        workDir.listFiles()?.forEach { it.deleteRecursively() }

        val collection = UpnpRepository.kinoService.sharedMediaCollection.value
        val parentNode = collection[item.parent] as? MediaCollection.MediaNode.Container
        if (parentNode == null) {
            Log.e(TAG, "Segmented Transcode: Could not find parent folder for ${item.name}")
            return@withContext
        }

        val parentDoc = DocumentFile.fromTreeUri(context, parentNode.uri)
        if (parentDoc == null || !parentDoc.isDirectory) {
            Log.e(TAG, "Segmented Transcode: Parent folder URI is invalid or not a directory: ${parentNode.uri}")
            return@withContext
        }

        val segmentDurationMs = 30_000L
        val totalDurationMs = item.durationMs
        val segmentsCount = if (totalDurationMs > 0) ((totalDurationMs + segmentDurationMs - 1) / segmentDurationMs).toInt() else 0
        val tsFiles = mutableListOf<File>()

        Log.d(TAG, "Starting segmented transcode for ${item.name}. Total duration: ${totalDurationMs}ms, Segments: $segmentsCount")

        if (segmentsCount == 0) {
            Log.e(TAG, "Segmented Transcode: Item duration is 0 or unknown.")
            return@withContext
        }

        var allSegmentsSuccessful = true
        for (i in 0 until segmentsCount) {
            Log.d(TAG, "Segmented Transcode: Progress $i / $segmentsCount chunks done")
            val startMs = i * segmentDurationMs
            val endMs = min((i + 1) * segmentDurationMs, totalDurationMs)
            val chunkMp4 = File(workDir, "chunk_$i.mp4")
            if (chunkMp4.exists()) chunkMp4.delete()

            Log.d(TAG, "Segment $i START: $startMs ms to $endMs ms -> ${chunkMp4.name}")

            val segmentSuccess = suspendCancellableCoroutine<Boolean> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(
                                VideoEncoderSettings.Builder()
                                    .setBitrate(2_500_000)
                                    .build()
                            )
                            .build()
                    )
                    .build()

                val progressJob = scope.launch(Dispatchers.Main) {
                    val progressHolder = ProgressHolder()
                    var lastLogTime = 0L
                    while (isActive) {
                        delay(1000)
                        val progressState = transformer.getProgress(progressHolder)
                        if (progressState == Transformer.PROGRESS_STATE_AVAILABLE) {
                            val now = System.currentTimeMillis()
                            if (now - lastLogTime >= 5000) {
                                Log.d(TAG, "Segmented Transcode: Progress ${i + 1} / $segmentsCount")
                                lastLogTime = now
                            }
                        }
                    }
                }

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        progressJob.cancel()
                        Log.d(TAG, "Segment $i COMPLETED. File size: ${chunkMp4.length()}")
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        progressJob.cancel()
                        Log.e(TAG, "Segment $i FAILED: ${exportException.message}", exportException)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
                transformer.addListener(listener)

                try {
                    val mediaItem = MediaItem.Builder()
                        .setUri(item.uri)
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
                                listOf<Effect>(Presentation.createForHeight(720))
                            )
                        )
                        .build()

                    transformer.start(editedMediaItem, chunkMp4.absolutePath)

                    continuation.invokeOnCancellation {
                        progressJob.cancel()
                        transformer.cancel()
                    }
                } catch (e: Exception) {
                    progressJob.cancel()
                    Log.e(TAG, "Segment $i Setup Error: ${e.message}", e)
                    if (continuation.isActive) continuation.resume(false)
                }
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
                    chunkMp4.delete()
                } else {
                    Log.e(TAG, "Segment $i: Failed to convert to TS. Stderr: ${convertResult.stderr}")
                    allSegmentsSuccessful = false
                    break
                }
                
                // Small delay between segments to allow hardware/buffers to breathe
                delay(500)
            } else {
                allSegmentsSuccessful = false
                Log.e(TAG, "Segmented Transcode: Stopping due to failure in segment $i")
                break
            }
        }

        val outputMp4 = File(workDir, "segmented_output.mp4")
        if (outputMp4.exists()) outputMp4.delete()

        if (tsFiles.isNotEmpty()) {
            val finalOutputName = if (allSegmentsSuccessful) "output.mp4" else "partial_output.mp4"
            Log.d(TAG, "Segmented Transcode: Concatenating ${tsFiles.size} TS chunks into $finalOutputName")

            val combinedTs = File(workDir, "combined.ts")
            try {
                combinedTs.outputStream().use { output ->
                    tsFiles.forEach { tsFile ->
                        tsFile.inputStream().use { input ->
                            input.copyTo(output)
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
                } else {
                    Log.e(TAG, "Segmented Transcode: Remux FAILED: exitCode=${remuxResult.exitCode}")
                }
            } finally {
                if (combinedTs.exists()) combinedTs.delete()
            }

            // Cleanup
            tsFiles.forEach { if (it.exists()) it.delete() }
            if (outputMp4.exists()) outputMp4.delete()
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
        stdinSource: Uri? = null
    ): ProcessOutput = coroutineScope {
        Log.d(TAG, "Starting ProcessBuilder: ${args.joinToString(" ")}")
        val process = ProcessBuilder(args).start()
        Log.d(TAG, "ProcessBuilder finished (process started, pid unknown on this API level)")

        val stderrBuilder = StringBuilder()
        val stderrDeferred = async(Dispatchers.IO) {
            try {
                process.errorStream.bufferedReader().forEachLine { line ->
                    stderrBuilder.appendLine(line)
                    if (logStderrLive) Log.d(TAG, "  stderr> $line")
                }
            } catch (e: IOException) {
                // Expected when destroyForcibly() closes this stream out from under an
                // in-progress read (see the timeout branch below). Whatever was captured
                // before the close is already in stderrBuilder -- nothing lost.
                Log.d(TAG, "stderr read stopped, likely process was killed: ${e.message}")
            }
        }
        val stdoutDeferred = async(Dispatchers.IO) {
            try {
                process.inputStream.bufferedReader().readText()
            } catch (e: IOException) {
                Log.d(TAG, "stdout read stopped, likely process was killed: ${e.message}")
                ""
            }
        }
        // Feeds the real sample video (sampleVideoUri) into the process's stdin for
        // the video-encoder checks below -- same "open a ParcelFileDescriptor and copy
        // it to the process on a background thread" approach MediaCollection.probeWithFfprobe
        // already uses to hand real files to ffprobe. Null for every audio check (and for
        // a video stage if no sample video was found), which leaves this a no-op and
        // stdin untouched, exactly like before this parameter existed.
        val stdinDeferred = stdinSource?.let { uri ->
            async(Dispatchers.IO) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            process.outputStream.use { output ->
                                input.copyTo(output)
                            }
                        }
                    } ?: Log.w(TAG, "Could not open stdin source for video encoder check: $uri")
                } catch (e: Exception) {
                    // Covers both a normal broken-pipe IOException (ffmpeg closed its end
                    // once -t/-frames:v was satisfied, before we finished writing -- expected,
                    // same situation the stderr reader handles above) and a SecurityException
                    // if SAF access to the sample video was revoked mid-session.
                    Log.d(TAG, "stdin write for video encoder check stopped: ${e.message}")
                }
            }
        }

        Log.d(TAG, "1: waiting for process, timeout=${timeoutMs}ms")
        val exitCode = withTimeoutOrNull(timeoutMs) {
            // process.waitFor() is a blocking, non-suspending call -- plain withContext()
            // cancellation does nothing to it (coroutine cancellation is cooperative, and a
            // raw blocking call never checks for it). runInterruptible actually delivers
            // Thread.interrupt() to the blocked thread on cancellation, which Process.waitFor()
            // is documented to honor by throwing InterruptedException. Without this, a hung
            // process makes withTimeoutOrNull itself hang too -- it can mark the job
            // cancelled, but can't proceed until that job actually finishes.
            runInterruptible(Dispatchers.IO) { process.waitFor() }
        }

        if (exitCode == null) {
            Log.w(TAG, "TIMED OUT after ${timeoutMs}ms — killing process. stderr collected so far:\n$stderrBuilder")
            process.destroyForcibly()
            // No need to cancel the readers explicitly: destroyForcibly() closes their
            // streams, which unblocks them (via the caught IOException above) on its own.
            stdoutDeferred.join()
            stderrDeferred.join()
            stdinDeferred?.join()
            return@coroutineScope ProcessOutput(exitCode = -1, stdout = "", stderr = stderrBuilder.toString(), timedOut = true)
        }
        Log.d(TAG, "2: process finished, exitCode=$exitCode")
        stderrDeferred.join()
        val out = stdoutDeferred.await()
        stdinDeferred?.join()
        Log.d(TAG, "3: stdout=$out")
        Log.d(TAG, "4: stderr=$stderrBuilder")
        ProcessOutput(exitCode, out, stderrBuilder.toString())
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
