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
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageWriter
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.transformer.SurfaceAssetLoader
import java.io.DataInputStream
import java.io.EOFException
import kotlinx.coroutines.CompletableDeferred

class TranscoderController(val context: Context, val upnpService: UpnpService) {

    companion object {
        private const val TAG = "TranscoderController"
        private const val RETRY_DELAY_MS = 500L
        private const val CAPABILITY_SCHEMA_VERSION = 1

        private const val HW_PROBE_FRAME_LIMIT = 60
        private const val HW_PROBE_TIME_LIMIT_S = 2.0
        private const val HW_PROBE_TIMEOUT_MS = 10_000L

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

    /**
     * Set once by [probeHardwareFfmpegPipeline] during init and cached for the
     * life of this controller -- true routes real transcodes through ffmpeg's
     * own hardware decode+encode (fast; confirmed reliable on Qualcomm), false
     * routes them through the safe path (Media3 Transformer for video, ffmpeg
     * for audio+remux; confirmed reliable everywhere, including the Tensor
     * devices where the fast path hangs indefinitely -- see the probe's own
     * doc comment for the history). Null means the probe hasn't run yet;
     * callers should treat that as "use the safe path" rather than block on it.
     */
    @Volatile
    var useHardwareFfmpegPipeline: Boolean? = null
        private set

    init {
        scope.launch {
            try {
                Log.d(TAG, "Running quick hardware capability check (720p synth)")
                val res720 = Constants.Transcoder.Resolution.HD_720
                val probeFile = File(ffmpegFile.parentFile, "hevc_720p_short_ac3.so")
                
                if (!probeFile.exists()) {
                    Log.w(TAG, "Probe file missing, assuming hardware pipeline unsupported")
                    useHardwareFfmpegPipeline = false
                } else {
                    val outcome = runHwProbe(
                        label = "QUICK_720p_PROBE",
                        inputPath = probeFile.absolutePath,
                        limitMode = LimitMode.TIME,
                        includeAudio = true,
                        targetAudioChannels = 2,
                        targetCodec = Constants.Transcoder.VideoCodec.H264, // Synthetic is HEVC, test re-encoding to H264
                        resolution = res720
                    )
                    useHardwareFfmpegPipeline = (outcome == HwProbeOutcome.PASS)
                }
                Log.d(TAG, "Hardware capability check complete -- useHardwareFfmpegPipeline=$useHardwareFfmpegPipeline")

                // Unified test loop for library files
                val libraryItems = UpnpRepository.kinoService.sharedMediaCollection.value.values
                    .filterIsInstance<MediaCollection.MediaNode.Item>()

                Log.d(TAG, "UNIFIED TEST: Starting library-wide transcode test (10s segments)")
                for (item in libraryItems) {
                    // Smart detection of unstreamable containers
                    if (!item.isFastStart) {
                        Log.d(TAG, "UNIFIED TEST: Skipping '${item.name}' (moov atom at end of file, piping not possible)")
                        continue
                    }

                    val targetVideoCodec = if (item.videoCodec?.contains("avc", ignoreCase = true) == true || 
                                              item.videoCodec?.contains("h264", ignoreCase = true) == true) {
                        Constants.Transcoder.VideoCodec.HEVC
                    } else {
                        Constants.Transcoder.VideoCodec.H264
                    }

                    val testOutputFile = File(workDir, "unified_test_${item.id}.mp4")
                    if (testOutputFile.exists()) testOutputFile.delete()

                    val startTime = System.currentTimeMillis()
                    val testDurationMs = 10_000L
                    
                    val success = transcodeMediaFile(
                        item = item,
                        startTimeMs = 0,
                        durationMs = testDurationMs,
                        targetVideoCodec = targetVideoCodec,
                        targetResolution = null, // Keep original
                        targetVideoBitrate = null, // Keep original
                        targetAudioCodec = Constants.Transcoder.AudioCodec.AAC,
                        targetAudioBitrate = null, // Keep original
                        outputFile = testOutputFile
                    )
                    val totalTime = System.currentTimeMillis() - startTime

                    if (success) {
                        val rtFactor = totalTime.toDouble() / testDurationMs
                        Log.d(TAG, "UNIFIED TEST METRICS: '${item.name}'")
                        Log.d(TAG, "  > Target Codec:  ${targetVideoCodec.name}")
                        Log.d(TAG, "  > Total Time:    ${totalTime}ms")
                        Log.d(TAG, "  > RT FACTOR:     ${String.format("%.2f", rtFactor)}x (10.0s segment)")
                    } else {
                        Log.e(TAG, "UNIFIED TEST RESULT: '${item.name}' -> FAIL")
                    }

                    if (testOutputFile.exists()) testOutputFile.delete()
                    delay(1000.milliseconds)
                }
                Log.d(TAG, "UNIFIED TEST: Finished")
            } catch (e: Exception) {
                Log.e(TAG, "Init/Test crashed: ${e.message}", e)
                useHardwareFfmpegPipeline = false
            }
        }
    }

    private fun isValidCapabilities(cached: CapabilitySnapshot): Boolean {
        val sameApp = cached.appVersionCode == currentAppVersionCode()
        val sameBinary = cached.ffmpegBinarySize == ffmpegFile.length() &&
            cached.ffmpegBinaryMtime == ffmpegFile.lastModified()
        val sameOs = cached.osFingerprint == Build.FINGERPRINT
        return sameApp && sameBinary && sameOs
    }

    private fun encoderLevelArg(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): String =
        when (codec) {
            Constants.Transcoder.VideoCodec.H264 -> when (resolution) {
                Constants.Transcoder.Resolution.SD -> "30"
                Constants.Transcoder.Resolution.HD_720 -> "31"
                Constants.Transcoder.Resolution.HD_1080 -> "40"
                Constants.Transcoder.Resolution.UHD_4K -> "51"
            }
            Constants.Transcoder.VideoCodec.HEVC -> when (resolution) {
                Constants.Transcoder.Resolution.SD -> "60"
                Constants.Transcoder.Resolution.HD_720 -> "93"
                Constants.Transcoder.Resolution.HD_1080 -> "123"
                Constants.Transcoder.Resolution.UHD_4K -> "153"
            }
        }

    private enum class HwProbeOutcome { PASS, FAIL, HANG }
    private enum class LimitMode { FRAME_COUNT, TIME }

    private fun limitArgs(mode: LimitMode): List<String> = when (mode) {
        LimitMode.FRAME_COUNT -> listOf("-frames:v", HW_PROBE_FRAME_LIMIT.toString(), "-t", "3")
        LimitMode.TIME -> listOf("-t", HW_PROBE_TIME_LIMIT_S.toString())
    }

    private fun testHeightFor(resolution: Constants.Transcoder.Resolution): Int =
        if (resolution == Constants.Transcoder.Resolution.HD_1080) 1088 else resolution.maxHeight

    private suspend fun runHwProbe(
        label: String,
        inputPath: String,
        stdinSource: Uri? = null,
        limitMode: LimitMode,
        includeAudio: Boolean,
        targetAudioChannels: Int? = 2,
        targetCodec: Constants.Transcoder.VideoCodec = Constants.Transcoder.VideoCodec.HEVC,
        resolution: Constants.Transcoder.Resolution,
        timeoutMs: Long = HW_PROBE_TIMEOUT_MS
    ): HwProbeOutcome {
        if (stdinSource == null && !File(inputPath).exists()) return HwProbeOutcome.FAIL

        val streamArgs = if (includeAudio) {
            val acArgs = if (targetAudioChannels == 6) listOf("-ac", "6", "-channel_layout", "5.1")
                         else listOf("-ac", "2")
            listOf("-map", "0:v:0", "-map", "0:a:0?", "-c:a", Constants.Transcoder.AudioCodec.AAC.ffmpegEncoder) + acArgs
        } else listOf("-an", "-sn")
        
        val targetHeight = testHeightFor(resolution)
        val targetWidth = if (resolution == Constants.Transcoder.Resolution.HD_1080) 1920 else resolution.maxWidth

        val args = mutableListOf(
            ffmpegPath, "-y", "-hide_banner", "-v", "debug",
            "-init_hw_device", "mediacodec=mc", "-hwaccel", "mediacodec", "-ndk_codec", "1",
            "-i", inputPath
        )
        args.addAll(streamArgs)
        args.addAll(limitArgs(limitMode))
        args.addAll(listOf(
            "-vf", "scale=$targetWidth:$targetHeight",
            "-c:v", targetCodec.ffmpegEncoder,
            "-b:v", "${resolution.targetBitrateKbps}k",
            "-bitrate_mode", "vbr", "-ndk_codec", "1"
        ))
        args.addAll(encoderTuningArgs(targetCodec, resolution).filter { it != "-pix_fmt" && it != "yuv420p" })
        args.addAll(listOf("-f", "null", "-"))

        val startTime = System.currentTimeMillis()
        val result = runProcessCapture(args, timeoutMs = timeoutMs, logStderrLive = true, verbose = false, stdinSource = stdinSource)
        val totalTime = System.currentTimeMillis() - startTime

        val outcome = when {
            result.timedOut -> HwProbeOutcome.HANG
            result.exitCode == 0 -> HwProbeOutcome.PASS
            else -> HwProbeOutcome.FAIL
        }

        if (outcome == HwProbeOutcome.PASS) {
            val actualLimitS = when (limitMode) {
                LimitMode.TIME -> {
                    val tIdx = args.indexOf("-t")
                    if (tIdx != -1 && tIdx + 1 < args.size) args[tIdx + 1].toDoubleOrNull() ?: HW_PROBE_TIME_LIMIT_S
                    else HW_PROBE_TIME_LIMIT_S
                }
                LimitMode.FRAME_COUNT -> {
                    val fIdx = args.indexOf("-frames:v")
                    val frames = if (fIdx != -1 && fIdx + 1 < args.size) args[fIdx + 1].toIntOrNull() ?: HW_PROBE_FRAME_LIMIT
                                 else HW_PROBE_FRAME_LIMIT
                    frames / 30.0 
                }
            }
            val realTimeFactor = totalTime / 1000.0 / actualLimitS
            Log.d(TAG, "GATE 2 METRICS: '$label' > RT FACTOR: ${String.format("%.2f", realTimeFactor)}x")
        }
        return outcome
    }

    private suspend fun findRealHevcAc3MkvSample(): MediaCollection.MediaNode.Item? {
        val candidates = UpnpRepository.kinoService.sharedMediaCollection.value.values
            .filterIsInstance<MediaCollection.MediaNode.Item>()
            .filter { it.name.endsWith(".mkv", ignoreCase = true) && it.videoCodec == MediaFormat.MIMETYPE_VIDEO_HEVC }
        for (item in candidates) {
            val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"
            val args = listOf(ffprobePath, "-hide_banner", "-v", "error", "-select_streams", "a", "-show_entries", "stream=codec_name", "-of", "csv=p=0", inputPath ?: "pipe:0")
            val result = runProcessCapture(args, timeoutMs = 5_000L, verbose = false, stdinSource = if (inputPath == "pipe:0") item.uri else null)
            if (result.exitCode == 0 && result.stdout.lines().any { it.trim().equals("ac3", ignoreCase = true) }) return item
        }
        return null
    }

    private data class SynthCase(val tag: String, val file: File, val hasAudio: Boolean, val resolution: Constants.Transcoder.Resolution)

    private suspend fun runIsolatedHd1080Probe(): HwProbeOutcome {
        val file = File(ffmpegFile.parentFile, "hevc_1080p_short_noaudio.so")
        return runHwProbe(label = "ISOLATED_1080p_long_noaudio", inputPath = file.absolutePath, limitMode = LimitMode.TIME, includeAudio = false, targetCodec = Constants.Transcoder.VideoCodec.HEVC, resolution = Constants.Transcoder.Resolution.HD_1080)
    }

    private suspend fun runComprehensiveHardwarePipelineDiagnostic(): Boolean {
        val samplesDir = ffmpegFile.parentFile
        val results = linkedMapOf<String, HwProbeOutcome>()
        val res4k = Constants.Transcoder.Resolution.UHD_4K
        val res1080 = Constants.Transcoder.Resolution.HD_1080
        val res720 = Constants.Transcoder.Resolution.HD_720
        val synthCases = listOf(
            SynthCase("4k_short_noaudio", File(samplesDir, "hevc_4k_short_noaudio.so"), false, res4k),
            SynthCase("4k_short_ac3", File(samplesDir, "hevc_4k_short_ac3.so"), true, res4k),
            SynthCase("1080p_short_noaudio", File(samplesDir, "hevc_1080p_short_noaudio.so"), false, res1080),
            SynthCase("1080p_short_ac3", File(samplesDir, "hevc_1080p_short_ac3.so"), true, res1080),
            SynthCase("720p_short_noaudio", File(samplesDir, "hevc_720p_short_noaudio.so"), false, res720),
            SynthCase("720p_short_ac3", File(samplesDir, "hevc_720p_short_ac3.so"), true, res720)
        )
        for (case in synthCases) {
            for (mode in LimitMode.entries) {
                val label = "${case.tag}/$mode"
                results[label] = runHwProbe(label, case.file.absolutePath, limitMode = mode, includeAudio = case.hasAudio, targetAudioChannels = if (case.hasAudio) 2 else null, targetCodec = Constants.Transcoder.VideoCodec.HEVC, resolution = case.resolution)
                delay(1000.milliseconds)
            }
        }
        return results.values.any { it == HwProbeOutcome.PASS }
    }

    private fun encoderTuningArgs(codec: Constants.Transcoder.VideoCodec, resolution: Constants.Transcoder.Resolution): List<String> {
        val profile = when (codec) {
            Constants.Transcoder.VideoCodec.H264 -> "high"
            Constants.Transcoder.VideoCodec.HEVC -> "main"
        }
        return listOf("-profile:v", profile, "-level", encoderLevelArg(codec, resolution), "-pix_fmt", "yuv420p", "-r", "24", "-g", "24", "-bf", "0")
    }

    /**
     * Unified orchestration function to transcode a media segment using the 
     * most efficient path for the current device and source media.
     */
    suspend fun transcodeMediaFile(
        item: MediaCollection.MediaNode.Item,
        startTimeMs: Long,
        durationMs: Long,
        targetVideoCodec: Constants.Transcoder.VideoCodec,
        targetResolution: Constants.Transcoder.Resolution? = null,
        targetVideoBitrate: Int? = null,
        targetAudioCodec: Constants.Transcoder.AudioCodec,
        targetAudioBitrate: Int? = null,
        outputFile: File,
        timeoutS: Long? = null
    ): Boolean {
        val resolution = targetResolution ?: run {
            val parts = item.resolution.split("x")
            val height = parts.getOrNull(1)?.toIntOrNull() ?: 1080
            Constants.Transcoder.Resolution.entries.sortedBy { it.maxHeight }
                .find { it.maxHeight >= height } ?: Constants.Transcoder.Resolution.HD_1080
        }
        val videoBitrateKbps = targetVideoBitrate ?: resolution.targetBitrateKbps
        
        val videoMime = item.videoCodec?.lowercase() ?: ""
        val isLegacyVideo = videoMime.contains("mpeg4") || videoMime.contains("wmv") || item.name.lowercase().endsWith(".avi")
        val is4k = resolution == Constants.Transcoder.Resolution.UHD_4K
        val hasDtsAudio = item.audioCodec?.contains("dts", ignoreCase = true) == true || 
                          item.audioTracks.any { it.codec.contains("dts", ignoreCase = true) }

        val strategy = when {
            is4k -> "full_media3"
            isLegacyVideo -> "hybrid"
            hasDtsAudio -> "hybrid"
            useHardwareFfmpegPipeline == true -> "hw_ffmpeg"
            else -> "hybrid"
        }

        Log.d(TAG, "Transcoding '${item.name}' using strategy: $strategy (timeout: ${timeoutS}s)")
        
        val timeoutMs = timeoutS?.let { it * 1000 } ?: 300_000L // Default to 5m if null
        
        return when (strategy) {
            "hw_ffmpeg" -> transcodeViaHardwareFfmpeg(item, targetVideoCodec, resolution, videoBitrateKbps, targetAudioCodec, targetAudioBitrate, outputFile, startTimeMs, durationMs, timeoutMs = timeoutMs)
            "full_media3" -> transcodeViaFullMedia3Path(item, targetVideoCodec, resolution, outputFile, durationMs, startTimeMs, timeoutMs = timeoutMs)
            else -> transcodeViaSafePath(item, targetVideoCodec, resolution, outputFile, durationMs, startTimeMs, timeoutMs = timeoutMs)
        }
    }

    private suspend fun transcodeViaHardwareFfmpeg(
        item: MediaCollection.MediaNode.Item,
        targetVideo: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        videoBitrateKbps: Int,
        targetAudio: Constants.Transcoder.AudioCodec,
        audioBitrateBps: Int?,
        outputFile: File,
        startTimeMs: Long,
        durationMs: Long,
        timeoutMs: Long
    ): Boolean {
        val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"
        val safeInputPath = inputPath ?: "pipe:0"
        val targetHeight = testHeightFor(resolution)
        val targetWidth = if (resolution == Constants.Transcoder.Resolution.HD_1080) 1920 else resolution.maxWidth

        val args = mutableListOf(ffmpegPath, "-y", "-hide_banner", "-hwaccel", "mediacodec", "-ndk_codec", "1")
        if (startTimeMs > 0) args.addAll(listOf("-ss", (startTimeMs / 1000.0).toString()))
        args.addAll(listOf("-i", safeInputPath))
        args.addAll(listOf("-map", "0:v:0", "-vf", "scale=$targetWidth:$targetHeight", "-c:v", targetVideo.ffmpegEncoder, "-b:v", "${videoBitrateKbps}k", "-bitrate_mode", "vbr", "-ndk_codec", "1"))
        args.addAll(encoderTuningArgs(targetVideo, resolution).filter { it != "-pix_fmt" && it != "yuv420p" })
        if ((item.channelCount ?: 0) > 0) {
            args.addAll(listOf("-map", "0:a:0?", "-c:a", targetAudio.ffmpegEncoder))
            if (audioBitrateBps != null) args.addAll(listOf("-b:a", audioBitrateBps.toString()))
            if ((item.channelCount ?: 0) >= 6) args.addAll(listOf("-ac", "6", "-channel_layout", "5.1"))
            else args.addAll(listOf("-ac", "2"))
        }
        args.addAll(listOf("-t", (durationMs / 1000.0).toString(), "-f", "mp4", outputFile.absolutePath))

        val result = runProcessCapture(args, timeoutMs = timeoutMs, logStderrLive = true, stdinSource = if (safeInputPath == "pipe:0") item.uri else null)
        return result.exitCode == 0 && outputFile.exists() && outputFile.length() > 0
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcodeViaFullMedia3Path(
        item: MediaCollection.MediaNode.Item,
        targetCodec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        outputFile: File,
        limitDurationMs: Long? = null,
        offsetMs: Long = 0,
        timeoutMs: Long
    ): Boolean {
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs.milliseconds) {
                suspendCancellableCoroutine { continuation ->
                    val videoMimeType = when (targetCodec) {
                        Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264
                        Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265
                    }
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(videoMimeType)
                        .setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(resolution.targetBitrateKbps * 1000).setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR).build()).build())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (continuation.isActive) continuation.resume(true) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                Log.e(TAG, "Full path: Transformer failed: ${exportException.message}", exportException)
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }).build()
                    try {
                        val mediaItemBuilder = MediaItem.Builder().setUri(item.uri).setMimeType(item.mimeType)
                        if (limitDurationMs != null || offsetMs > 0) {
                            mediaItemBuilder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(offsetMs)
                                .setEndPositionMs(if (limitDurationMs != null) offsetMs + limitDurationMs else C.TIME_END_OF_SOURCE)
                                .build())
                        }
                        val mediaItem = mediaItemBuilder.build()
                        val editedMediaItem = EditedMediaItem.Builder(mediaItem).build()
                        transformer.start(editedMediaItem, outputFile.absolutePath)
                        continuation.invokeOnCancellation { CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch { try { transformer.cancel() } catch (e: Exception) {} } }
                    } catch (e: Exception) {
                        Log.e(TAG, "Full path: Transformer setup failed: ${e.message}", e)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun transcodeViaSafePath(
        item: MediaCollection.MediaNode.Item,
        targetCodec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        outputFile: File,
        limitDurationMs: Long? = null,
        offsetMs: Long = 0,
        timeoutMs: Long
    ): Boolean {
        val videoOnlyFile = File(workDir, "safepath_video_${item.id}.mp4")
        val audioFile = File(workDir, "safepath_audio_${item.id}.m4a")
        listOf(videoOnlyFile, audioFile).forEach { if (it.exists()) it.delete() }

        val videoOk = withContext(Dispatchers.Main) {
            withTimeoutOrNull(timeoutMs.milliseconds) {
                suspendCancellableCoroutine { continuation ->
                    val videoMimeType = when (targetCodec) {
                        Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264
                        Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265
                    }
                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(videoMimeType)
                        .setEncoderFactory(DefaultEncoderFactory.Builder(context).setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(resolution.targetBitrateKbps * 1000).setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR).build()).build())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { if (continuation.isActive) continuation.resume(true) }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                Log.e(TAG, "Safe path: Transformer export failed: ${exportException.message}", exportException)
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }).build()
                    try {
                        val mediaItemBuilder = MediaItem.Builder().setUri(item.uri).setMimeType(item.mimeType)
                        if (limitDurationMs != null || offsetMs > 0) {
                            mediaItemBuilder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(offsetMs)
                                .setEndPositionMs(if (limitDurationMs != null) offsetMs + limitDurationMs else C.TIME_END_OF_SOURCE)
                                .build())
                        }
                        val mediaItem = mediaItemBuilder.build()
                        val editedMediaItem = EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).build()
                        transformer.start(editedMediaItem, videoOnlyFile.absolutePath)
                        continuation.invokeOnCancellation { CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch { try { transformer.cancel() } catch (e: Exception) {} } }
                    } catch (e: Exception) {
                        Log.e(TAG, "Safe path: Transformer setup failed: ${e.message}", e)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
        }
        if (!videoOk || !videoOnlyFile.exists() || videoOnlyFile.length() == 0L) {
            Log.e(TAG, "Safe path: video leg failed.")
            videoOnlyFile.delete()
            return false
        }

        if (!hasAudioTrack(item)) {
            videoOnlyFile.copyTo(outputFile, overwrite = true)
            videoOnlyFile.delete()
            return outputFile.exists() && outputFile.length() > 0
        }

        if (!transcodeAudioTrack(item, audioFile, durationS = limitDurationMs?.let { it / 1000 }, startTimeMs = offsetMs)) {
            Log.e(TAG, "Safe path: audio leg failed.")
            videoOnlyFile.delete()
            return false
        }

        val muxOk = remuxVideoAndAudio(videoOnlyFile, audioFile, outputFile)
        videoOnlyFile.delete()
        audioFile.delete()
        return muxOk && outputFile.exists() && outputFile.length() > 0
    }

    private suspend fun hasAudioTrack(item: MediaCollection.MediaNode.Item): Boolean {
        val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"
        val args = listOf(ffprobePath, "-hide_banner", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", inputPath ?: "pipe:0")
        val result = runProcessCapture(args, timeoutMs = 5_000L, logStderrLive = false, stdinSource = if (inputPath == "pipe:0") item.uri else null)
        return result.exitCode == 0 && result.stdout.isNotBlank()
    }

    private enum class TransformerOutcome { PASS, FAIL, HANG }

    private fun mapMimeToDecoder(mime: String?): String? = when (mime) {
        MediaFormat.MIMETYPE_VIDEO_AVC -> "h264"
        MediaFormat.MIMETYPE_VIDEO_HEVC -> "hevc"
        MediaFormat.MIMETYPE_VIDEO_MPEG4 -> "mpeg4"
        MediaFormat.MIMETYPE_VIDEO_VP9 -> "vp9"
        "video/x-msmpeg4v3" -> "msmpeg4v3"
        "video/x-ms-wmv" -> "wmv2"
        else -> mime?.removePrefix("video/vnd.ffmpeg.")
    }

    private suspend fun testTransformerPipeline(
        targetCodec: Constants.Transcoder.VideoCodec,
        item: MediaCollection.MediaNode.Item,
        audioEncoder: String = "aac",
        testDurationS: Long? = 5L
    ): TransformerOutcome {
        val sourceDecoder = mapMimeToDecoder(item.videoCodec)
        if (sourceDecoder == null) return TransformerOutcome.FAIL
        val resParts = item.resolution.split("x")
        val srcW = resParts.getOrNull(0)?.toIntOrNull() ?: 1920
        val srcH = resParts.getOrNull(1)?.toIntOrNull() ?: 1080
        val width = srcW - (srcW % 2); val height = srcH - (srcH % 2); val frameSize = width * height * 3 / 2
        val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"
        val audioOut = File(workDir, "gate3_${targetCodec.name.lowercase()}_audio.m4a")
        val muxedOut = File(workDir, "gate3_${targetCodec.name.lowercase()}_final.mp4")
        listOf(audioOut, muxedOut).forEach { if (it.exists()) it.delete() }
        var bridge: SurfaceAssetLoaderBridge? = null
        val startTime = System.currentTimeMillis()
        return try {
            bridge = SurfaceAssetLoaderBridge(context, targetCodec, width, height)
            val surface = withTimeoutOrNull(10_000L.milliseconds) { bridge.awaitSurface() }
            if (surface == null) { bridge.cancel(); return TransformerOutcome.HANG }
            val decodeStart = System.currentTimeMillis()
            val decodeResult = runSoftwareDecodeToSurface(sourceDecoder = sourceDecoder, inputPath = inputPath, stdinSource = if (inputPath == "pipe:0") item.uri else null, width = width, height = height, frameSize = frameSize, surface = surface, durationS = testDurationS, timeoutMs = if (testDurationS != null) (testDurationS * 5000L) + 15_000L else 300_000L)
            bridge.signalEndOfInput()
            val decodeTime = System.currentTimeMillis() - decodeStart
            if (decodeResult.success == null) { bridge.cancel(); return TransformerOutcome.HANG }
            if (decodeResult.success == false) { bridge.cancel(); return TransformerOutcome.FAIL }
            val encodeOk = withTimeoutOrNull(if (testDurationS != null) 20_000L.milliseconds else 600_000L.milliseconds) { bridge.awaitCompletion() }
            if (encodeOk != true) return if (encodeOk == null) TransformerOutcome.HANG else TransformerOutcome.FAIL
            val hasAudio = (item.channelCount ?: 0) > 0
            if (hasAudio) {
                if (!transcodeAudioTrackExperimental(item, audioOut, testDurationS, audioEncoder)) return TransformerOutcome.FAIL
                if (!remuxVideoAndAudio(bridge.outputFile, audioOut, muxedOut)) return TransformerOutcome.FAIL
            }
            val totalTime = System.currentTimeMillis() - startTime
            val realTimeFactor = if (testDurationS != null && testDurationS > 0) (totalTime / 1000.0 / testDurationS) else 0.0
            Log.d(TAG, "GATE 3 METRICS: '${item.name}' > RT FACTOR: ${String.format("%.2f", realTimeFactor)}x")
            TransformerOutcome.PASS
        } catch (e: Exception) { TransformerOutcome.FAIL } finally { bridge?.cancel() }
    }

    @OptIn(UnstableApi::class)
    private inner class SurfaceAssetLoaderBridge(context: Context, codec: Constants.Transcoder.VideoCodec, width: Int, height: Int) {
        val outputFile = File(workDir, "transformer_video_only_${codec.name.lowercase()}.mp4")
        private val surfaceDeferred = CompletableDeferred<Surface>(); private val completionDeferred = CompletableDeferred<Boolean>(); private var loaderRef: SurfaceAssetLoader? = null; private val transformer: Transformer
        init {
            if (outputFile.exists()) outputFile.delete()
            val mimeType = when (codec) { Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264; Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265 }
            val callback = object : SurfaceAssetLoader.Callback {
                override fun onSurfaceAssetLoaderCreated(surfaceAssetLoader: SurfaceAssetLoader) { loaderRef = surfaceAssetLoader; val format = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_RAW).setWidth(width).setHeight(height).setColorInfo(ColorInfo.SDR_BT709_LIMITED).build(); surfaceAssetLoader.setContentFormat(format) }
                override fun onSurfaceReady(surface: Surface, editedMediaItem: EditedMediaItem) { surfaceDeferred.complete(surface) }
            }
            val mediaItem = MediaItem.Builder().setUri(Uri.parse("${SurfaceAssetLoader.MEDIA_ITEM_URI_SCHEME}://gate3_${codec.name.lowercase()}")).build(); val editedMediaItem = EditedMediaItem.Builder(mediaItem).setRemoveAudio(true).build()
            transformer = Transformer.Builder(context).setVideoMimeType(mimeType).setAssetLoaderFactory(SurfaceAssetLoader.Factory(callback)).addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { completionDeferred.complete(true) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { completionDeferred.complete(false) }
            }).build()
            scope.launch(Dispatchers.Main) { transformer.start(editedMediaItem, outputFile.absolutePath) }
        }
        suspend fun awaitSurface(): Surface = surfaceDeferred.await()
        suspend fun awaitCompletion(): Boolean = completionDeferred.await()
        fun signalEndOfInput() { loaderRef?.signalEndOfInput() }
        fun cancel() { if (!completionDeferred.isCompleted) { scope.launch(Dispatchers.Main) { try { transformer.cancel() } catch (e: Exception) {} } } }
    }

    private data class DecodeResult(val frameCount: Int, val success: Boolean?)

    private suspend fun runSoftwareDecodeToSurface(sourceDecoder: String, inputPath: String?, stdinSource: Uri?, width: Int, height: Int, frameSize: Int, surface: Surface, durationS: Long? = null, timeoutMs: Long): DecodeResult {
        val safeInputPath = inputPath ?: "pipe:0"
        val args = mutableListOf(ffmpegPath, "-y", "-hide_banner", "-v", "warning", "-c:v", sourceDecoder, "-i", safeInputPath, "-an", "-sn")
        if (durationS != null) args.addAll(listOf("-t", durationS.toString()))
        args.addAll(listOf("-vf", "scale=$width:$height,format=yuv420p", "-f", "rawvideo", "-pix_fmt", "yuv420p", "pipe:1"))
        val process = try { ProcessBuilder(args).start() } catch (e: Exception) { return DecodeResult(0, false) }
        val pumpExecutor = Executors.newFixedThreadPool(4); val pumpDispatcher = pumpExecutor.asCoroutineDispatcher(); val pumpScope = CoroutineScope(pumpDispatcher + SupervisorJob()); val stderrLog = StringBuilder()
        pumpScope.launch { try { process.errorStream.bufferedReader().use { r -> r.forEachLine { synchronized(stderrLog) { stderrLog.appendLine(it) } } } } catch (e: Exception) {} }
        stdinSource?.let { uri -> pumpScope.launch { try { context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> FileInputStream(pfd.fileDescriptor).use { input -> process.outputStream.use { output -> input.copyTo(output) } } } } catch (e: Exception) {} } }
        var imageWriter: ImageWriter? = null; var frameCount = 0
        val pumpJob = pumpScope.launch {
            try {
                imageWriter = ImageWriter.newInstance(surface, 5, ImageFormat.YUV_420_888); val input = DataInputStream(process.inputStream); val frameBuf = ByteArray(frameSize); val frameDurationNs = 1_000_000_000L / 30 
                while (isActive) { input.readFully(frameBuf); val image = imageWriter?.dequeueInputImage() ?: break; copyPlanarFrameIntoImage(frameBuf, width, height, image); image.timestamp = frameCount * frameDurationNs; imageWriter?.queueInputImage(image); frameCount++ }
            } catch (e: EOFException) {} catch (e: Exception) { synchronized(stderrLog) { stderrLog.appendLine("pump error: ${e.message}") } }
        }
        val finishedInTime = withTimeoutOrNull(timeoutMs.milliseconds) { pumpJob.join() } != null
        if (!finishedInTime) {
            CoroutineScope(Dispatchers.Default + SupervisorJob()).launch { try { process.destroyForcibly() } catch (e: Exception) {}; try { imageWriter?.close() } catch (e: Exception) {}; pumpScope.cancel(); pumpExecutor.shutdownNow() }
            return DecodeResult(frameCount, null)
        }
        try { process.waitFor() } catch (e: Exception) {}; try { imageWriter?.close() } catch (e: Exception) {}; pumpScope.cancel(); pumpExecutor.shutdownNow()
        return if (frameCount == 0) DecodeResult(0, false) else DecodeResult(frameCount, true)
    }

    private fun copyPlanarFrameIntoImage(frame: ByteArray, width: Int, height: Int, image: Image) {
        val chromaW = width / 2; val chromaH = height / 2; val ySize = width * height; val chromaSize = chromaW * chromaH
        val srcOffsets = intArrayOf(0, ySize, ySize + chromaSize); val srcRowWidths = intArrayOf(width, chromaW, chromaW); val planeHeights = intArrayOf(height, chromaH, chromaH)
        val planes = image.planes
        for (p in 0 until minOf(planes.size, 3)) {
            val plane = planes[p]; val buffer = plane.buffer; val rowStride = plane.rowStride; val pixelStride = plane.pixelStride; val srcRowWidth = srcRowWidths[p]; var srcPos = srcOffsets[p]
            for (row in 0 until planeHeights[p]) {
                if (pixelStride == 1) { buffer.position(row * rowStride); buffer.put(frame, srcPos, srcRowWidth) }
                else { var dst = row * rowStride; for (col in 0 until srcRowWidth) { buffer.put(dst, frame[srcPos + col]); dst += pixelStride } }
                srcPos += srcRowWidth
            }
        }
    }

    private suspend fun transcodeAudioTrackExperimental(item: MediaCollection.MediaNode.Item, outFile: File, durationS: Long? = null, encoder: String = "aac"): Boolean {
        val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"; val safeInputPath = inputPath ?: "pipe:0"
        val args = mutableListOf(ffmpegPath, "-y", "-hide_banner", "-hwaccel", "mediacodec", "-ndk_codec", "1", "-i", safeInputPath, "-vn", "-sn", "-map", "0:a:0?", "-c:a", encoder, "-b:a", "128k")
        if (durationS != null) args.addAll(listOf("-t", durationS.toString()))
        val channels = item.channelCount ?: 0
        if (channels >= 6) args.addAll(listOf("-ac", "6", "-channel_layout", "5.1")) else if (channels > 0) args.addAll(listOf("-ac", channels.toString()))
        args.addAll(listOf("-f", "mp4", outFile.absolutePath))
        val result = runProcessCapture(args, timeoutMs = 300_000L, logStderrLive = true, stdinSource = if (safeInputPath == "pipe:0") item.uri else null)
        return result.exitCode == 0 && outFile.exists() && outFile.length() > 0
    }

    private suspend fun transcodeAudioTrack(item: MediaCollection.MediaNode.Item, outFile: File, durationS: Long? = null, startTimeMs: Long = 0, encoder: String = "aac"): Boolean {
        val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"; val safeInputPath = inputPath ?: "pipe:0"
        val args = mutableListOf(ffmpegPath, "-y", "-hide_banner", "-hwaccel", "mediacodec", "-ndk_codec", "1")
        if (startTimeMs > 0) args.addAll(listOf("-ss", (startTimeMs / 1000.0).toString()))
        args.addAll(listOf("-i", safeInputPath, "-vn", "-sn", "-map", "0:a:0?", "-c:a", encoder, "-b:a", "128k"))
        if (durationS != null) args.addAll(listOf("-t", durationS.toString()))
        if ((item.channelCount ?: 0) >= 6) args.addAll(listOf("-ac", "6", "-channel_layout", "5.1")) else args.addAll(listOf("-ac", "2"))
        args.addAll(listOf("-f", "mp4", outFile.absolutePath))
        val result = runProcessCapture(args, timeoutMs = 300_000L, logStderrLive = true, stdinSource = if (safeInputPath == "pipe:0") item.uri else null)
        return result.exitCode == 0 && outFile.exists() && outFile.length() > 0
    }

    private suspend fun remuxVideoAndAudio(videoFile: File, audioFile: File, outFile: File): Boolean {
        val args = listOf(ffmpegPath, "-y", "-hide_banner", "-i", videoFile.absolutePath, "-i", audioFile.absolutePath, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-f", "mp4", outFile.absolutePath)
        val result = runProcessCapture(args, timeoutMs = 60_000L, logStderrLive = true)
        return result.exitCode == 0 && outFile.exists() && outFile.length() > 0
    }

    private fun currentAppVersionCode(): Long = try { val info = context.packageManager.getPackageInfo(context.packageName, 0); info.longVersionCode } catch (e: PackageManager.NameNotFoundException) { -1L }

    private data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

    private suspend fun runProcessCapture(args: List<String>, timeoutMs: Long = 15_000L, logStderrLive: Boolean = false, stdinSource: Uri? = null, verbose: Boolean = false): ProcessOutput {
        if (verbose) Log.d(TAG, "Starting ProcessBuilder: ${args.joinToString(" ")}")
        val process = try { ProcessBuilder(args).start() } catch (e: Exception) { return ProcessOutput(-1, "", "Failed to start process: ${e.message}") }
        val lock = Any(); val stderrBuilder = StringBuilder(); val stdoutBuilder = StringBuilder(); val ioExecutor = Executors.newFixedThreadPool(3); val detachedIoDispatcher = ioExecutor.asCoroutineDispatcher(); val detachedIoScope = CoroutineScope(detachedIoDispatcher + SupervisorJob())
        detachedIoScope.launch { try { process.errorStream.bufferedReader().use { r -> r.forEachLine { synchronized(lock) { stderrBuilder.appendLine(it) }; if (logStderrLive && verbose) Log.d(TAG, "  stderr> $it") } } } catch (e: Exception) {} }
        detachedIoScope.launch { try { process.inputStream.bufferedReader().use { r -> r.forEachLine { synchronized(lock) { stdoutBuilder.appendLine(it) } } } } catch (e: Exception) {} }
        stdinSource?.let { uri -> detachedIoScope.launch { try { context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd -> FileInputStream(pfd.fileDescriptor).use { input -> process.outputStream.use { output -> input.copyTo(output) } } } } catch (e: Exception) {} } }
        var exitCode: Int? = null
        withContext(Dispatchers.Default) { val startTime = System.currentTimeMillis(); while (System.currentTimeMillis() - startTime < timeoutMs) { try { exitCode = process.exitValue(); return@withContext } catch (e: IllegalThreadStateException) { delay(100.milliseconds) } } }
        if (exitCode == null) { CoroutineScope(Dispatchers.Default + SupervisorJob()).launch { try { process.destroyForcibly(); process.inputStream.close(); process.errorStream.close(); process.outputStream.close() } catch (e: Exception) {} } }
        else { try { process.inputStream.close(); process.errorStream.close(); process.outputStream.close() } catch (e: Exception) {} }
        detachedIoScope.cancel(); ioExecutor.shutdownNow()
        val finalStderr = synchronized(lock) { stderrBuilder.toString() }; val finalStdout = synchronized(lock) { stdoutBuilder.toString() }
        return if (exitCode == null) ProcessOutput(-1, finalStdout, finalStderr, true) else ProcessOutput(exitCode!!, finalStdout, finalStderr)
    }

    private suspend fun runFfmpegFallbackSegment(index: Int, startMs: Long, endMs: Long, sourceUri: Uri, targetCodec: Constants.Transcoder.VideoCodec, targetHeight: Int, targetBitrateBps: Int, outputFile: File, verbose: Boolean = true): Boolean {
        val durationS = (endMs - startMs) / 1000.0; val startS = startMs / 1000.0; val tuningRes = Constants.Transcoder.Resolution.entries.find { it.maxHeight == targetHeight } ?: Constants.Transcoder.Resolution.HD_720
        val useDirectPath = sourceUri.scheme == "file" && sourceUri.path != null
        val args = listOf(ffmpegPath, "-y", "-hide_banner", "-ss", startS.toString(), "-t", durationS.toString(), "-i", if (useDirectPath) sourceUri.path!! else "pipe:0", "-an", "-sn", "-vf", "scale=-2:$targetHeight", "-c:v", targetCodec.ffmpegEncoder, "-b:v", "${targetBitrateBps / 1000}k") + encoderTuningArgs(targetCodec, tuningRes) + listOf("-f", "mp4", outputFile.absolutePath)
        val result = runProcessCapture(args, timeoutMs = 5_000L, logStderrLive = true, stdinSource = if (useDirectPath) null else sourceUri, verbose = verbose)
        return result.exitCode == 0 && outputFile.exists() && outputFile.length() > 0
    }

    private fun updateSeekBarPosition(position: String, duration: String) {}
    fun release() { resetTranscoderActivity(); scope.cancel() }
    private fun resetTranscoderActivity() { stopPlaying() }
    private fun stopPlaying() { stopPlayingLocal() }
    private fun stopPlayingLocal() { repo.setSeekBarDuration("00:00:00"); repo.setSeekBarPosition("00:00:00") }
}
