package acab.naiveha.upnpkino

import acab.naiveha.upnpkino.Constants.Transcoder.AudioCodec
import acab.naiveha.upnpkino.Constants.Transcoder.Container
import acab.naiveha.upnpkino.Constants.Transcoder.Resolution
import acab.naiveha.upnpkino.Constants.Transcoder.VideoCodec
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class TranscoderController(val context: Context, val upnpService: UpnpService) {
    companion object {
        private const val TAG = "TranscoderController"
        private const val RETRY_DELAY_MS = 500L
        private const val CAPABILITY_SCHEMA_VERSION = 1

        /** Cheap, static inventory of what the ffmpeg binary was compiled with. */
        data class RuntimeCapabilities(
            val compiledVideoEncoders: Set<VideoCodec>,
            val compiledAudioEncoders: Set<AudioCodec>,
            val compiledMuxers: Set<Container>
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
            val verifiedVideo: Map<VideoCodec, Set<Resolution>>,
            /** Audio codecs actually confirmed working (subset of what's compiled). */
            val verifiedAudio: Set<AudioCodec>
        ) {
            fun canEncode(codec: VideoCodec, resolution: Resolution): Boolean =
                resolution in (verifiedVideo[codec] ?: emptySet())

            fun canEncodeAudio(codec: AudioCodec): Boolean =
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
                        .mapNotNull { runCatching { VideoCodec.valueOf(it) }.getOrNull() }.toSet()
                    val compiledAudio = root.getJSONArray("compiledAudioEncoders").strings()
                        .mapNotNull { runCatching { AudioCodec.valueOf(it) }.getOrNull() }.toSet()
                    val compiledMuxers = root.getJSONArray("compiledMuxers").strings()
                        .mapNotNull { runCatching { Container.valueOf(it) }.getOrNull() }.toSet()

                    val verifiedVideoJson = root.getJSONObject("verifiedVideo")
                    val verifiedVideo = mutableMapOf<VideoCodec, Set<Resolution>>()
                    verifiedVideoJson.keys().forEach { key ->
                        val codec = runCatching { VideoCodec.valueOf(key) }.getOrNull() ?: return@forEach
                        val resolutions = verifiedVideoJson.getJSONArray(key).strings()
                            .mapNotNull { runCatching { Resolution.valueOf(it) }.getOrNull() }.toSet()
                        verifiedVideo[codec] = resolutions
                    }
                    val verifiedAudio = root.getJSONArray("verifiedAudio").strings()
                        .mapNotNull { runCatching { AudioCodec.valueOf(it) }.getOrNull() }.toSet()

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
    private val workDir get() = context.cacheDir

    /** Device/binary transcode capabilities. Null until the init check completes. */
    @Volatile
    var capabilities: CapabilitySnapshot? = null
        private set

    init {
        scope.launch {
            checkCapabilities()
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

    // -------------------------------------------------------------------
    // Capability checking — engages the real ffmpeg binary to determine
    // what it can transcode ON THIS DEVICE, not just what it's compiled
    // with. h264_mediacodec/hevc_mediacodec are thin MediaCodec wrappers
    // registered unconditionally at compile time, so `-encoders` lists
    // them even on hardware that can't actually open them, or that caps
    // out below a given resolution — only a real encode attempt (via
    // --enable-demuxer=lavfi --enable-filter=testsrc/sine) confirms that.
    // The result is cached in Preferences and only re-measured when the
    // app/binary or the OS build has actually changed.
    // -------------------------------------------------------------------

    private suspend fun checkCapabilities() {
        try {
            val cached = upnpService.preferences.getTranscoderCapabilities()?.let { CapabilitySnapshot.fromJson(it) }
            capabilities = if (cached != null && isValidCapabilities(cached)) {
                Log.d(TAG, "Trusting cached transcode capabilities from ${cached.checkedAtMillis}")
                cached
            } else {
                Log.d(TAG, "Running fresh transcode capability check")
                val fresh = runFullCapabilityCheck()
                upnpService.preferences.saveTranscoderCapabilities(fresh.toJson())
                fresh
            }
            Log.d(TAG, "Transcode capabilities ready: video=${capabilities?.verifiedVideo} audio=${capabilities?.verifiedAudio}")
        } catch (e: Exception) {
            Log.e(TAG, "Capability check failed: ${e.message}", e)
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

    private suspend fun runFullCapabilityCheck(): CapabilitySnapshot {
        val compiled = inspectCompiledCapabilities()
        Log.d(TAG, "Compiled capabilities: video=${compiled.compiledVideoEncoders} " +
            "audio=${compiled.compiledAudioEncoders} muxers=${compiled.compiledMuxers}")

        val verifiedVideo = mutableMapOf<VideoCodec, Set<Resolution>>()
        for (codec in compiled.compiledVideoEncoders) {
            val working = Resolution.entries.filter { testVideoEncoderWithRetry(codec, it) }.toSet()
            verifiedVideo[codec] = working
            Log.d(TAG, "Live-verified $codec at: $working")
        }

        val verifiedAudio = compiled.compiledAudioEncoders.filter { testAudioEncoderWithRetry(it) }.toSet()
        Log.d(TAG, "Live-verified audio: $verifiedAudio")

        return CapabilitySnapshot(
            appVersionCode = currentAppVersionCode(),
            ffmpegBinarySize = ffmpegFile.length(),
            ffmpegBinaryMtime = ffmpegFile.lastModified(),
            osFingerprint = Build.FINGERPRINT,
            checkedAtMillis = System.currentTimeMillis(),
            compiled = compiled,
            verifiedVideo = verifiedVideo,
            verifiedAudio = verifiedAudio
        )
    }

    private suspend fun inspectCompiledCapabilities(): RuntimeCapabilities = withContext(Dispatchers.IO) {
        val encoders = runProcessCapture(listOf(ffmpegPath, "-hide_banner", "-encoders")).stdout
        val muxers = runProcessCapture(listOf(ffmpegPath, "-hide_banner", "-muxers")).stdout

        val compiledVideo = VideoCodec.entries
            .filter { Regex("\\b${Regex.escape(it.ffmpegEncoder)}\\b").containsMatchIn(encoders) }.toSet()
        val compiledAudio = AudioCodec.entries
            .filter { Regex("\\b${Regex.escape(it.ffmpegEncoder)}\\b").containsMatchIn(encoders) }.toSet()
        val compiledMuxers = Container.entries
            .filter { Regex("\\b${Regex.escape(it.ffmpegMuxer)}\\b").containsMatchIn(muxers) }.toSet()

        RuntimeCapabilities(compiledVideo, compiledAudio, compiledMuxers)
    }

    /** A single failure could be transient hardware-codec contention; only trust a negative after a retry. */
    private suspend fun testVideoEncoderWithRetry(codec: VideoCodec, resolution: Resolution): Boolean {
        if (testVideoEncoder(codec, resolution)) return true
        delay(RETRY_DELAY_MS)
        val retried = testVideoEncoder(codec, resolution)
        Log.d(TAG, "${codec.name}@${resolution.name}: first attempt failed, retry=$retried")
        return retried
    }

    private suspend fun testAudioEncoderWithRetry(codec: AudioCodec): Boolean {
        if (testAudioEncoder(codec)) return true
        delay(RETRY_DELAY_MS)
        val retried = testAudioEncoder(codec)
        Log.d(TAG, "audio ${codec.name}: first attempt failed, retry=$retried")
        return retried
    }

    private suspend fun testVideoEncoder(codec: VideoCodec, resolution: Resolution): Boolean {
        val tempOut = File(workDir, "cap_check_${codec.name.lowercase()}_${resolution.name.lowercase()}.mp4")
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner",
            "-f", "lavfi", "-i", "testsrc=duration=1:size=${resolution.maxWidth}x${resolution.maxHeight}:rate=24",
            "-t", "1", "-c:v", codec.ffmpegEncoder, "-f", "mp4", tempOut.absolutePath
        )
        val result = runProcessCapture(args)
        tempOut.delete()
        return result.exitCode == 0
    }

    private suspend fun testAudioEncoder(codec: AudioCodec): Boolean {
        val tempOut = File(workDir, "cap_check_audio_${codec.name.lowercase()}.mp4")
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner",
            "-f", "lavfi", "-i", "sine=frequency=1000:duration=1",
            "-t", "1", "-ac", codec.testChannels.toString(), "-c:a", codec.ffmpegEncoder, "-f", "mp4", tempOut.absolutePath
        )
        val result = runProcessCapture(args)
        tempOut.delete()
        return result.exitCode == 0
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

    private data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String)

    private suspend fun runProcessCapture(args: List<String>): ProcessOutput = coroutineScope {
        val process = ProcessBuilder(args).start()
        val stdoutDeferred = async(Dispatchers.IO) { process.inputStream.bufferedReader().readText() }
        val stderrDeferred = async(Dispatchers.IO) { process.errorStream.bufferedReader().readText() }
        val exitCode = withContext(Dispatchers.IO) { process.waitFor() }
        ProcessOutput(exitCode, stdoutDeferred.await(), stderrDeferred.await())
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
