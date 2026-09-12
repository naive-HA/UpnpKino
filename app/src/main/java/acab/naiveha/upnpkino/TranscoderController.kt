package acab.naiveha.upnpkino

import android.content.ContentValues
import android.content.Context
import android.app.ActivityManager
import android.os.PowerManager
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.DocumentsContract
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
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.EncoderUtil
import com.google.common.collect.ImmutableList
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
                        targetCodec = Constants.Transcoder.VideoCodec.H264, // Synthetic is HEVC, test re-encoding to H264
                        resolution = res720
                    )
                    useHardwareFfmpegPipeline = (outcome == HwProbeOutcome.PASS)
                }
                Log.d(TAG, "Hardware capability check complete -- useHardwareFfmpegPipeline=$useHardwareFfmpegPipeline")

                // Unified test loop for library files
                val libraryItems = UpnpRepository.kinoService.sharedMediaCollection.value.values
                    .filterIsInstance<MediaCollection.MediaNode.Item>()

                Log.d(TAG, "UNIFIED TEST: Starting library-wide transcode test")
                for (item in libraryItems) {
                    break
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
                    val testDurationMs: Long? = null
//                    val testDurationMs: Long? = 30_000L
                    
                val success = transcodeMediaFile(
                        item = item,
                        startTimeMs = 0,
                        durationMs = testDurationMs,
                        targetVideoCodec = targetVideoCodec,
                        targetResolution = null, // Keep original
                        subtitleTrack = null, // Future development
                        audioTrackIndex = 0, // Default track
                        targetAudioCodec = Constants.Transcoder.AudioCodec.AAC,
                        outputFile = testOutputFile,
                        saveToSourceDir = true,
                        timeoutS = if (testDurationMs == null) 3600 else 60
                    )
                    val totalTime = System.currentTimeMillis() - startTime

                    if (success) {
                        val rtFactor = if (testDurationMs != null && testDurationMs > 0) {
                            totalTime.toDouble() / testDurationMs
                        } else if (item.durationMs > 0) {
                            totalTime.toDouble() / item.durationMs
                        } else {
                            null
                        }
                        Log.d(TAG, "UNIFIED TEST METRICS: '${item.name}'")
                        Log.d(TAG, "  > Result:        SUCCESS")
                        Log.d(TAG, "  > Total Time:    ${totalTime}ms")
                        if (rtFactor != null) {
                            Log.d(TAG, "  > RT FACTOR:     ${String.format("%.2f", rtFactor)}x")
                        }
                    } else {
                        Log.e(TAG, "UNIFIED TEST METRICS: '${item.name}'")
                        Log.e(TAG, "  > Result:        FAIL")
                        Log.e(TAG, "  > Total Time:    ${totalTime}ms")
                    }
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

    /** Canonical channel layouts this app explicitly supports end-to-end.
     *  ffmpeg's native aac encoder rejects channel counts/layouts it doesn't
     *  recognize by name (this is what caused an earlier hardware-probe
     *  crash: a source reporting a bare "6 channels" with no named layout).
     *  Anything outside these three downmixes to stereo -- always a safe,
     *  unambiguous target -- rather than being passed through as a raw
     *  channel count ffmpeg might reject.
     */
    private enum class AudioChannelLayout(val channelCount: Int, val ffmpegLayoutName: String?) {
        MONO(1, null),
        STEREO(2, null),
        SURROUND_5_1(6, "5.1")
    }

    private fun resolveChannelLayout(sourceChannelCount: Int?): AudioChannelLayout = when (sourceChannelCount) {
        1 -> AudioChannelLayout.MONO
        6 -> AudioChannelLayout.SURROUND_5_1
        else -> AudioChannelLayout.STEREO
    }

    private fun resolveAudioBitrate(layout: AudioChannelLayout): Int = when (layout) {
        AudioChannelLayout.MONO -> 64_000
        AudioChannelLayout.STEREO -> 128_000
        AudioChannelLayout.SURROUND_5_1 -> 384_000
    }

    private fun channelArgs(layout: AudioChannelLayout): List<String> {
        val base = listOf("-ac", layout.channelCount.toString())
        return if (layout.ffmpegLayoutName != null) base + listOf("-channel_layout", layout.ffmpegLayoutName) else base
    }

    /**
     * Queries the real source channel count via ffprobe. Used only by
     * runHwProbe() for the synthetic test fixtures exercised at init --
     * those have no scanned library metadata to fall back on. Real per-item
     * transcodes do NOT use this; see resolveItemChannelLayout() below.
     * Works against either a direct filesystem path or a piped content://
     * source, mirroring the -i/stdin split used everywhere else in this
     * file. Returns null if ffprobe fails, times out, or the stream has no
     * parseable/no audio channel entry.
     */
    private suspend fun probeAudioChannelCount(inputPath: String, stdinSource: Uri? = null): Int? {
        val args = listOf(
            ffprobePath, "-hide_banner", "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=channels",
            "-of", "csv=p=0",
            inputPath
        )
        val result = runProcessCapture(args, timeoutMs = 5_000L, verbose = false, stdinSource = stdinSource)
        if (result.exitCode != 0) return null
        return result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
    }

    /**
     * The channel layout to encode a library item with, trusting the
     * one-time library scan (item.channelCount) rather than re-probing.
     * Real transcodes call this on every invocation -- including per-segment
     * calls against a portion of the same file during playback -- so
     * re-running ffprobe here would mean paying that cost repeatedly for a
     * file whose channel count was already established at scan time and
     * doesn't change mid-file. Logs whenever the result falls outside the
     * three explicitly-supported layouts, so a downmix is visible rather
     * than silent.
     */
    private fun resolveItemChannelLayout(item: MediaCollection.MediaNode.Item): AudioChannelLayout {
        val channelCount = item.channelCount
        if (channelCount != null && channelCount !in setOf(1, 2, 6)) {
            Log.w(TAG, "Downmixing '${item.name}' from ${channelCount}ch to stereo (unsupported layout)")
        }
        return resolveChannelLayout(channelCount)
    }

    private enum class HwProbeOutcome { PASS, FAIL, HANG }
    private enum class LimitMode { FRAME_COUNT, TIME }

    private fun limitArgs(mode: LimitMode): List<String> = when (mode) {
        LimitMode.FRAME_COUNT -> listOf("-frames:v", HW_PROBE_FRAME_LIMIT.toString(), "-t", "3")
        LimitMode.TIME -> listOf("-t", HW_PROBE_TIME_LIMIT_S.toString())
    }

    private fun testHeightFor(resolution: Constants.Transcoder.Resolution): Int =
        if (resolution == Constants.Transcoder.Resolution.HD_1080) 1088 else resolution.maxHeight

    /**
     * @param includeAudio null = auto-detect (probe the source; audio is
     *   included only if a channel count is actually found). Pass true/false
     *   to force a specific behavior regardless of what's really in the
     *   source -- e.g. a synthetic "_noaudio" fixture that's deliberately
     *   meant to exercise the no-audio hardware path on its own terms.
     * @param knownAudioChannelCount skip a redundant ffprobe pass when the
     *   caller already has a trustworthy channel count (e.g. from library
     *   metadata). Leave null to have this function probe the source itself.
     */
    private suspend fun runHwProbe(
        label: String,
        inputPath: String,
        stdinSource: Uri? = null,
        limitMode: LimitMode,
        includeAudio: Boolean? = null,
        knownAudioChannelCount: Int? = null,
        targetCodec: Constants.Transcoder.VideoCodec = Constants.Transcoder.VideoCodec.HEVC,
        resolution: Constants.Transcoder.Resolution,
        timeoutMs: Long = HW_PROBE_TIMEOUT_MS
    ): HwProbeOutcome {
        if (stdinSource == null && !File(inputPath).exists()) return HwProbeOutcome.FAIL

        // Costs one extra full pass over the source ahead of the real probe
        // encode -- cheap for the small synthetic fixtures this is normally
        // called with. Worth remembering if this is ever pointed directly at
        // large piped library items.
        val probedChannelCount = knownAudioChannelCount ?: probeAudioChannelCount(inputPath, stdinSource)
        val shouldIncludeAudio = includeAudio ?: (probedChannelCount != null && probedChannelCount > 0)

        val streamArgs = if (shouldIncludeAudio) {
            val layout = resolveChannelLayout(probedChannelCount)
            if (probedChannelCount != null && probedChannelCount !in setOf(1, 2, 6)) {
                Log.w(TAG, "HW PROBE [$label]: downmixing ${probedChannelCount}ch source to stereo for the probe")
            }
            listOf("-map", "0:v:0", "-map", "0:a:0?", "-c:a", Constants.Transcoder.AudioCodec.AAC.ffmpegEncoder) + channelArgs(layout)
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
                results[label] = runHwProbe(label, case.file.absolutePath, limitMode = mode, includeAudio = case.hasAudio, targetCodec = Constants.Transcoder.VideoCodec.HEVC, resolution = case.resolution)
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
     *
     * @param subtitleTrack index of subtitle track to include (future work).
     * @param audioTrackIndex index of audio track to transcode (default: 0).
     * @param saveToSourceDir if true, saves the output to the input file's directory.
     *   If false, treats the transcode as a temporary operation and clears the 
     *   resulting file upon finishing.
     */
    suspend fun transcodeMediaFile(
        item: MediaCollection.MediaNode.Item,
        startTimeMs: Long,
        durationMs: Long?,
        targetVideoCodec: Constants.Transcoder.VideoCodec,
        targetResolution: Constants.Transcoder.Resolution? = null,
        subtitleTrack: Int? = null,
        audioTrackIndex: Int = 0,
        targetAudioCodec: Constants.Transcoder.AudioCodec,
        outputFile: File,
        saveToSourceDir: Boolean = false,
        timeoutS: Long? = null
    ): Boolean {
        val resolution = targetResolution ?: run {
            val parts = item.resolution.split("x")
            val height = parts.getOrNull(1)?.toIntOrNull() ?: 1080
            Constants.Transcoder.Resolution.entries.sortedBy { it.maxHeight }
                .find { it.maxHeight >= height } ?: Constants.Transcoder.Resolution.HD_1080
        }
        val videoBitrateKbps = resolution.targetBitrateKbps
        val audioLayout = resolveItemChannelLayout(item)
        val audioBitrateBps = resolveAudioBitrate(audioLayout)
        
        val videoMime = item.videoCodec?.lowercase() ?: ""
        val isLegacyVideo = videoMime.contains("mpeg4") || videoMime.contains("wmv") || item.name.lowercase().endsWith(".avi")
        val is4k = resolution == Constants.Transcoder.Resolution.UHD_4K
        val hasDtsAudio = item.audioCodec?.contains("dts", ignoreCase = true) == true || 
                          item.audioTracks.any { it.codec.contains("dts", ignoreCase = true) }

        val strategy = when {
//            is4k -> "full_media3"
//            isLegacyVideo -> "hybrid"
//            hasDtsAudio -> "hybrid"
            isLegacyVideo || hasDtsAudio -> "hybrid"
            useHardwareFfmpegPipeline == true -> "hw_ffmpeg"
            // NOTE: originally assumed full_media3's Muxer error (watchdog timeout) came from
            // interleaving video+audio, and that the Safe Path (hybrid) avoided it by exporting
            // video-only first. 2026-09-10 trace shows the same watchdog abort ("no output sample
            // written in 10000ms") on the video-only leg alone -- it's an encoder/frame-processor
            // stall, not an interleaving issue, so hybrid does NOT reliably avoid it.
            else -> "hybrid"
        }

        Log.d(TAG, "Transcode Start: '${item.name}'")
        Log.d(TAG, "  > Strategy:   $strategy (4K=$is4k, timeout: ${timeoutS}s)")
        Log.d(TAG, "  > Source V:   ${item.videoCodec} (${item.resolution})")
        Log.d(TAG, "  > Source A:   ${item.audioCodec} (${item.channelCount}ch)")
        Log.d(TAG, "  > Target V:   ${targetVideoCodec.name} (${resolution.maxWidth}x${resolution.maxHeight} @ ${videoBitrateKbps}kbps)")
        Log.d(TAG, "  > Target A:   ${targetAudioCodec.name} (${audioLayout.channelCount}ch @ ${audioBitrateBps / 1000}kbps)")
        
        Log.d(TAG, "Save routing: saveToSourceDir=$saveToSourceDir, uri=${item.uri}, scheme=${item.uri.scheme}, path=${item.uri.path}")
        Log.d(TAG, "Item start: '${item.name}' durationMs=${item.durationMs}, ${diagnosticSnapshot()}")

        val timeoutMs = timeoutS?.let { it * 1000 } ?: 300_000L // Default to 5m if null

        // Always transcode into the local temp file, regardless of saveToSourceDir.
        // Neither Media3's Transformer nor the native ffmpeg process (ProcessBuilder)
        // can write directly to a SAF/content:// tree, and even for a genuine file://
        // item there's no guarantee the app can write into an arbitrary external
        // directory under scoped storage -- so "same directory as input" is handled
        // as a separate, explicitly-checked publish step below, once we have a
        // completed local file to copy from.
        val success = when (strategy) {
            "hw_ffmpeg" -> transcodeViaHardwareFfmpeg(item, targetVideoCodec, resolution, videoBitrateKbps, targetAudioCodec, audioBitrateBps, audioTrackIndex, outputFile, startTimeMs, durationMs, timeoutMs = timeoutMs)
            "full_media3" -> transcodeViaFullMedia3Path(item, targetVideoCodec, resolution, outputFile, durationMs, startTimeMs, timeoutMs = timeoutMs)
            else -> transcodeViaSafePath(item, targetVideoCodec, resolution, targetAudioCodec, audioBitrateBps, audioTrackIndex, outputFile, durationMs, startTimeMs, timeoutMs = timeoutMs)
        }

        var finalSuccess = success
        if (success && saveToSourceDir) {
            val published = publishToSourceDirectory(item, outputFile)
            if (published != null) {
                Log.d(TAG, "Transcode finished. File preserved at: $published")
            } else {
                Log.e(TAG, "Transcode succeeded but publishing to source directory failed -- treating as a failure rather than silently leaving the temp copy behind")
                finalSuccess = false
            }
        }

        if (outputFile.exists()) {
            val deleted = outputFile.delete()
            Log.d(TAG, "Cleanup: Deleted temporary transcode file ($deleted)")
        }

        return finalSuccess
    }

    /**
     * Copies a completed transcode from [tempFile] into the same directory as
     * [item]'s source file, replacing any previous copy of the same name.
     * Handles both raw filesystem items (item.uri has a "file" scheme) and
     * SAF/DocumentFile-backed items (typically "content") by writing through
     * DocumentFile/ContentResolver instead of touching a raw path.
     *
     * Returns the published location (absolute path or content Uri, as a
     * String, for logging) on success, or null if the source directory
     * couldn't be resolved or written to. Callers should treat null as
     * saveToSourceDir having failed -- not as a reason to silently keep
     * (or lose track of) the temp copy.
     */
    @OptIn(UnstableApi::class)
    private fun publishToSourceDirectory(item: MediaCollection.MediaNode.Item, tempFile: File): String? {
        val baseName = item.name.substringBeforeLast('.', item.name)
        val targetName = "transcoded_$baseName.mp4"

        if (item.uri.scheme == "file" && item.uri.path != null) {
            val parentDir = File(item.uri.path!!).parentFile
            if (parentDir == null || !parentDir.exists() || !parentDir.canWrite()) {
                Log.e(TAG, "Publish: source directory not writable (parentExists=${parentDir?.exists()}, parentWritable=${parentDir?.canWrite()}, path=${parentDir?.absolutePath})")
                return null
            }
            val target = File(parentDir, targetName)
            return try {
                tempFile.copyTo(target, overwrite = true)
                Log.d(TAG, "Publish: copied to ${target.absolutePath} (size=${target.length()})")
                target.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Publish: file copy failed: ${e.message}", e)
                null
            }
        }

        // SAF / content:// item. DocumentFile.fromSingleUri()/.fromTreeUri() both
        // leave getParentFile() null when constructed from a bare document Uri --
        // parent traversal only works if you already walked down from the tree
        // root via listFiles(). Derive the parent document Uri directly instead,
        // via the standard ExternalStorageProvider document-ID convention
        // ("<root>:relative/path" -- strip the last path segment). This is only
        // reliable for that one authority (the one SAF folder pickers use for
        // local/SD storage); anything else falls through and fails cleanly.
        val parentUri = resolveSafParentUri(item.uri)
        if (parentUri == null) {
            Log.e(TAG, "Publish: could not resolve a parent directory for uri=${item.uri} (authority=${item.uri.authority}) -- leaving output in temp storage")
            return null
        }
        val parentDoc = DocumentFile.fromTreeUri(context, parentUri)
        if (parentDoc == null || !parentDoc.canWrite()) {
            Log.e(TAG, "Publish: parent DocumentFile at $parentUri is null or not writable")
            return null
        }
        return try {
            parentDoc.findFile(targetName)?.delete()
            val newDoc = parentDoc.createFile("video/mp4", targetName)
            if (newDoc == null) {
                Log.e(TAG, "Publish: DocumentFile.createFile failed for $targetName in $parentUri")
                return null
            }
            val opened = context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false
            if (!opened) {
                Log.e(TAG, "Publish: could not open output stream for ${newDoc.uri}")
                return null
            }
            Log.d(TAG, "Publish: copied to ${newDoc.uri} (size=${newDoc.length()})")
            newDoc.uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Publish: SAF copy failed: ${e.message}", e)
            null
        }
    }

    /** See the comment in [publishToSourceDirectory]. Returns null for any authority
     *  other than the standard local-storage SAF provider, or if the Uri is already
     *  at its tree root (no parent segment left to strip). */
    private fun resolveSafParentUri(uri: Uri): Uri? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        return try {
            val docId = DocumentsContract.getDocumentId(uri)
            val lastSlash = docId.lastIndexOf('/')
            if (lastSlash <= 0) return null
            val parentDocId = docId.substring(0, lastSlash)
            DocumentsContract.buildDocumentUriUsingTree(uri, parentDocId)
        } catch (e: Exception) {
            Log.e(TAG, "resolveSafParentUri failed for $uri: ${e.message}", e)
            null
        }
    }

    private suspend fun transcodeViaHardwareFfmpeg(
        item: MediaCollection.MediaNode.Item,
        targetVideo: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        videoBitrateKbps: Int,
        targetAudio: Constants.Transcoder.AudioCodec,
        audioBitrateBps: Int,
        audioTrackIndex: Int,
        outputFile: File,
        startTimeMs: Long,
        durationMs: Long?,
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
            args.addAll(listOf("-map", "0:a:$audioTrackIndex?", "-c:a", targetAudio.ffmpegEncoder))
            args.addAll(listOf("-b:a", "${audioBitrateBps / 1000}k"))
            args.addAll(channelArgs(resolveItemChannelLayout(item)))
        }
        if (durationMs != null) args.addAll(listOf("-t", (durationMs / 1000.0).toString()))
        args.addAll(listOf("-f", "mp4", outputFile.absolutePath))

        Log.d(TAG, "HW ffmpeg: Starting transcode to ${outputFile.absolutePath} (${diagnosticSnapshot()})")
        val result = runProcessCapture(args, timeoutMs = timeoutMs, logStderrLive = true, stdinSource = if (safeInputPath == "pipe:0") item.uri else null)
        val success = result.exitCode == 0 && outputFile.exists() && outputFile.length() > 0
        Log.d(TAG, "HW ffmpeg: Finished success=$success, exitCode=${result.exitCode}, size=${outputFile.length()}, ${diagnosticSnapshot()}")
        return success
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
                        .setEncoderFactory(DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                                .setBitrate(resolution.targetBitrateKbps * 1000)
                                .build())
                            .build())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { 
                                Log.d(TAG, "Full path: Export completed")
                                if (continuation.isActive) continuation.resume(true) 
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (exportException.message?.contains("Release timed out") == true || 
                                    exportException.cause?.message?.contains("Release timed out") == true) {
                                    // "Release timed out" can fire spuriously during cleanup after a
                                    // genuinely successful export -- don't trust the message alone,
                                    // verify the output actually landed before calling it OK.
                                    val wroteOutput = outputFile.exists() && outputFile.length() > 0
                                    Log.w(TAG, "Full path: Release timeout during cleanup, output present=$wroteOutput (size=${outputFile.length()})")
                                    if (continuation.isActive) continuation.resume(wroteOutput)
                                    return
                                }
                                Log.e(TAG, "Full path: Transformer failed: ${exportException.message}", exportException)
                                Log.e(TAG, "  > Error Details: ${exportException.cause?.message}")
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
                        Log.d(TAG, "Full path: Starting export to ${outputFile.absolutePath}")
                        transformer.start(editedMediaItem, outputFile.absolutePath)

                        // Progress logging loop
                        val progressHolder = ProgressHolder()
                        scope.launch(Dispatchers.Main) {
                            var lastBytes = -1L
                            var lastGrowthAt = System.currentTimeMillis()
                            while (continuation.isActive) {
                                val state = transformer.getProgress(progressHolder)
                                val bytes = outputFile.length()
                                val now = System.currentTimeMillis()
                                if (bytes != lastBytes) { lastGrowthAt = now; lastBytes = bytes }
                                val stalledForMs = now - lastGrowthAt
                                if (state != Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                                    Log.d(TAG, "Full path: Progress: ${progressHolder.progress}% (state=$state, bytesWritten=$bytes, ${diagnosticSnapshot()})")
                                }
                                if (stalledForMs >= 6000L) {
                                    Log.w(TAG, "Full path: STALL SUSPECTED -- no byte growth for ${stalledForMs}ms (state=$state, bytesWritten=$bytes, ${diagnosticSnapshot()})")
                                }
                                delay(2000.milliseconds)
                            }
                        }
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
        targetAudioCodec: Constants.Transcoder.AudioCodec,
        audioBitrateBps: Int,
        audioTrackIndex: Int,
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
                        .setEncoderFactory(DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                                .setBitrate(resolution.targetBitrateKbps * 1000)
                                .build())
                            .build())
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) { 
                                Log.d(TAG, "Safe path: Video-only export completed")
                                if (continuation.isActive) continuation.resume(true) 
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (exportException.message?.contains("Release timed out") == true || 
                                    exportException.cause?.message?.contains("Release timed out") == true) {
                                    // "Release timed out" can fire spuriously during cleanup after a
                                    // genuinely successful export -- don't trust the message alone,
                                    // verify the output actually landed before calling it OK.
                                    val wroteOutput = videoOnlyFile.exists() && videoOnlyFile.length() > 0
                                    Log.w(TAG, "Safe path: Release timeout during cleanup, output present=$wroteOutput (size=${videoOnlyFile.length()})")
                                    if (continuation.isActive) continuation.resume(wroteOutput)
                                    return
                                }
                                Log.e(TAG, "Safe path: Transformer export failed: ${exportException.message}", exportException)
                                Log.e(TAG, "  > Error Details: ${exportException.cause?.message}")
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
                        Log.d(TAG, "Safe path: Starting video-only export for '${item.name}' to ${videoOnlyFile.absolutePath} (${diagnosticSnapshot()})")
                        transformer.start(editedMediaItem, videoOnlyFile.absolutePath)
                        
                        // Progress logging loop
                        val progressHolder = ProgressHolder()
                        scope.launch(Dispatchers.Main) {
                            var lastBytes = -1L
                            var lastGrowthAt = System.currentTimeMillis()
                            while (continuation.isActive) {
                                val state = transformer.getProgress(progressHolder)
                                val bytes = videoOnlyFile.length()
                                val now = System.currentTimeMillis()
                                if (bytes != lastBytes) { lastGrowthAt = now; lastBytes = bytes }
                                val stalledForMs = now - lastGrowthAt
                                if (state != Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                                    Log.d(TAG, "Safe path: Video progress: ${progressHolder.progress}% (state=$state, bytesWritten=$bytes, ${diagnosticSnapshot()})")
                                }
                                if (stalledForMs >= 6000L) {
                                    Log.w(TAG, "Safe path: STALL SUSPECTED for '${item.name}' -- no byte growth for ${stalledForMs}ms (state=$state, bytesWritten=$bytes, ${diagnosticSnapshot()})")
                                }
                                delay(2000.milliseconds)
                            }
                        }
                        continuation.invokeOnCancellation { CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch { try { transformer.cancel() } catch (e: Exception) {} } }
                    } catch (e: Exception) {
                        Log.e(TAG, "Safe path: Transformer setup failed: ${e.message}", e)
                        if (continuation.isActive) continuation.resume(false)
                    }
                }
            } ?: false
        }
        if (!videoOk || !videoOnlyFile.exists() || videoOnlyFile.length() == 0L) {
            Log.e(TAG, "Safe path: video leg failed for '${item.name}' (${diagnosticSnapshot()})")
            videoOnlyFile.delete()
            return false
        }

        if (!hasAudioTrack(item)) {
            Log.d(TAG, "Safe path: No audio track found, copying video-only file to ${outputFile.absolutePath}")
            try {
                videoOnlyFile.copyTo(outputFile, overwrite = true)
                videoOnlyFile.delete()
                val success = outputFile.exists() && outputFile.length() > 0
                Log.d(TAG, "Safe path: Video-only copy success=$success (size=${outputFile.length()})")
                return success
            } catch (e: Exception) {
                Log.e(TAG, "Safe path: Failed to copy video-only file: ${e.message}", e)
                return false
            }
        }

        Log.d(TAG, "Safe path: Starting audio leg for '${item.name}' -> ${audioFile.absolutePath} (${diagnosticSnapshot()})")
        val audioLegStartedAt = System.currentTimeMillis()
        val audioOk = transcodeAudioTrack(item, audioFile, durationS = limitDurationMs?.let { it / 1000 }, startTimeMs = offsetMs, encoder = targetAudioCodec.ffmpegEncoder, audioBitrateBps = audioBitrateBps, audioTrackIndex = audioTrackIndex)
        Log.d(TAG, "Safe path: Audio leg for '${item.name}' finished after ${System.currentTimeMillis() - audioLegStartedAt}ms, success=$audioOk")
        if (!audioOk) {
            Log.e(TAG, "Safe path: audio leg failed for '${item.name}' (${diagnosticSnapshot()})")
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
    ): TransformerOutcome = withContext(Dispatchers.Main) {
        val sourceDecoder = mapMimeToDecoder(item.videoCodec)
        if (sourceDecoder == null) return@withContext TransformerOutcome.FAIL
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
        try {
            bridge = SurfaceAssetLoaderBridge(context, targetCodec, width, height)
            val surface = withTimeoutOrNull(10_000L.milliseconds) { bridge.awaitSurface() }
            if (surface == null) { bridge.cancel(); return@withContext TransformerOutcome.HANG }
            val decodeStart = System.currentTimeMillis()
            val decodeResult = runSoftwareDecodeToSurface(sourceDecoder = sourceDecoder, inputPath = inputPath, stdinSource = if (inputPath == "pipe:0") item.uri else null, width = width, height = height, frameSize = frameSize, surface = surface, durationS = testDurationS, timeoutMs = if (testDurationS != null) (testDurationS * 5000L) + 15_000L else 300_000L)
            bridge.signalEndOfInput()
            val decodeTime = System.currentTimeMillis() - decodeStart
            if (decodeResult.success == null) { bridge.cancel(); return@withContext TransformerOutcome.HANG }
            if (decodeResult.success == false) { bridge.cancel(); return@withContext TransformerOutcome.FAIL }
            val encodeOk = withTimeoutOrNull(if (testDurationS != null) 20_000L.milliseconds else 600_000L.milliseconds) { bridge.awaitCompletion() }
            if (encodeOk != true) return@withContext if (encodeOk == null) TransformerOutcome.HANG else TransformerOutcome.FAIL
            val hasAudio = (item.channelCount ?: 0) > 0
            if (hasAudio) {
                val layout = resolveItemChannelLayout(item)
                val bitrate = resolveAudioBitrate(layout)
                if (!transcodeAudioTrackExperimental(item, audioOut, testDurationS, audioEncoder, audioBitrateBps = bitrate, audioTrackIndex = 0)) return@withContext TransformerOutcome.FAIL
                if (!remuxVideoAndAudio(bridge.outputFile, audioOut, muxedOut)) return@withContext TransformerOutcome.FAIL
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

    private suspend fun transcodeAudioTrackExperimental(
        item: MediaCollection.MediaNode.Item,
        outFile: File,
        durationS: Long? = null,
        encoder: String = "aac",
        audioBitrateBps: Int = 128_000,
        audioTrackIndex: Int = 0
    ): Boolean {
        val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"; val safeInputPath = inputPath ?: "pipe:0"
        val args = mutableListOf(ffmpegPath, "-y", "-hide_banner", "-hwaccel", "mediacodec", "-ndk_codec", "1", "-i", safeInputPath, "-vn", "-sn", "-map", "0:a:$audioTrackIndex?", "-c:a", encoder, "-b:a", "${audioBitrateBps / 1000}k")
        if (durationS != null) args.addAll(listOf("-t", durationS.toString()))
        args.addAll(channelArgs(resolveItemChannelLayout(item)))
        args.addAll(listOf("-f", "mp4", outFile.absolutePath))
        val result = runProcessCapture(args, timeoutMs = 300_000L, logStderrLive = true, stdinSource = if (safeInputPath == "pipe:0") item.uri else null)
        return result.exitCode == 0 && outFile.exists() && outFile.length() > 0
    }

    private suspend fun transcodeAudioTrack(
        item: MediaCollection.MediaNode.Item,
        outFile: File,
        durationS: Long? = null,
        startTimeMs: Long = 0,
        encoder: String = "aac",
        audioBitrateBps: Int = 128_000,
        audioTrackIndex: Int = 0
    ): Boolean {
        val inputPath = if (item.uri.scheme == "file") item.uri.path else "pipe:0"; val safeInputPath = inputPath ?: "pipe:0"
        val args = mutableListOf(ffmpegPath, "-y", "-hide_banner", "-hwaccel", "mediacodec", "-ndk_codec", "1")
        if (startTimeMs > 0) args.addAll(listOf("-ss", (startTimeMs / 1000.0).toString()))
        args.addAll(listOf("-i", safeInputPath, "-vn", "-sn", "-map", "0:a:$audioTrackIndex?", "-c:a", encoder, "-b:a", "${audioBitrateBps / 1000}k"))
        if (durationS != null) args.addAll(listOf("-t", durationS.toString()))
        args.addAll(channelArgs(resolveItemChannelLayout(item)))
        args.addAll(listOf("-f", "mp4", outFile.absolutePath))
        val result = runProcessCapture(args, timeoutMs = 300_000L, logStderrLive = true, stdinSource = if (safeInputPath == "pipe:0") item.uri else null)
        return result.exitCode == 0 && outFile.exists() && outFile.length() > 0
    }

    private suspend fun remuxVideoAndAudio(videoFile: File, audioFile: File, outFile: File): Boolean {
        Log.d(TAG, "Remuxing: V=${videoFile.absolutePath} (${videoFile.length()} bytes), A=${audioFile.absolutePath} (${audioFile.length()} bytes) -> ${outFile.absolutePath}")
        val args = listOf(ffmpegPath, "-y", "-hide_banner", "-i", videoFile.absolutePath, "-i", audioFile.absolutePath, "-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-f", "mp4", outFile.absolutePath)
        val result = runProcessCapture(args, timeoutMs = 60_000L, logStderrLive = true)
        val success = result.exitCode == 0 && outFile.exists() && outFile.length() > 0
        Log.d(TAG, "Remuxing finished: success=$success, exitCode=${result.exitCode}, targetSize=${outFile.length()}, ${diagnosticSnapshot()}")
        return success
    }

    private fun currentAppVersionCode(): Long = try { val info = context.packageManager.getPackageInfo(context.packageName, 0); info.longVersionCode } catch (e: PackageManager.NameNotFoundException) { -1L }

    /** Cheap point-in-time system health snapshot. Thermal throttling and memory
     *  pressure are the obvious suspects for a stall that hits ~50% of runs with
     *  no clean codec/resolution/audio-format correlation over a multi-hour test --
     *  attach this to progress/failure logs so a rerun can show whether stalls
     *  cluster with elevated thermal state or low available memory. */
    private fun diagnosticSnapshot(): String {
        return try {
            val thermalStr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                when ((context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.currentThermalStatus) {
                    PowerManager.THERMAL_STATUS_NONE -> "NONE"
                    PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
                    PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
                    PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
                    PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
                    PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
                    PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
                    else -> "unknown"
                }
            } else "n/a(api<29)"
            val memInfo = ActivityManager.MemoryInfo()
            (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memInfo)
            "thermal=$thermalStr, availMemMB=${memInfo.availMem / (1024 * 1024)}, lowMemory=${memInfo.lowMemory}"
        } catch (e: Exception) {
            "diagnosticSnapshot failed: ${e.message}"
        }
    }

    private data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

    private suspend fun runProcessCapture(args: List<String>, timeoutMs: Long = 15_000L, logStderrLive: Boolean = false, stdinSource: Uri? = null, verbose: Boolean = false): ProcessOutput {
        if (verbose) Log.d(TAG, "Starting ProcessBuilder: ${args.joinToString(" ")}")
        val startedAt = System.currentTimeMillis()
        val process = try { ProcessBuilder(args).start() } catch (e: Exception) {
            Log.e(TAG, "runProcessCapture: failed to start process: ${e.message} | args=${args.joinToString(" ")}", e)
            return ProcessOutput(-1, "", "Failed to start process: ${e.message}")
        }
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
        val out = if (exitCode == null) ProcessOutput(-1, finalStdout, finalStderr, true) else ProcessOutput(exitCode!!, finalStdout, finalStderr)
        val elapsedMs = System.currentTimeMillis() - startedAt
        if (out.exitCode != 0 || out.timedOut) {
            Log.e(TAG, "runProcessCapture: FAILED after ${elapsedMs}ms (exitCode=${out.exitCode}, timedOut=${out.timedOut}, timeoutMs=$timeoutMs, ${diagnosticSnapshot()})")
            Log.e(TAG, "runProcessCapture: args=${args.joinToString(" ")}")
            Log.e(TAG, "runProcessCapture: stderr tail=${out.stderr.takeLast(3000)}")
        } else if (verbose) {
            Log.d(TAG, "runProcessCapture: OK after ${elapsedMs}ms")
        }
        return out
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
