package acab.naiveha.upnpkino

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.os.PowerManager
import android.content.pm.PackageManager
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import java.util.Collections
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Log
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
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
import androidx.annotation.VisibleForTesting
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
import kotlin.math.roundToInt
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageWriter
import android.view.Surface
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.transformer.SurfaceAssetLoader
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred

class TranscoderController(val context: Context, val upnpService: UpnpService) {
    companion object {
        private const val TAG = "TranscoderController"
        private const val RETRY_DELAY_MS = 500L

        /** Matches a floating point or integer number anywhere in raw ffprobe
         *  `-of csv=p=0` output -- resilient to stray commas, "N/A", or other
         *  formatting noise between values, which a strict per-line parse
         *  (trim + toDoubleOrNull on the whole line) is not: at least one
         *  ffprobe build emits a stray trailing comma on the first csv row of
         *  any `-show_entries frame=...` output, for any file, which fails a
         *  strict parse on exactly that row. Shared by
         *  [probeKeyframeTimestampsMsViaFfprobe] and [probeFirstFramePtsMs]. */
        private val FFPROBE_NUMBER_REGEX = Regex("""\d+(\.\d+)?""")

        /** SharedPreferences name/key for [setPersistedOutputDirectory]'s choice
         *  of output directory (E5). A dedicated file rather than a shared
         *  "app prefs" one, since this controller has no visibility into what
         *  key names the rest of the app might already be using. */
        private const val PREFS_NAME = "transcoder_controller_prefs"
        private const val PREF_KEY_OUTPUT_DIR = "persisted_output_dir_uri"
        private const val CAPABILITY_SCHEMA_VERSION = 1

        private const val HW_PROBE_FRAME_LIMIT = 60
        private const val HW_PROBE_TIME_LIMIT_S = 2.0
        private const val HW_PROBE_TIMEOUT_MS = 10_000L

        /** Bounds for [probeKeyframeTimestampsMsViaMediaExtractor]: an overall
         *  wall-clock budget for the open+walk (best-effort -- see that
         *  function's doc comment for why a wedged native call underneath
         *  can still outlive this), and a hard cap on samples collected so a
         *  MediaExtractor that never reports end-of-stream can't loop forever. */
        private const val KEYFRAME_EXTRACTOR_TIMEOUT_MS = 20_000L
        private const val KEYFRAME_EXTRACTOR_MAX_SAMPLES = 200_000

        /** Floor [resolveFfmpegInputPath] insists stay free, after accounting
         *  for the source's own size, before it will mirror a content:// item
         *  to local cache. A round, conservative number rather than derived
         *  from anything -- big enough that a mirror can't be the thing that
         *  tips the device into a real low-storage state, small enough to
         *  still allow the fast-seek path on any reasonably-provisioned
         *  device. Tune freely; nothing else depends on the exact value. */
        private const val MIN_FREE_SPACE_AFTER_MIRROR_MB = 500L

        /** How far a chunk's total duration may fall short of what was requested
         *  before it's rejected. Was 2000ms -- loose enough that a real AV-sync
         *  problem (a few hundred ms) would sail through as long as it stayed
         *  under 2 full seconds. Tightened to just above realistic frame/AAC-block
         *  quantization slack, so a genuine drift actually gets caught. */
        private const val CHUNK_DURATION_TOLERANCE_MS = 250L

        /** How far a chunk may *overrun* the requested duration before it's
         *  rejected. Deliberately looser than [CHUNK_DURATION_TOLERANCE_MS], and
         *  the asymmetry is the point: shortfall is the truncation bug that check
         *  exists for, while overrun has legitimate sources -- AAC encoder
         *  priming/padding on the whole-file audio pre-encode, Media3 clipping on
         *  the safe path running slightly past the requested end. A false
         *  rejection is maximally expensive (the chunk retries identically, fails
         *  identically, then aborts the whole transcode), so the loose side stays
         *  loose. Don't "fix" this back to symmetric. Real overrun still matters
         *  -- a demuxer's seek can still land slightly past a requested boundary
         *  on some inputs -- it just needs a wider bar than shortfall.
         *  (The seam stutter this comment used to attribute to backwards -ss
         *  snapping turned out to be a different, unrelated bug: every chunk's
         *  own duration was always correct in isolation, which is exactly what
         *  this tolerance checks -- the composite streamed byte sequence's
         *  timeline was not, which nothing here can see. See
         *  patchFragmentTimestamps' doc comment for the actual mechanism and
         *  the fix; tightening this constant was never going to touch it.) */
        private const val CHUNK_OVERRUN_TOLERANCE_MS = 1000L

        /** How far a hw_ffmpeg chunk's actual first frame (per -copyts, see
         *  [verifyChunkStartAlignment]) may land from the seek target before
         *  it's treated as landing on the wrong keyframe rather than shipped.
         *  A *duration*-only check (the two tolerances above) cannot catch
         *  this class of failure: a chunk that starts early or late but is
         *  still cut to its full nominal length reports the requested
         *  duration while covering the wrong window, which is what actually
         *  produces overlapping or gapped video at a seam once
         *  [concatChunks] stitches chunks together. This has to sit above the
         *  encoder's own B-frame reorder delay (confirmed directly at ~83ms
         *  for a representative software encoder on a zero-seek chunk, i.e.
         *  present even with nothing wrong) and below the smallest plausible wrong-
         *  keyframe error (at least one whole missed GOP -- see
         *  [GOP_TARGET_SECONDS] -- so at least seconds, not tens of ms, for
         *  any realistic source). 500ms clears both with room to spare. */
        private const val CHUNK_START_ALIGNMENT_TOLERANCE_MS = 500L

        /** Shortfall tolerance for the *last* chunk of a range only. The range
         *  end comes from library-scan metadata; when the real file is slightly
         *  shorter, the final chunk legitimately requests past EOF and comes back
         *  short. Rejecting it discards every other chunk's work, so the final
         *  chunk gets slack the rest don't. [resolveSourceDurationMs] removes the
         *  cause for local sources; this covers the piped ones it can't. */
        private const val FINAL_CHUNK_TOLERANCE_MS = 2000L

        /** Dimension alignment some MediaCodec encoders require. This is where
         *  the long-standing 1088 comes from -- 1080 rounded up to a multiple of
         *  16 -- and why [videoScaleFilter] pads rather than stretches to reach
         *  it: the alignment is real (this file's history includes encoder-init
         *  failures at unaligned heights), the distortion it used to cause was
         *  not intended. */
        private const val ENCODER_DIMENSION_ALIGNMENT_PX = 16

        /** GOP target in seconds, converted against the real source frame rate
         *  by [encoderTuningArgs]. The old fixed `-g 24` was a 1-second GOP at
         *  the equally fixed 24fps; ~2s is a bitrate win at the same quality and
         *  costs nothing at the chunk seams, since ffmpeg emits an IDR at the
         *  start of every chunk encode regardless of `-g`. */
        private const val GOP_TARGET_SECONDS = 2.0

        /** Frame rate assumed when the real one can't be determined (piped
         *  source, failed probe), used only for GOP sizing. */
        private const val ASSUMED_FRAME_RATE_FPS = 30.0

        /** Output frame-rate ceiling. Nothing is ever resampled *up* to this and
         *  content at or below it passes through untouched -- it exists only to
         *  cap genuinely high-rate sources. */
        private const val MAX_OUTPUT_FRAME_RATE_FPS = 60

        /** Max allowed difference between a muxed chunk's video-track duration and
         *  its audio-track duration. Catches drift between the two independently
         *  produced legs that verifyChunkDuration (container-level only) can miss --
         *  see [verifyMuxedAvSync]. */
        private const val AV_SYNC_TOLERANCE_MS = 200L

        /** How far over the requested output box an encoder may legitimately land
         *  before [verifyOutputResolution] calls it a real miss: one alignment
         *  step. Encoders are free to align dimensions up (and Media3's
         *  DefaultEncoderFactory fallback will adjust a requested resolution to
         *  something the device actually supports), so a few pixels over is
         *  expected and only worth logging. A rejection here costs a chunk retry
         *  and then the whole transcode, so the check only fires on a miss big
         *  enough to mean the scale request was ignored outright. */
        private const val ENCODER_ALIGNMENT_SLACK_PX = ENCODER_DIMENSION_ALIGNMENT_PX

        const val DEFAULT_TIMEOUT_S = 60L
        const val DEFAULT_CHUNK_DURATION_MS = 30_000L

        /** How long [runProcessCapture] waits for its stdout/stderr readers to
         *  reach EOF after the child exits, before giving up on them. Bounded on
         *  purpose: on the timeout path the process was force-killed and its
         *  streams may never reach EOF, so an unbounded join would hang exactly
         *  where this file has hung before. */
        private const val READER_DRAIN_GRACE_MS = 2_000L

        /** How long a cancelled [Transformer] gets to report that it released its
         *  codecs. See [cancelAndAwait]. */
        private const val TRANSFORMER_CANCEL_TIMEOUT_MS = 5_000L

        /** Consecutive safe-path failures that look like codec-pool exhaustion
         *  before [transcodeInChunks] stops retrying. Retrying into an exhausted
         *  codec pool cannot succeed -- every attempt fails identically until the
         *  process restarts -- so the retry budget is better spent failing fast
         *  and loudly. */
        private const val CODEC_EXHAUSTION_FAILURE_LIMIT = 2

        /** How long the *streaming* path will wait for the hardware probe before
         *  proceeding on the safe path. A DLNA renderer typically abandons a
         *  connection that produces no bytes within 5-15s, so an unbounded wait on
         *  a 10s probe would cause the very timeout it exists to avoid. The
         *  file-save path has no such deadline and awaits properly. */
        private const val HW_PROBE_STREAM_WAIT_MS = 2_000L

        /** No byte growth in the safe path's output for this long and the export is
         *  treated as wedged rather than slow. Sized well clear of a slow first
         *  keyframe. This is the counterweight to [Budget]'s generous budgets: a
         *  stall surfaces in seconds while a merely-slow device still gets the time
         *  it needs. */
        private const val STALL_ABORT_MS = 30_000L

        /** Metadata probes read a container header; they don't scale with content
         *  length. The keyframe probe does walk the file, hence its own budget. */
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val KEYFRAME_PROBE_TIMEOUT_MS = 60_000L
        private const val AUDIO_SLICE_TIMEOUT_MS = 15_000L

        /** The keyframe probe's budget on the *streaming* path only, where it
         *  sits between the request arriving and the first byte going out.
         *  [KEYFRAME_PROBE_TIMEOUT_MS] plus [KEYFRAME_EXTRACTOR_TIMEOUT_MS] is up
         *  to 80 seconds of probing in front of a renderer that abandons a silent
         *  connection in 5-15, so the streaming path trades keyframe-snapped
         *  boundaries for getting bytes moving: whatever a cached index or a
         *  three-second look can supply, else fixed-time chunks.
         *
         *  That trade has a cost -- less precise chunk boundaries than a real
         *  keyframe index gives, and [CHUNK_OVERRUN_TOLERANCE_MS] exists partly
         *  for that -- but it is not the seam stutter every chunk boundary shows
         *  on this path. That turned out to be unconditional: present even with
         *  boundaries snapped exactly onto real keyframes, because it's a
         *  property of independently-invoked chunks' own reset-to-zero output
         *  timelines, not of seek precision. See patchFragmentTimestamps' doc
         *  comment for the mechanism and the fix -- fixed-time chunks were never
         *  the cause, so getting the keyframe probe to succeed more often here
         *  would not have touched it either. File-save mode has no such deadline
         *  and keeps the full budget. */
        private const val KEYFRAME_PROBE_STREAM_BUDGET_MS = 3_000L

        /** Most chunks a file-save transcode will attempt over a `pipe:0` source
         *  before refusing outright. The pipe is forward-only, so chunk n decodes
         *  and discards everything before its own start offset: cost is quadratic
         *  in chunk count, and a 90-minute film at [DEFAULT_CHUNK_DURATION_MS]
         *  is ~180 chunks, i.e. ~180 full reads of the source. Twenty chunks is
         *  ten minutes of content -- tolerable to re-read, and well clear of
         *  anything a user would sit through unknowingly.
         *
         *  Only file-save mode aborts. Streaming delivers progressively, so a
         *  slow start is visible rather than silent. */
        private const val PIPE_MAX_CHUNKS_BEFORE_ABORT = 20

        /** How long a [diagnosticSnapshot] stays fresh. The snapshot stats the
         *  filesystem and hits two system services, and Kotlin evaluates string
         *  templates whether or not the log line is kept, so at 13 call sites it
         *  was being rebuilt far more often than its inputs move. Thermal state
         *  and free space do not change meaningfully inside five seconds. */
        private const val SNAPSHOT_TTL_MS = 5_000L

        /** Lines of stderr kept per process. Enough for any ffmpeg failure tail
         *  worth reading, against the megabytes this used to accumulate at
         *  `-v debug` before being discarded down to a `takeLast(3000)`. */
        private const val STDERR_TAIL_LINES = 200

        /**
         * Process timeouts derived from the work being asked for, rather than one
         * flat 60s budget for everything.
         *
         * The flat budget was 2x real time for a 30s chunk. A mid-range device
         * doing 4K->1080p on the safe path runs well below that, so it blew the
         * budget every time, retried identically, and aborted -- a device being
         * slow was indistinguishable from a device being broken. These factors are
         * deliberately generous for that reason; [STALL_ABORT_MS] is what
         * distinguishes the two.
         *
         * Self-contained by design: no reference to the outer constants, so the
         * numbers here can be tuned without reading the rest of the file.
         */
        private object Budget {
            private const val MIN_CHUNK_BUDGET_MS = 30_000L
            private const val MIN_COPY_BUDGET_MS = 30_000L
            private const val MAX_COPY_BUDGET_MS = 900_000L
            private const val MIN_AUDIO_BUDGET_MS = 60_000L
            private const val MAX_AUDIO_BUDGET_MS = 600_000L

            /** Slowest real-time factor worth waiting for, by pipeline. */
            private fun rtFactor(strategy: String, resolution: Constants.Transcoder.Resolution): Double = when {
                strategy == "hw_ffmpeg" -> 4.0
                resolution == Constants.Transcoder.Resolution.UHD_4K -> 12.0
                else -> 8.0
            }

            fun forChunk(durationMs: Long, strategy: String, resolution: Constants.Transcoder.Resolution): Long =
                (durationMs * rtFactor(strategy, resolution)).toLong().coerceAtLeast(MIN_CHUNK_BUDGET_MS)

            /** Copy-only work (concat, remux): budget by bytes at a pessimistic
             *  throughput floor rather than by a fixed number that a 20GB input
             *  and a 200MB input are held to equally. */
            fun forStreamCopy(bytes: Long, floorBytesPerSec: Long = 8L * 1024 * 1024): Long =
                (bytes / floorBytesPerSec * 1000).coerceIn(MIN_COPY_BUDGET_MS, MAX_COPY_BUDGET_MS)

            /** Audio-only encode. Cheap per second of content, but it still decodes
             *  the source, so it scales with length instead of the flat 300s that a
             *  3-minute clip and a 3-hour film used to share. */
            fun forAudio(durationMs: Long): Long =
                (durationMs * 1.5).toLong().coerceIn(MIN_AUDIO_BUDGET_MS, MAX_AUDIO_BUDGET_MS)
        }

        /**
         * Fixed-size tail of a process's stderr.
         *
         * stderr at `-v debug` used to accumulate megabytes across a probe,
         * essentially all of which was then thrown away by a `takeLast(3000)`
         * on the failure log -- so only the tail was ever read, but the full
         * megabytes were held (and copied under lock) to get there. This keeps
         * only the tail in the first place.
         *
         * stdout deliberately does *not* use this -- it's parsed, and dropping its
         * head would corrupt a probe result rather than just a log line.
         */
        private class TailBuffer(private val maxLines: Int = STDERR_TAIL_LINES) {
            private val lines = ArrayDeque<String>()
            private var dropped = 0
            fun add(line: String) {
                if (lines.size == maxLines) { lines.removeFirst(); dropped++ }
                lines.addLast(line)
            }
            fun droppedLines(): Int = dropped
            override fun toString(): String = lines.joinToString("\n")
        }

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

    internal val repo = UpnpRepository.transcoder
    internal val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val ffmpegFile get() = FfmpegInstaller.ffmpegBinary(context)
    private val ffmpegPath get() = ffmpegFile.absolutePath
    private val ffprobeFile get() = FfmpegInstaller.ffprobeBinary(context)
    private val ffprobePath get() = ffprobeFile.absolutePath
    /** Home for the one thing under [workDir] that's genuinely disposable: a
     *  local mirror of a `content://` source (see [resolveFfmpegInputPath]).
     *  Losing one mid-copy costs nothing but a re-copy -- it's re-derivable
     *  from the source at any time -- which is exactly what `cacheDir` is for
     *  and why only this one artefact stays here after E3. Wiping the whole
     *  process-wide cache root on construction destroyed every other
     *  component's cache (Glide/Coil, OkHttp, WorkManager -- anything using
     *  Context.getCacheDir()), and `File.delete()` left their subdirectories
     *  behind anyway: too broad and incomplete at once.
     *
     *  `by lazy` rather than a getter also settles the nullability this file was
     *  inconsistent about -- one site treated it as nullable, eight didn't, and
     *  Context.cacheDir is non-null. */
    private val workDir: File by lazy { File(context.cacheDir, "transcode").apply { mkdirs() } }

    /** Home for everything else this controller writes: chunks, the concat
     *  list, safe-path legs, the pre-publish output -- in-progress work that
     *  is small but expensive to redo, not disposable the way a source mirror
     *  is. `cacheDir` is reclaimable by the OS under storage pressure with no
     *  warning and no regard for whether a write is in flight; losing 50
     *  already-encoded chunks moments before [concatChunks] runs loses the
     *  whole job, not just a mirror copy that [resolveFfmpegInputPath] would
     *  cheerfully redo. `noBackupFilesDir` is internal app storage (not
     *  reclaimed under pressure, not swept into auto-backup either, which
     *  matters for what amounts to transient scratch data) -- deliberately
     *  *not* `filesDir`/plain internal storage, and deliberately not where the
     *  source mirror lives: that one artefact can be gigabytes, and putting
     *  something that size in non-reclaimable app storage would make this
     *  controller the thing filling a user's device with no way for the OS to
     *  recover the space (E3). */
    private val durableWorkDir: File by lazy { File(context.noBackupFilesDir, "transcode").apply { mkdirs() } }

    /** Deletes this controller's own working files under both [workDir] and
     *  [durableWorkDir], recursively, leaving the rest of the process's cache
     *  and app storage alone. Also the startup sweep that clears whatever a
     *  previous crash orphaned in either tier -- necessary now that every
     *  filename in both is session-scoped (B6/C1) rather than reused, since
     *  nothing else would ever revisit and clean up an old session's files. */
    private fun cleanWorkDir() {
        listOf(workDir, durableWorkDir).forEach { dir ->
            val entries = dir.listFiles()
            if (entries == null) {
                Log.w(TAG, "cleanWorkDir: could not list ${dir.absolutePath}")
                return@forEach
            }
            entries.forEach { it.deleteRecursively() }
            Log.d(TAG, "cleanWorkDir: cleared ${entries.size} entrie(s) from ${dir.absolutePath}")
        }
    }

    /** In-flight or completed mirror resolution per item.id -- see
     *  [resolveFfmpegInputPath]. A `Deferred` rather than the resolved path
     *  because the map has to make the *decision* atomic, not just the storage:
     *  a ConcurrentHashMap made check-then-copy-then-publish look safe while two
     *  concurrent callers for one item both missed the cache and both opened the
     *  same mirror path for writing, interleaving their bytes into one file and
     *  handing the result to ffmpeg.
     *
     *  Started lazily, so `computeIfAbsent`'s mapping function stays free of
     *  side effects, and on [scope], so [release] fails any mirror still in
     *  flight rather than leaving a copy running with nothing waiting on it. */
    private val mirrorJobs = ConcurrentHashMap<String, Deferred<String>>()

    /** Local mirror files, keyed by item.id, so they can be deleted later. */
    private val localSourceMirrors = ConcurrentHashMap<String, File>()

    /** Every child process currently running, so [release] has something to kill.
     *  Without it, `release()` returned while ffmpeg kept transcoding: the
     *  process outlives the coroutine that spawned it, and cancelling a scope
     *  says nothing to a separate OS process. */
    private val liveProcesses = Collections.newSetFromMap(ConcurrentHashMap<Process, Boolean>())

    /** One pool for every child process's stdio pumps, instead of the fresh
     *  3-thread pool [runProcessCapture] used to build and tear down per spawn.
     *  That cost ~18 thread lifecycles per safe-path chunk and ~3,200 across a
     *  180-chunk film, for work that is almost entirely blocked on a pipe.
     *
     *  Cached and unbounded on purpose, *not* a fixed size: each live process
     *  needs up to three pump coroutines (stderr, stdout, stdin), so a fixed
     *  3-thread pool would starve the moment two processes overlap -- a
     *  streaming session alongside a file save, say -- and the symptom of that
     *  starvation is a hang, which is the last thing this file needs another
     *  source of. Daemon threads so a leaked one can never hold the process up;
     *  idle ones retire on the cached pool's own 60s timeout, which is what
     *  keeps the steady-state thread count flat instead of sawtoothing.
     *
     *  Closed by [release]. */
    private val processIoDispatcher: ExecutorCoroutineDispatcher =
        Executors.newCachedThreadPool { r -> Thread(r, "transcode-proc-io").apply { isDaemon = true } }
            .asCoroutineDispatcher()

    /** Consecutive safe-path failures that looked like codec-pool exhaustion.
     *  Reset by any successful video leg. Read by [transcodeInChunks]: once the
     *  device's global codec budget is gone, every subsequent attempt fails
     *  identically until the process restarts, so retrying is pure cost. */
    private val consecutiveCodecAllocationFailures = AtomicInteger(0)

    /** Everything a single ffprobe pass over a file yields that this class
     *  needs. Null fields mean "couldn't determine" -- every caller has a
     *  fallback.
     *
     *  One shape for source files and generated chunk files alike, because the
     *  probes had multiplied: per safe-path chunk this file used to spawn
     *  ffprobe for the source's duration and frame rate, the video-only leg's
     *  duration, the video-only leg's dimensions, the muxed output's duration,
     *  and the muxed output's video and audio stream durations -- six spawns
     *  asking for fields one `-show_entries` can return together. */
    private data class MediaProbe(
        val formatDurationMs: Long? = null,
        val videoDurationMs: Long? = null,
        val audioDurationMs: Long? = null,
        val size: PixelSize? = null,
        val frameRate: Double? = null,
        val hasAudio: Boolean = false,
        // (10) Raw ffprobe strings (e.g. "bt709", "bt2020nc"), not parsed into
        // an enum: colorMetadataArgs passes them straight back to ffmpeg's own
        // -color_primaries/-color_trc/-colorspace, which take the same
        // vocabulary, so there's nothing to gain from an intermediate type.
        val colorPrimaries: String? = null,
        val colorTransfer: String? = null,
        val colorSpace: String? = null,
        // (15) ffprobe's stream-level field_order ("tt"/"bb"/"tb"/"bt" for
        // interlaced, "progressive" for not), null when undetected or not
        // declared by the container. See isInterlaced.
        val fieldOrder: String? = null
    )

    /** [MediaProbe] for each *source*, per item.id, so the probe behind it is
     *  paid at most once per item instead of once per chunk. Never cleared: a
     *  source file's duration, geometry and frame rate are immutable properties
     *  of it.
     *
     *  Source probes only. Results on generated chunk files must never land
     *  here -- those paths are reused across chunks and rewritten between
     *  them, so a cached probe would describe the previous chunk. */
    private val probedSourceMedia = ConcurrentHashMap<String, MediaProbe>()

    /** Result of [probedHasAudioTrack] per item.id -- the fallback path only,
     *  used when the scan recorded no audio metadata for an item. */
    private val probedAudioPresence = ConcurrentHashMap<String, Boolean>()

    /** Keyframe timestamps per item.id -- see [probeKeyframeTimestampsMs].
     *
     *  The index is a property of the file and does not change, yet it was
     *  recomputed for every HTTP request, and a DLNA renderer routinely opens
     *  two or three per item (probe, playback, seek). This is the cheapest and
     *  highest-value item in the streaming-latency work: the second request for
     *  an item pays nothing.
     *
     *  Successful probes only. A probe that found nothing over `pipe:0` can
     *  succeed later against a mirror some other call has since paid for, so
     *  caching the miss would pin an item to fixed-time chunks for the
     *  controller's whole life. */
    private val keyframeIndex = ConcurrentHashMap<String, List<Long>>()

    /** Device/binary transcode capabilities. Null until the init check completes. */
    @Volatile
    var capabilities: CapabilitySnapshot? = null
        private set

    /**
     * Set once by [probeHardwarePipeline] during init and cached for the
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

    /** Completed exactly once by `init`, with the probe's verdict.
     *
     *  The `null`-until-probed property above meant any request arriving inside
     *  the probe's 10s window read null and took the safe path by race rather
     *  than by policy -- and since the safe path's budget is tighter relative to
     *  its real cost, losing that race could turn into an outright failure
     *  rather than merely a slower transcode.
     *
     *  Kept alongside the property, not replacing it: three callers in the
     *  strategy selectors still read it synchronously, and those functions are
     *  slated for deletion (D3/D7). The property can go with them. */
    private val hwPipelineReady = CompletableDeferred<Boolean>()

    /** Suspends until the one-time hardware probe has run, then reports its
     *  verdict. Use this anywhere that can afford to wait; the streaming path
     *  can't and bounds it with [HW_PROBE_STREAM_WAIT_MS] instead. */
    private suspend fun useHardwareFfmpeg(): Boolean = hwPipelineReady.await()

    fun selectTranscodingStrategy(item: MediaCollection.MediaNode.Item): String? {
        val vCodec = item.videoCodec
        val aCodec = item.audioCodec

        // FFmpeg capabilities (decoders) as defined in build_libffmpeg_for_android_gpl_free.sh
        val ffmpegVideoDecoders = setOf(
            Constants.Transcoder.VideoCodec.H264, Constants.Transcoder.VideoCodec.HEVC,
            Constants.Transcoder.VideoCodec.MPEG2, Constants.Transcoder.VideoCodec.MPEG4,
            Constants.Transcoder.VideoCodec.WMV3, Constants.Transcoder.VideoCodec.VC1,
            Constants.Transcoder.VideoCodec.MJPEG, Constants.Transcoder.VideoCodec.VP8,
            Constants.Transcoder.VideoCodec.VP9, Constants.Transcoder.VideoCodec.AV1,
            Constants.Transcoder.VideoCodec.PRORES, Constants.Transcoder.VideoCodec.DNXHD
        )
        val ffmpegAudioDecoders = setOf(
            Constants.Transcoder.AudioCodec.AAC, Constants.Transcoder.AudioCodec.AC3,
            Constants.Transcoder.AudioCodec.EAC3, Constants.Transcoder.AudioCodec.DCA,
            Constants.Transcoder.AudioCodec.MP3, Constants.Transcoder.AudioCodec.OPUS,
            Constants.Transcoder.AudioCodec.VORBIS, Constants.Transcoder.AudioCodec.FLAC,
            Constants.Transcoder.AudioCodec.ALAC, Constants.Transcoder.AudioCodec.MLP,
            Constants.Transcoder.AudioCodec.TRUEHD, Constants.Transcoder.AudioCodec.PCM_S16LE,
            Constants.Transcoder.AudioCodec.WMAV2
        )

        // Media3 Transformer capabilities (standard hardware-accelerated formats)
        val media3VideoCodecs = setOf(
            Constants.Transcoder.VideoCodec.H264, Constants.Transcoder.VideoCodec.HEVC,
            Constants.Transcoder.VideoCodec.VP8, Constants.Transcoder.VideoCodec.VP9,
            Constants.Transcoder.VideoCodec.AV1
        )
        val media3AudioCodecs = setOf(
            Constants.Transcoder.AudioCodec.AAC, Constants.Transcoder.AudioCodec.MP3,
            Constants.Transcoder.AudioCodec.OPUS, Constants.Transcoder.AudioCodec.VORBIS,
            Constants.Transcoder.AudioCodec.FLAC, Constants.Transcoder.AudioCodec.AC3,
            Constants.Transcoder.AudioCodec.EAC3
        )

        val vStrat = when {
            useHardwareFfmpegPipeline == true && vCodec in ffmpegVideoDecoders -> "ffmpeg"
            vCodec in media3VideoCodecs -> "media3"
            vCodec in ffmpegVideoDecoders -> "ffmpeg"
            else -> null
        }

        val aStrat = when {
            useHardwareFfmpegPipeline == true && aCodec in ffmpegAudioDecoders -> "ffmpeg"
            aCodec in media3AudioCodecs -> "media3"
            aCodec in ffmpegAudioDecoders -> "ffmpeg"
            else -> "ffmpeg" // Default to ffmpeg for wide coverage
        }

        return when {
            vStrat == "ffmpeg" && aStrat == "ffmpeg" -> "ffmpeg"
            vStrat == "media3" && aStrat == "media3" -> "media3"
            vStrat != null && aStrat != null -> "hybrid"
            else -> null
        }
    }
    fun selectTranscodingStrategy1(item: MediaCollection.MediaNode.Item): String?{
        val frameHeight = item.resolution.split("x").getOrNull(1)?.toIntOrNull() ?: 0
        val resolution = Constants.Transcoder.Resolution.entries.sortedBy { it.maxHeight }.find { it.maxHeight >= frameHeight }
        if (item.videoCodec == Constants.Transcoder.VideoCodec.UNKNOWN || frameHeight == 0 || resolution == null){
            return null
        }
        val dtsAudio = item.audioCodec == Constants.Transcoder.AudioCodec.DCA ||item.audioTracks.any { it.codec == Constants.Transcoder.AudioCodec.DCA }
        val audioStrategy = when{
            item.audioCodec == Constants.Transcoder.AudioCodec.AC3 -> "ffmpeg"
            item.audioCodec == Constants.Transcoder.AudioCodec.AAC -> "ffmpeg"
            dtsAudio -> "media3"
            else -> null
        }
        var videoStrategy = when{
            resolution == Constants.Transcoder.Resolution.UHD_4K -> "media3"
            item.videoCodec == Constants.Transcoder.VideoCodec.MPEG4 ||
                    item.videoCodec == Constants.Transcoder.VideoCodec.WMV3 ||
                    item.name.lowercase().endsWith(".avi") -> "media3"
            item.videoCodec == Constants.Transcoder.VideoCodec.HEVC -> "ffmpeg"
            else -> null
        }
        if (videoStrategy != null && useHardwareFfmpegPipeline == false){
            videoStrategy = "media3"
        }

        return when {
            audioStrategy == "ffmpeg" && videoStrategy == "ffmpeg" -> "ffmpeg"
            audioStrategy == "ffmpeg" && videoStrategy == "media3" -> "hybrid"
            audioStrategy == "media3" && videoStrategy == "media3" -> "media3"
            else -> null
        }
    }

    init {
        scope.launch {
            try {
                Log.d(TAG, "Cleaning up transcode cache directory")
                cleanWorkDir()

                probeHardwarePipeline()

                // Debug builds only. This is a real end-to-end transcode of every
                // fast-start item in the library, publishing a transcoded_*.mp4
                // next to each source -- a diagnostic harness for the surface-input
                // investigation, not something a user's device should ever run on
                // launch. Gated rather than deleted deliberately: it's the fastest
                // iteration loop on the problem currently being worked.
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "runLibraryBenchmark")
                    runLibraryBenchmark()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Init/Test crashed: ${e.message}", e)
                useHardwareFfmpegPipeline = false
            } finally {
                // Nothing may be left waiting on the probe, whatever happened
                // above. complete() returns false rather than throwing if the
                // value was already published, so this is safe on every path.
                if (hwPipelineReady.complete(useHardwareFfmpegPipeline == true)) {
                    Log.w(TAG, "init: hardware probe never published a verdict -- defaulting waiters to safe path")
                }
            }
        }
    }

    /** The one-time hardware capability probe. Publishes its verdict to both
     *  [useHardwareFfmpegPipeline] and [hwPipelineReady]. */
    private suspend fun probeHardwarePipeline() {
        Log.d(TAG, "Running quick hardware capability check (720p synth)")
        val probeFile = File(ffmpegFile.parentFile, "hevc_720p_short_ac3.so")

        if (!probeFile.exists()) {
            Log.w(TAG, "Probe file missing at ${probeFile.absolutePath}, assuming hardware pipeline unsupported")
            val dir = ffmpegFile.parentFile
            val entries = dir?.listFiles()
            if (entries == null) {
                Log.w(TAG, "runHwProbe fixture check: could not list ${dir?.absolutePath}")
            } else {
                Log.w(TAG, "runHwProbe fixture check: ${dir.absolutePath} actually contains ${entries.size} entrie(s) --")
                entries.sortedBy { it.name }.forEachIndexed { i, f ->
                    Log.w(TAG, "runHwProbe fixture check: [$i] ${f.name} (${if (f.isDirectory) "dir" else "${f.length()} bytes"})")
                }
            }
            useHardwareFfmpegPipeline = false
        } else if (!encoderAdvertisesSupport(Constants.Transcoder.VideoCodec.H264, Constants.Transcoder.Resolution.HD_720)) {
            // Definitive in milliseconds via MediaCodecInfo/EncoderUtil -- skip
            // spending a real probe encode (and its timeout budget) finding out
            // what the device has already told us it can't do.
            Log.w(TAG, "runHwProbe fixture check: device does not advertise H264 @ 720p support -- skipping probe encode, defaulting to safe path")
            useHardwareFfmpegPipeline = false
        } else {
            Log.d(TAG, "runHwProbe fixture check: found ${probeFile.absolutePath} (${probeFile.length()} bytes, readable=${probeFile.canRead()})")
            val outcome = runHwProbe(
                label = "QUICK_720p_PROBE",
                inputPath = probeFile.absolutePath,
                limitMode = LimitMode.TIME,
                includeAudio = true,
                targetCodec = Constants.Transcoder.VideoCodec.H264,
                resolution = Constants.Transcoder.Resolution.HD_720,
                // Explicit, and the reason it is explicit matters: this probe's
                // stderr is the primary artefact of the open surface-input
                // investigation, so it keeps debug output while every other
                // runHwProbe caller gets the quiet default. TailBuffer bounds
                // what that costs in memory. Drop this argument once the
                // investigation closes.
                verbosity = "debug"
            )
            useHardwareFfmpegPipeline = (outcome == HwProbeOutcome.PASS)
        }
        Log.d(TAG, "Hardware capability check complete -- useHardwareFfmpegPipeline=$useHardwareFfmpegPipeline")
        hwPipelineReady.complete(useHardwareFfmpegPipeline == true)
    }

    /**
     * Library-wide soak/benchmark harness. **Debug builds only** -- this runs a
     * real end-to-end transcode of every fast-start library item and publishes a
     * transcoded_*.mp4 next to each source. Never call it from a release path.
     *
     * Kept in the main source set rather than moved to androidTest while the
     * surface-input investigation is open: it is the fastest iteration loop on
     * that problem, and moving it behind the instrumentation boundary now would
     * cost more than the gate does. Revisit when the investigation closes.
     */
    @VisibleForTesting
    internal suspend fun runLibraryBenchmark() {
        val libraryItems = UpnpRepository.kinoService.sharedMediaCollection.value.values
            .filterIsInstance<MediaCollection.MediaNode.Item>()

        // D23r.1: the old synthCases harness could construct mono/5.1 fixtures
        // to order; this one only sees whatever the real library happens to
        // contain, so it can't manufacture coverage that isn't already there.
        // What it can do is say plainly whether this run's library gave that
        // coverage, rather than leaving the gap silent -- see the summary
        // logged after the loop below.
        val exercisedAudioLayouts = mutableSetOf<AudioChannelLayout>()

        Log.d(TAG, "UNIFIED TEST: Starting library-wide transcode test")
        for (item in libraryItems) {
            // Smart detection of unstreamable containers
            if (!item.isFastStart) {
                Log.d(TAG, "UNIFIED TEST: Skipping '${item.name}' (moov atom at end of file, piping not possible)")
                continue
            }
            val targetVideoCodec = if (item.videoCodec == Constants.Transcoder.VideoCodec.H264) {
                Constants.Transcoder.VideoCodec.HEVC
            } else {
                Constants.Transcoder.VideoCodec.H264
            }

            val wallStart = System.currentTimeMillis()
            val success = transcodeMediaFile(
                item = item,
                startTimeMs = 0,
                targetVideoCodec = targetVideoCodec,
                targetResolution = null, // Keep original
                subtitleTrackIndex = null, // Future development
                audioTrackIndex = 0, // Default track
                targetAudioCodec = Constants.Transcoder.AudioCodec.AAC,
                responder = null
            )
            val wallElapsed = System.currentTimeMillis() - wallStart

            if (success) {
                resolveTrackChannelCount(item, audioTrackIndex = 0)
                    ?.takeIf { it > 0 }
                    ?.let { exercisedAudioLayouts += resolveChannelLayout(it) }
                val rtFactor = if (item.durationMs > 0) wallElapsed.toDouble() / item.durationMs else null
                Log.d(TAG, "UNIFIED TEST: '${item.name}' (${item.durationMs}ms) -> SUCCESS in ${wallElapsed}ms" +
                        (rtFactor?.let { " (RT: ${String.format(Locale.US, "%.2f", it)}x)" } ?: ""))
            } else {
                Log.e(TAG, "UNIFIED TEST: '${item.name}' (${item.durationMs}ms) -> FAILED in ${wallElapsed}ms")
            }
            break
            delay(1000.milliseconds)
        }
        val uncoveredLayouts = AudioChannelLayout.entries.toSet() - exercisedAudioLayouts
        if (uncoveredLayouts.isNotEmpty()) {
            Log.w(TAG, "UNIFIED TEST: audio-layout coverage this run: $exercisedAudioLayouts -- " +
                    "$uncoveredLayouts not exercised by any item in this library, so the hardware AAC " +
                    "encoder's acceptance of ${uncoveredLayouts.joinToString(", ")} is still unconfirmed (D23r)")
        }
        Log.d(TAG, "UNIFIED TEST: Finished")
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
            else -> ""
        }

    /** Canonical channel layouts this app explicitly supports end-to-end.
     *  ffmpeg's native aac encoder rejects channel counts/layouts it doesn't
     *  recognize by name (this is what caused an earlier hardware-probe
     *  crash: a source reporting a bare "6 channels" with no named layout).
     *  Sources with 1 or 6 channels map directly; anything else -- including
     *  7.1's 8 channels -- downmixes to one of these two verified targets
     *  rather than being passed through as a raw channel count ffmpeg might
     *  reject. See [resolveChannelLayout] for which target a given count
     *  gets.
     */
    private enum class AudioChannelLayout(val channelCount: Int, val ffmpegLayoutName: String?) {
        MONO(1, null),
        STEREO(2, null),
        SURROUND_5_1(6, "5.1")
    }

    /** Anything above 6 channels (7.1, most commonly) downmixes to 5.1 rather
     *  than stereo (12) -- strictly closer to the source, and it reuses the
     *  already hardware-verified SURROUND_5_1 path instead of requiring a new
     *  encoder-capability check for a dedicated 7.1 layout (see the class doc
     *  on [AudioChannelLayout] for why that table stays narrow). 2 channels,
     *  an unknown count, and any other in-between count still fall back to
     *  stereo, same as before this change. */
    private fun resolveChannelLayout(sourceChannelCount: Int?): AudioChannelLayout = when {
        sourceChannelCount == 1 -> AudioChannelLayout.MONO
        sourceChannelCount == 6 -> AudioChannelLayout.SURROUND_5_1
        sourceChannelCount != null && sourceChannelCount > 6 -> AudioChannelLayout.SURROUND_5_1
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
        // When the source is piped in, ffprobe has to be pointed at "pipe:0" --
        // the real path is not what it will be reading, and on a content:// item
        // it isn't openable by ffprobe at all. Callers pass the item's own path
        // alongside a stdin source (see runHwProbe), so normalize here instead of
        // trusting every call site to do it.
        val probeTarget = if (stdinSource != null) "pipe:0" else inputPath
        val args = listOf(
            ffprobePath, "-hide_banner", "-v", "error",
            "-select_streams", "a:0",
            "-show_entries", "stream=channels",
            "-of", "csv=p=0",
            probeTarget
        )
        val result = runProcessCapture(args, timeoutMs = 5_000L, stdinSource = stdinSource)
        if (result.exitCode != 0) return null
        return result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.toIntOrNull()
    }

    /**
     * Channel count of the audio track that is actually going to be mapped.
     *
     * [item.channelCount] is the *default* track's count, so on a file whose
     * default is stereo but whose track 1 is a 5.1 commentary, deriving the
     * encode arguments from it produced a `-ac 2` for a 6-channel input --
     * the encoders that tolerate that downmix silently, and the ones that
     * don't fail the chunk. Falls back to the item-level count when the
     * requested index isn't in the scanned track list or the scan didn't
     * record a per-track count, which keeps the previous behavior for
     * single-track files.
     */
    private fun resolveTrackChannelCount(item: MediaCollection.MediaNode.Item, audioTrackIndex: Int): Int? =
        item.audioTracks.getOrNull(audioTrackIndex)?.channelCount ?: item.channelCount

    /**
     * The channel layout to encode a library item's [audioTrackIndex] track
     * with, trusting the one-time library scan
     * (see [resolveTrackChannelCount]) rather than re-probing.
     * Real transcodes call this on every invocation -- including per-segment
     * calls against a portion of the same file during playback -- so
     * re-running ffprobe here would mean paying that cost repeatedly for a
     * file whose channel count was already established at scan time and
     * doesn't change mid-file.
     *
     * Deliberately pure -- no logging here. This is called once per chunk
     * (see [transcodeViaHardwareFfmpeg] and [transcodeAudioTrack]) as well as
     * once per request (see [transcodeMediaFile]), so a downmix warning
     * placed here would fire once per chunk instead of once per transcode.
     * [transcodeMediaFile]'s single top-level call is where that's logged,
     * once, for the whole request (D23r.3).
     */
    private fun resolveItemChannelLayout(item: MediaCollection.MediaNode.Item, audioTrackIndex: Int = 0): AudioChannelLayout =
        resolveChannelLayout(resolveTrackChannelCount(item, audioTrackIndex))

    private enum class HwProbeOutcome { PASS, FAIL, HANG }
    private enum class LimitMode { FRAME_COUNT, TIME }

    /** The `-t`/`-frames:v` args for [mode], paired with the real-time-factor
     *  denominator they imply. Returned together so the RT-factor log in
     *  [runHwProbe] can read the limit it just requested directly instead of
     *  re-parsing it back out of the assembled `args` list twenty lines later
     *  to recover a value this function already knew (D14). */
    private fun limitArgs(mode: LimitMode): Pair<List<String>, Double> = when (mode) {
        LimitMode.FRAME_COUNT -> listOf("-frames:v", HW_PROBE_FRAME_LIMIT.toString(), "-t", "3") to (HW_PROBE_FRAME_LIMIT / 30.0)
        LimitMode.TIME -> listOf("-t", HW_PROBE_TIME_LIMIT_S.toString()) to HW_PROBE_TIME_LIMIT_S
    }

    /**
     * The nominal box for a resolution tier, *unaligned*: HD_1080 is 1920x1080
     * here, not 1920x1088. Alignment is applied at the end of
     * [videoScaleFilter] as padding, so it can no longer reach the scaler and
     * distort the picture. The literal 1920 for HD_1080 is carried over from
     * the two call sites that spelled it out rather than trusting
     * `resolution.maxWidth`.
     */
    private fun tierBox(resolution: Constants.Transcoder.Resolution): PixelSize =
        if (resolution == Constants.Transcoder.Resolution.HD_1080) PixelSize(1920, 1080)
        else PixelSize(resolution.maxWidth, resolution.maxHeight)

    /** Rounds a dimension up to [ENCODER_DIMENSION_ALIGNMENT_PX]. Yields 1088
     *  for 1080, which is exactly what the old `testHeightFor` hardcoded for
     *  HD_1080 -- the alignment requirement it encoded is preserved, just
     *  generalized to whatever height a scaled frame actually lands on. */
    private fun alignUpForEncoder(value: Int): Int {
        val alignment = ENCODER_DIMENSION_ALIGNMENT_PX
        return ((value + alignment - 1) / alignment) * alignment
    }

    /**
     * The `-vf` chain for a video encode: fit the frame inside [box] without
     * distorting it, then pad to encoder-aligned dimensions.
     *
     * Replaces a bare `scale=W:H`, which stretched anything whose aspect ratio
     * didn't match the target. With the old 1088 height for HD_1080 even 16:9
     * content was distorted (1920/1088 = 1.7647 against 16/9 = 1.7778), and a
     * 2.39:1 film targeted at 1080p was stretched vertically by around 35%.
     * `force_original_aspect_ratio=decrease` fits instead of stretching, and
     * `pad` re-adds the alignment the encoder wants as black bars rather than
     * as distortion.
     *
     * The pad is always emitted, even when [box] is already aligned, because
     * `decrease` can round a fitted dimension to an odd number that a 4:2:0
     * encoder would reject -- padding to a fixed aligned target makes the
     * output dimensions deterministic for a given box, which also keeps every
     * chunk of one item identical for [concatChunks]' stream copy. When no
     * padding is needed it's a no-op.
     *
     * Deliberately limited to filters that predate ffmpeg 4.4: no
     * `force_divisible_by`, since the bundled binary
     * (build_libffmpeg_for_android_gpl_free.sh) hasn't been confirmed to have
     * it.
     *
     * @param deinterlace (15) prepends `yadif` ahead of the scale/pad chain
     *   when true. Callers pass [isInterlaced] of the source's own probed
     *   field order, so this only fires for sources that actually reported
     *   interlaced content -- progressive sources take the same scale/pad
     *   chain as before, unchanged. Bare `yadif` uses its default mode
     *   (`send_frame`), which outputs one deinterlaced frame per input frame
     *   rather than doubling the frame rate, so this doesn't disturb the
     *   GOP/frame-rate sizing [encoderTuningArgs] does downstream.
     */
    private fun videoScaleFilter(box: PixelSize, deinterlace: Boolean = false): String {
        val padded = PixelSize(alignUpForEncoder(box.width), alignUpForEncoder(box.height))
        // Use named parameters for 'pad' to avoid parsing errors on some ffmpeg versions
        // when using expressions in positional arguments.
        val deinterlacePrefix = if (deinterlace) "yadif," else ""
        return "${deinterlacePrefix}scale=w=${box.width}:h=${box.height}:force_original_aspect_ratio=decrease," +
            "pad=w=${padded.width}:h=${padded.height}:x=(ow-iw)/2:y=(oh-ih)/2"
    }

    private data class PixelSize(val width: Int, val height: Int) {
        override fun toString(): String = "${width}x$height"
    }

    /** Source pixel dimensions as recorded by the library scan ("WxH"), or
     *  null if that string isn't parseable -- callers treat null as "don't
     *  make any resolution decisions for this item". */
    private fun parseSourceSize(item: MediaCollection.MediaNode.Item): PixelSize? {
        val parts = item.resolution.split("x")
        val width = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val height = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: return null
        return if (width > 0 && height > 0) PixelSize(width, height) else null
    }

    /**
     * The box a video leg should actually encode into: [source] scaled to fit
     * inside [box], aspect ratio preserved, never upscaled, both axes even.
     *
     * The fit is computed here rather than left to the scaler or to
     * Presentation because both would *upscale* anything smaller than the
     * target -- [transcodeMediaFile] rounds a source height *up* to the next
     * tier, so a 1280x536 scope film resolves to HD_1080 and a naive
     * 1920x1080 request would blow it up -- and because a box matching the
     * source's own aspect ratio means neither path has to letterbox or
     * stretch to reach it.
     *
     * Rounding is always downward to an even number, since encoders reject odd
     * dimensions for 4:2:0 chroma.
     */
    private fun resolveOutputSize(source: PixelSize, box: PixelSize): PixelSize {
        val scale = min(
            1.0,
            min(
                box.width.toDouble() / source.width,
                box.height.toDouble() / source.height
            )
        )
        fun evenDown(value: Int): Int = (value - (value % 2)).coerceAtLeast(2)
        return PixelSize(
            evenDown((source.width * scale).roundToInt()),
            evenDown((source.height * scale).roundToInt())
        )
    }

    /** [item]'s scanned dimensions fitted into [resolution]'s tier -- the raw,
     *  UNALIGNED box (see [resolveOutputSize]). Falls back to the tier box
     *  itself when the source's own dimensions are unknown, since the ffmpeg
     *  scale filter always needs concrete numbers.
     *
     *  This is the box to *scale* into, not the box to end up at:
     *  [videoScaleFilter] does its own alignment for the pad step that follows
     *  the scale, and [encodeTargetSize] does the same for Presentation (which
     *  has no separate pad step -- one box has to do both jobs there). Handing
     *  an already-aligned box to a scale step that then pads to that same
     *  alignment made the scale itself perform a small, needless upscale to
     *  reach it (confirmed against ffmpeg directly: scaling 1274x716 into an
     *  already-aligned 1280x720 box resamples every pixel, where scaling into
     *  the raw 1274x716 fitted box is a genuine passthrough and the last few
     *  pixels come from padding instead) -- harmless in the numbers, but a
     *  resample the content never needed. */
    private fun resolveOutputBox(item: MediaCollection.MediaNode.Item, resolution: Constants.Transcoder.Resolution): PixelSize =
        parseSourceSize(item)?.let { resolveOutputSize(it, tierBox(resolution)) } ?: tierBox(resolution)

    /**
     * The exact pixel dimensions **every** path must end up encoding [item] to
     * at [resolution] -- [resolveOutputBox]'s fitted box, aligned up the same
     * way [videoScaleFilter]'s pad does.
     *
     * One function rather than two derivations, because the hardware and safe
     * paths fell out of agreement the moment each was fixed independently: the
     * ffmpeg chain padded to alignment and Presentation didn't, so a 1920x804
     * source came out 1920x816 through one path and 1920x804 through the other.
     * [transcodeChunk] falls back from hardware to safe path *per chunk*, so a
     * single file could contain both, and [concatChunks] stream-copies them into
     * one MP4 -- a dimension change mid-stream that the concat demuxer cannot
     * reconcile.
     *
     * Null means the scan's resolution string was unparseable, which callers
     * read as "don't make a geometry decision for this item" -- unlike
     * [resolveOutputBox], which always returns something.
     */
    private fun encodeTargetSize(item: MediaCollection.MediaNode.Item, resolution: Constants.Transcoder.Resolution): PixelSize? =
        parseSourceSize(item)?.let {
            val fitted = resolveOutputSize(it, tierBox(resolution))
            PixelSize(alignUpForEncoder(fitted.width), alignUpForEncoder(fitted.height))
        }

    /**
     * Whether the audio track this transcode will actually map carries audio.
     *
     * Replaces `hasAudioTrack`, which spawned an ffprobe per chunk to ask
     * whether the file had *any* audio stream -- and on a `content://` item that
     * declined to mirror, that meant streaming the entire source through stdin
     * to answer a yes/no question, once per chunk. It also disagreed with the
     * hardware path, which asks about the *mapped* track: for a file whose track
     * 0 is stereo and whose requested track 2 doesn't exist, the hardware path
     * correctly emitted no audio while the safe path saw "yes, there is audio",
     * ran an audio leg that mapped nothing, and failed the chunk with a
     * confusing error.
     *
     * Falls back to the ffprobe check only when the scan recorded nothing at all
     * -- if the metadata is absent rather than merely saying zero, guessing
     * "no audio" would ship a silent file as a success.
     */
    private suspend fun itemHasAudio(item: MediaCollection.MediaNode.Item, audioTrackIndex: Int): Boolean {
        val scanned = resolveTrackChannelCount(item, audioTrackIndex)
        if (scanned != null) return scanned > 0
        if (item.audioTracks.isNotEmpty()) return audioTrackIndex < item.audioTracks.size
        Log.d(TAG, "itemHasAudio: '${item.name}' has no scanned audio metadata -- falling back to an ffprobe check")
        return probedHasAudioTrack(item)
    }

    /**
     * Cheap, millisecond-scale pre-flight for whether this device's hardware
     * encoder even *advertises* support for [codec] at [resolution], using
     * Media3's declarative [EncoderUtil] (`MediaCodecInfo` capability
     * queries) instead of spending a real probe encode to find out.
     *
     * A `false` return is a definitive "not supported" -- callers should
     * treat it as a known failure and skip straight to that outcome rather
     * than running [runHwProbe] or a real [Transformer] export and waiting
     * out its timeout to discover the same thing empirically. A `true`
     * return is not a guarantee of anything beyond "the device claims to
     * support this" -- it does not replace the empirical verification
     * ([runHwProbe]/[CapabilitySnapshot]) already in place, since some
     * devices (Tensor chips among them, per this file's other diagnostics)
     * advertise a configuration and then hang or fail on it anyway. Any
     * failure of the query itself (some OEM's `MediaCodecList` throwing on
     * an unexpected input) is treated the same way -- as inconclusive, not
     * as evidence the device can't encode -- so this shortcut can only ever
     * skip a doomed attempt, never block one the old code path would have
     * allowed.
     */
    @OptIn(UnstableApi::class)
    private fun encoderAdvertisesSupport(
        codec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution
    ): Boolean {
        val mimeType = when (codec) {
            Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264
            Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265
            else -> return true // no MIME mapping to check -- don't block a codec this pre-flight can't evaluate
        }
        val box = tierBox(resolution)
        val bitrateBps = resolution.targetBitrateKbps * 1000
        val encoders = try {
            EncoderUtil.getSupportedEncoders(mimeType)
        } catch (e: Exception) {
            Log.w(TAG, "encoderAdvertisesSupport: EncoderUtil query failed for $mimeType: ${e.message}")
            return true
        }
        if (encoders.isEmpty()) {
            Log.w(TAG, "encoderAdvertisesSupport: device advertises no encoder at all for $mimeType")
            return false
        }
        val supported = try {
            encoders.any { encoderInfo ->
                val widtHeight = EncoderUtil.getSupportedResolutionRanges(encoderInfo, mimeType)
                widtHeight.first.contains(box.width) && widtHeight.second.contains(box.height) &&
                    EncoderUtil.getSupportedBitrateRange(encoderInfo, mimeType).contains(bitrateBps)
            }
        } catch (e: Exception) {
            Log.w(TAG, "encoderAdvertisesSupport: EncoderUtil range query failed for $mimeType: ${e.message}")
            return true
        }
        if (!supported) {
            Log.w(TAG, "encoderAdvertisesSupport: no encoder for $mimeType advertises ${box.width}x${box.height} @ ${bitrateBps}bps across ${encoders.size} candidate(s)")
        }
        return supported
    }

    /**
     * @param includeAudio null = auto-detect (probe the source; audio is
     *   included only if a channel count is actually found). Pass true/false
     *   to force a specific behavior regardless of what's really in the
     *   source -- e.g. a synthetic "_noaudio" fixture that's deliberately
     *   meant to exercise the no-audio hardware path on its own terms.
     * @param knownAudioChannelCount skip a redundant ffprobe pass when the
     *   caller already has a trustworthy channel count (e.g. from library
     *   metadata). Leave null to have this function probe the source itself.
     * @param verbosity ffmpeg's `-v` level for the probe encode. Defaults to
     *   `error` because this probe's entire purpose is to measure a clean
     *   real-time factor, and debug-level logging is not free: it is thousands
     *   of formatted writes competing with the encode, which on a thermally
     *   throttled device skews the very number the pipeline gate reads. Pass
     *   `"debug"` explicitly where the stderr *is* the artefact -- currently the
     *   surface-input investigation's own call site. Once that investigation
     *   closes, the end state is to escalate to `debug` only on a second probe
     *   run after a first one fails.
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
        timeoutMs: Long = HW_PROBE_TIMEOUT_MS,
        verbosity: String = "error"
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
            listOf("-map", "0:v:0", "-map", "0:a:0?", "-c:a", Constants.Transcoder.AudioCodec.AAC.encoderId) + channelArgs(layout)
        } else listOf("-an", "-sn")
        
        // The tier box, not the item's own size: this probe answers "can the
        // encoder be configured and fed at this resolution", so the fixture is
        // deliberately scaled up to the tier under test. Output dimensions come
        // out the same as the old scale=W:H did (1920x1088 for HD_1080), so the
        // PASS/FAIL and RT-factor readings stay comparable with earlier runs.
        val probeBox = tierBox(resolution)

        val args = mutableListOf(
            ffmpegPath, "-y", "-hide_banner", "-v", verbosity,
            "-init_hw_device", "mediacodec=mc", "-hwaccel", "mediacodec",
            "-ndk_codec", "1", // applies to the decoder for -i, below -- see the matching flag after -c:v
            "-i", inputPath
        )
        args.addAll(streamArgs)
        val (limitModeArgs, actualLimitS) = limitArgs(limitMode)
        args.addAll(limitModeArgs)
        args.addAll(listOf(
            "-vf", videoScaleFilter(probeBox),
            "-c:v", targetCodec.encoderId,
            "-b:v", "${resolution.targetBitrateKbps}k",
            "-bitrate_mode", "vbr",
            "-ndk_codec", "1" // applies to the encoder for -c:v, above -- not a duplicate of the flag before -i
        ))
        args.addAll(encoderTuningArgs(targetCodec, resolution))
        // Note for anyone correlating against older GATE 2 logs: with `-r 24`
        // gone from encoderTuningArgs, this probe no longer resamples the
        // fixture to 24fps, so a `-t 2` run now encodes however many frames the
        // fixture's own rate implies (and gets a ~2s GOP instead of a 1s one).
        // Output dimensions are unchanged -- still 1920x1088 for HD_1080, see
        // videoScaleFilter -- so the encoder-configuration behaviour this probe
        // exists to test is the same; only the frame count moves. Pin a rate
        // here explicitly if a run has to be compared frame-for-frame with one
        // recorded before this change.
        args.addAll(listOf("-f", "null", "-"))

        val startTime = System.currentTimeMillis()
        val result = runProcessCapture(args, timeoutMs = timeoutMs, stdinSource = stdinSource)
        val totalTime = System.currentTimeMillis() - startTime

        val outcome = when {
            result.timedOut -> HwProbeOutcome.HANG
            result.exitCode == 0 -> HwProbeOutcome.PASS
            else -> HwProbeOutcome.FAIL
        }

        if (outcome == HwProbeOutcome.PASS) {
            val realTimeFactor = totalTime / 1000.0 / actualLimitS
            Log.d(TAG, "GATE 2 METRICS: '$label' > RT FACTOR: ${String.format(Locale.US, "%.2f", realTimeFactor)}x")
        }
        return outcome
    }

    /**
     * @param frameRate the source's real frame rate, where it's known. Null
     *   means unknown -- no frame-rate cap is emitted and the GOP is sized
     *   against [ASSUMED_FRAME_RATE_FPS].
     */
    private fun encoderTuningArgs(
        codec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        frameRate: Double? = null
    ): List<String> {
        val profile = when (codec) {
            Constants.Transcoder.VideoCodec.H264 -> "high"
            Constants.Transcoder.VideoCodec.HEVC -> "main"
            else -> "main"
        }
        // Cap, never resample. This used to be an unconditional `-r 24`, which
        // resampled every output to 24fps: 30fps content got a 4:5 drop pattern,
        // 60fps lost 60% of its frames, 25fps PAL got irregular duplication.
        // Content at or below the ceiling now passes through at its own rate.
        val frameRateArgs =
            if (frameRate != null && frameRate > MAX_OUTPUT_FRAME_RATE_FPS) listOf("-r", MAX_OUTPUT_FRAME_RATE_FPS.toString())
            else emptyList()
        // And `-g 24` was a 1-second GOP at that forced 24fps, which it stopped
        // being the moment the rate wasn't 24. Size it in seconds instead.
        val gopFrames = ((frameRate ?: ASSUMED_FRAME_RATE_FPS) * GOP_TARGET_SECONDS).roundToInt().coerceIn(24, 240)
        // -bf 0 is deliberate, not an oversight, and this comment is here so it
        // stops being re-litigated: output goes to Android MediaCodec hardware
        // encoders (-ndk_codec 1), where B-frame support is inconsistent -- some
        // ignore the request, some fail encoder configuration outright, and this
        // file already has a history of encoder-init failures. B-frames also
        // introduce reordering and a non-trivial DTS/PTS relationship exactly at
        // the fragmented-MP4 seams that concatChunks reassembles and
        // verifyMuxedAvSync checks to 200ms. Revisiting it needs a device matrix,
        // not a flag flip.
        //
        // No "-pix_fmt", "yuv420p" here (D13): both call sites immediately
        // filtered it back out, so it was dead weight that could only go wrong --
        // the filter matches flag and value independently, so changing the pixel
        // format here would have stripped the flag while leaving its value behind
        // as a stray positional argument, which ffmpeg reads as an output filename.
        return listOf("-profile:v", profile, "-level", encoderLevelArg(codec, resolution)) +
            frameRateArgs +
            listOf("-g", gopFrames.toString(), "-bf", "0")
    }

    /**
     * Unified orchestration function to transcode a media segment.
     *
     * If [responder] is provided, it streams the output over HTTP.
     * If [responder] is null, it saves the output to the input file's directory.
     *
     * @param startTimeMs The time offset to start from.
     * @param targetResolution Resolution target. Defaults to matching source height.
     * @param subtitleTrackIndex index of subtitle track to include.
     * @param audioTrackIndex index of audio track to transcode.
     */
    suspend fun transcodeMediaFile(
        item: MediaCollection.MediaNode.Item,
        startTimeMs: Long,
        targetVideoCodec: Constants.Transcoder.VideoCodec,
        targetResolution: Constants.Transcoder.Resolution? = null,
        subtitleTrackIndex: Int? = null,
        audioTrackIndex: Int = 0,
        targetAudioCodec: Constants.Transcoder.AudioCodec,
        responder: (suspend (status: Boolean?, contentType: String, writer: suspend (OutputStream) -> Unit) -> Unit)? = null
    ): Boolean {
        val resolution = targetResolution ?: run {
            val height = parseSourceSize(item)?.height ?: 1080
            Constants.Transcoder.Resolution.entries.sortedBy { it.maxHeight }
                .find { it.maxHeight >= height } ?: Constants.Transcoder.Resolution.HD_1080
        }
        val videoBitrateKbps = resolution.targetBitrateKbps
        val audioLayout = resolveItemChannelLayout(item, audioTrackIndex)
        val audioBitrateBps = resolveAudioBitrate(audioLayout)
        // Downmix warning lives here, not inside resolveItemChannelLayout: this
        // is the one call to it per transcode request. transcodeViaHardwareFfmpeg
        // and transcodeAudioTrack call it again per chunk with the same result,
        // and logging there used to turn one warning into one per chunk (D23r.3).
        resolveTrackChannelCount(item, audioTrackIndex)?.let { channelCount ->
            if (channelCount !in setOf(1, 2, 6)) {
                Log.w(TAG, "Downmixing '${item.name}' audio track $audioTrackIndex from ${channelCount}ch to stereo (unsupported layout)")
            }
        }

        val isLegacyVideo = item.videoCodec == Constants.Transcoder.VideoCodec.MPEG4 ||
                item.videoCodec == Constants.Transcoder.VideoCodec.WMV3 ||
                item.name.lowercase().endsWith(".avi")
        val is4k = resolution == Constants.Transcoder.Resolution.UHD_4K
        val hasDtsAudio = item.audioCodec == Constants.Transcoder.AudioCodec.DCA ||
                item.audioTracks.any { it.codec == Constants.Transcoder.AudioCodec.DCA }

        // The file-save path can afford to wait for the one-time hardware probe.
        // The streaming path can't: a DLNA renderer typically abandons a
        // connection producing no bytes within 5-15s, so an unbounded wait on a
        // 10s probe -- stacked on the keyframe probe still ahead of it -- would
        // manufacture exactly the timeout this is meant to avoid. Either way this
        // is a real wait-for-the-answer rather than the old read-whatever's-there-
        // right-now, which let any request arriving inside the probe's window
        // take the safe path by race rather than by policy.
        val hwReady = if (responder != null) {
            withTimeoutOrNull(HW_PROBE_STREAM_WAIT_MS) { hwPipelineReady.await() } ?: false
        } else {
            useHardwareFfmpeg()
        }

        val strategy = when {
            hwReady -> "hw_ffmpeg"
            is4k -> "hybrid" //full_media3" //transcodeViaFullMedia3Path
            isLegacyVideo -> "hybrid"
            hasDtsAudio -> "hybrid"
            else -> "hybrid"
        }

        Log.d(TAG, "Transcode Start: '${item.name}' (startTime=${startTimeMs}ms)")
        Log.d(TAG, "  > Strategy:   $strategy (mode=${if (responder != null) "stream" else "file"})")
        Log.d(TAG, "  > Source V:   ${item.videoCodec.name} (${item.resolution})")
        Log.d(TAG, "  > Source A:   ${item.audioCodec.name} (track $audioTrackIndex, ${resolveTrackChannelCount(item, audioTrackIndex)}ch; item default ${item.channelCount}ch)")
        Log.d(TAG, "  > Target V:   ${targetVideoCodec.name} (${encodeTargetSize(item, resolution) ?: resolveOutputBox(item, resolution)} within ${resolution.name} ${tierBox(resolution)} @ ${videoBitrateKbps}kbps)")
        Log.d(TAG, "  > Target A:   ${targetAudioCodec.name} (${audioLayout.channelCount}ch @ ${audioBitrateBps / 1000}kbps)")

        if (responder != null) {
            val status = if (startTimeMs > 0) true else null
            if (subtitleTrackIndex != null) {
                Log.w(TAG, "transcodeMediaFile: subtitleTrackIndex=$subtitleTrackIndex requested for streaming but not yet supported -- ignoring")
            }

            // One id per stream session. A DLNA renderer routinely opens two or
            // three concurrent connections against one item (probe, playback,
            // seek); the old fixed "live_chunk_<id>.mp4" meant two sessions wrote
            // the same path while two reader loops read from it.
            val sessionId = UUID.randomUUID().toString().take(8)
            // Bounded and mirror-free, unlike file-save mode below: everything
            // here happens before the first byte reaches the renderer, and a
            // renderer that has waited 15s for a response is already gone. See
            // KEYFRAME_PROBE_STREAM_BUDGET_MS for what that trades away.
            val chunkBoundaries = computeChunkBoundaries(
                item, DEFAULT_CHUNK_DURATION_MS, startTimeMs, null,
                keyframeBudgetMs = KEYFRAME_PROBE_STREAM_BUDGET_MS,
                allowMirror = false
            )
            var streamedChunks = 0
            // Every chunk after the first is an independently-invoked process whose
            // own output resets to a fresh internal timeline near zero -- read the
            // real per-track timescales once, from chunk 0's still-present moov,
            // while there's still a moov to read (chunk 1 overwrites this same
            // file). Empty until then; patchFragmentTimestamps no-ops on empty.
            // See patchFragmentTimestamps' doc comment for why this exists.
            var chunkTrackTimescales: Map<Int, Int> = emptyMap()

            responder.invoke(status, "video/mp4") { out ->
                val chunkFile = File(workDir, "live_${item.id}_$sessionId.mp4")
                try {
                    for ((index, boundary) in chunkBoundaries.withIndex()) {
                        // A disconnected client should stop the encoder, not let it
                        // run to completion into a dead socket -- real wasted encode
                        // time on a server where users seek and reconnect often.
                        currentCoroutineContext().ensureActive()
                        val (chunkStartMs, chunkDurMs, startsAtVerifiedKeyframe) = boundary
                        val chunkTimeoutMs = Budget.forChunk(chunkDurMs, strategy, resolution)
                        val outcome = transcodeChunk(
                            item, chunkStartMs, chunkDurMs, targetVideoCodec, resolution, videoBitrateKbps, targetAudioCodec, audioBitrateBps, audioTrackIndex, chunkFile, chunkTimeoutMs, strategy, fragmented = true,
                            durationToleranceMs = if (index == chunkBoundaries.lastIndex) FINAL_CHUNK_TOLERANCE_MS else CHUNK_DURATION_TOLERANCE_MS,
                            sessionId = sessionId, startsAtVerifiedKeyframe = startsAtVerifiedKeyframe
                        )
                        if (outcome != ChunkOutcome.Success) {
                            Log.e(TAG, "transcodeMediaFile: chunk $index $outcome for '${item.name}' [${chunkStartMs}ms +${chunkDurMs}ms) -- ending stream early")
                            break
                        }
                        streamedChunks++
                        if (index == 0) {
                            chunkTrackTimescales = readTrackTimescales(chunkFile)
                        } else if (chunkTrackTimescales.isNotEmpty()) {
                            // Relative to *this session's* first chunk, not to chunkStartMs
                            // itself -- see patchFragmentTimestamps' doc comment for why the
                            // distinction matters on every request that starts mid-file.
                            patchFragmentTimestamps(chunkFile, chunkTrackTimescales, chunkStartMs - chunkBoundaries.first().startMs)
                        }
                        val skipOffset = if (index == 0) 0L else findFirstMoofOffset(chunkFile)
                        RandomAccessFile(chunkFile, "r").use { raf ->
                            raf.seek(skipOffset)
                            val buf = ByteArray(65536)
                            while (true) {
                                val n = raf.read(buf)
                                if (n == -1) break
                                out.write(buf, 0, n)
                            }
                        }
                        out.flush()
                        Log.d(TAG, "transcodeMediaFile: streamed chunk $index/${chunkBoundaries.size} for '${item.name}' [${chunkStartMs}ms +${chunkDurMs}ms)")
                    }
                } finally {
                    chunkFile.delete()
                }
            }
            // Used to be an unconditional `return true`: if chunk 0 failed, the
            // loop above broke immediately, the responder finished having
            // written nothing, and this reported success anyway.
            return streamedChunks > 0 && streamedChunks == chunkBoundaries.size
        } else {
            // File mode: Save to source directory.
            val outputFile = File(durableWorkDir, "transcoding_${item.id}.mp4")
            if (outputFile.exists()) outputFile.delete()

            // When saving to file, we generally transcode the whole file from start to finish
            // unless a specific seek was intended. For the unified test, we use startTimeMs=0.

            val success = transcodeInChunks(item, targetVideoCodec, resolution, videoBitrateKbps, targetAudioCodec, audioBitrateBps, audioTrackIndex, outputFile, startTimeMs, null, DEFAULT_CHUNK_DURATION_MS, strategy)

            var finalSuccess = success
            if (success) {
                val published = publishToSourceDirectory(item, outputFile)
                if (published != null) {
                    Log.d(TAG, "Transcode finished. File preserved at: $published")
                } else {
                    Log.e(TAG, "Transcode succeeded but publishing failed.")
                    finalSuccess = false
                }
            }

            if (outputFile.exists()) outputFile.delete()
            return finalSuccess
        }
    }

    /**
     * Transcodes a single [chunkStartMs, chunkStartMs + chunkDurationMs)
     * window of [item] into [outputFile], and verifies the result actually
     * covers that duration before accepting it.
     *
     * Prefers hw_ffmpeg regardless of the whole-file useHardwareFfmpegPipeline
     * probe: that probe answers "is ffmpeg's hwaccel path fast enough for the
     * WHOLE file", a different question from "is it reliable for a 30-60s
     * window" -- and a window this short stays well clear of the ~5-8 minute
     * point where Transformer's video-only leg has been shown (this
     * conversation's memory investigation, corroborated by androidx/media#758)
     * to silently truncate. hw_ffmpeg also handles video+audio in one process,
     * so there's no independent-legs duration mismatch to begin with. Safe
     * Path is only a fallback for whatever hw_ffmpeg genuinely can't handle
     * (e.g. an unsupported codec), and still gets the same duration check.
     */
    private suspend fun transcodeChunk(
        item: MediaCollection.MediaNode.Item,
        chunkStartMs: Long,
        chunkDurationMs: Long,
        targetVideoCodec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        videoBitrateKbps: Int,
        targetAudioCodec: Constants.Transcoder.AudioCodec,
        audioBitrateBps: Int,
        audioTrackIndex: Int,
        outputFile: File,
        timeoutMs: Long,
        strategy: String,
        fragmented: Boolean = false,
        precomputedAudio: Deferred<File?>? = null,
        precomputedAudioBaseMs: Long = 0,
        durationToleranceMs: Long = CHUNK_DURATION_TOLERANCE_MS,
        sessionId: String,
        // From computeChunkBoundaries: true only when chunkStartMs is a
        // *confirmed* real source keyframe, not merely the nominal target
        // that was asked for. Only the safe path uses this (see
        // transcodeViaSafePath's setStartsAtKeyFrame) -- hw_ffmpeg instead
        // verifies its own landing empirically after every seek,
        // independent of whether the target happens to be a verified
        // keyframe, so it needs no flag here.
        startsAtVerifiedKeyframe: Boolean = false
    ): ChunkOutcome {
        outputFile.delete()
        var outcome = if (strategy == "hw_ffmpeg") {
            transcodeViaHardwareFfmpeg(item, targetVideoCodec, resolution, videoBitrateKbps, targetAudioCodec, audioBitrateBps, audioTrackIndex, outputFile, chunkStartMs, chunkDurationMs, timeoutMs = timeoutMs, fragmented = fragmented)
        } else {
            ChunkOutcome.Failed
        }
        if (outcome != ChunkOutcome.Success) {
            if (strategy == "hw_ffmpeg") Log.w(TAG, "transcodeChunk: hw_ffmpeg $outcome for '${item.name}' [${chunkStartMs}ms +${chunkDurationMs}ms), falling back to Safe Path")
            outputFile.delete()
            // The await lives here, not in the caller's loop, and that placement
            // is the whole optimisation: the safe path is the only consumer of a
            // pre-encoded audio track, so on hw_ffmpeg this line is never
            // reached and the LAZY encode never starts.
            //
            // A failed pre-encode must degrade to per-chunk audio, exactly as a
            // null precomputedAudioFile always has -- not fail the chunk. But a
            // bare runCatching here would also swallow a CancellationException
            // raised because *our* caller was cancelled, and the streaming path
            // depends on that propagating to stop encoding into a socket the
            // client already closed. ensureActive tells the two apart.
            val precomputedAudioFile = try {
                precomputedAudio?.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "transcodeChunk: full-audio pre-encode for '${item.name}' was cancelled -- falling back to per-chunk audio")
                null
            } catch (e: Exception) {
                Log.w(TAG, "transcodeChunk: full-audio pre-encode for '${item.name}' failed (${e.message}) -- falling back to per-chunk audio")
                null
            }
            outcome = transcodeViaSafePath(
                item, targetVideoCodec, resolution, targetAudioCodec, audioBitrateBps, audioTrackIndex, outputFile, chunkDurationMs, chunkStartMs, timeoutMs = timeoutMs,
                fragmented = fragmented, precomputedAudioFile = precomputedAudioFile, precomputedAudioBaseMs = precomputedAudioBaseMs,
                durationToleranceMs = durationToleranceMs, sessionId = sessionId, startsAtVerifiedKeyframe = startsAtVerifiedKeyframe
            )
        }
        if (outcome != ChunkOutcome.Success) return outcome
        return if (verifyChunkDuration(outputFile, chunkDurationMs, item.name, durationToleranceMs)) ChunkOutcome.Success else ChunkOutcome.Failed
    }

    /**
     * Entry point for handling transcoded HTTP requests from HttpServer.
     * Implements the scaffolding for serving transcoded streams on-the-fly.
     *
     * @param item The source media item to transcode.
     * @param startByte The byte offset requested by the client (for Range headers).
     * @param responder Callback to send status, headers and obtain an OutputStream.
     */

    /**
     * Encodes one continuous audio track covering a whole transcode range, so
     * each chunk's audio becomes a cheap stream-copy slice (see
     * [sliceAudioSegment]) instead of an independent decode+encode of the raw
     * source: one encoder priming/delay for the entire file instead of one per
     * chunk, and no chance of a per-chunk audio leg drifting from its video
     * leg's timing.
     *
     * Returns null rather than throwing if the encode fails or comes up short
     * -- callers fall back to per-chunk audio, which is slower and seam-ier but
     * correct. Losing the whole transcode over a failed optimization would not
     * be.
     */
    private suspend fun preEncodeFullAudio(
        item: MediaCollection.MediaNode.Item,
        candidate: File,
        totalDurationMs: Long,
        rangeStartMs: Long,
        targetAudioCodec: Constants.Transcoder.AudioCodec,
        audioBitrateBps: Int,
        audioTrackIndex: Int
    ): File? {
        candidate.delete()
        Log.d(TAG, "Chunked transcode: '${item.name}' -- pre-encoding full audio range once (${totalDurationMs}ms)")
        val fullAudioOk = transcodeAudioTrack(
            item, candidate, durationS = totalDurationMs / 1000.0, startTimeMs = rangeStartMs, encoder = targetAudioCodec.encoderId, audioBitrateBps = audioBitrateBps, audioTrackIndex = audioTrackIndex,
            timeoutMs = Budget.forAudio(totalDurationMs)
        )
        // FINAL_CHUNK_TOLERANCE_MS, not the strict one: this range ends at
        // EOF by construction, so it's the final-chunk case writ large. A
        // false rejection here isn't fatal -- it falls back to per-chunk
        // audio -- but that costs an encoder restart at every seam for the
        // whole file, which is exactly what this pre-encode exists to avoid.
        if (fullAudioOk && verifyChunkDuration(candidate, totalDurationMs, item.name, FINAL_CHUNK_TOLERANCE_MS)) {
            return candidate
        }
        Log.w(TAG, "Chunked transcode: '${item.name}' full-audio pre-encode failed or was truncated -- falling back to per-chunk audio encoding")
        candidate.delete()
        return null
    }

    /**
     * Full-file chunked mode: transcodes [rangeStartMs, rangeStartMs +
     * rangeDurationMs) in fixed [chunkDurationMs] windows and stitches the
     * verified chunk outputs into one valid MP4 via a single ffmpeg
     * concat-demuxer stream-copy pass (no re-encode). Each chunk is retried
     * up to [maxRetriesPerChunk] times before the whole transcode is given
     * up on -- a stalled/truncated chunk costs a retry of ~30-60s of work,
     * not the whole file.
     */
    private suspend fun transcodeInChunks(
        item: MediaCollection.MediaNode.Item,
        targetVideoCodec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        videoBitrateKbps: Int,
        targetAudioCodec: Constants.Transcoder.AudioCodec,
        audioBitrateBps: Int,
        audioTrackIndex: Int,
        finalOutputFile: File,
        rangeStartMs: Long,
        rangeDurationMs: Long?,
        chunkDurationMs: Long,
        strategy: String,
        maxRetriesPerChunk: Int = 1
    ): Boolean {
        // Every temp file this call creates carries this id. Without it, two
        // concurrent file-mode requests for the same item -- or a file-mode
        // request overlapping a streaming one -- wrote the identical
        // chunk_<id>_NNNN.mp4 / chunked_full_audio_<id>.m4a paths, and one
        // request's ffmpeg process clobbered the other's input mid-read.
        val sessionId = UUID.randomUUID().toString().take(8)
        val totalDurationMs = rangeDurationMs ?: (resolveSourceDurationMs(item) - rangeStartMs)
        if (totalDurationMs <= 0) {
            Log.e(TAG, "Chunked transcode: nothing to do for '${item.name}' (rangeStartMs=$rangeStartMs, item.durationMs=${item.durationMs})")
            return false
        }
        val chunkBoundaries = computeChunkBoundaries(item, chunkDurationMs, rangeStartMs, rangeDurationMs)
        val numChunks = chunkBoundaries.size
        Log.d(TAG, "Chunked transcode: '${item.name}' -- $numChunks keyframe-aligned chunk(s) over ${totalDurationMs}ms total (session=$sessionId)")

        // Detect and abort, rather than silently starting a job that cannot
        // finish in any reasonable time. Every ffmpeg invocation over a pipe
        // re-reads the source from byte 0 -- the pipe is forward-only, so `-ss`
        // on a late chunk decodes and discards everything before its own start
        // offset -- which makes the cost quadratic in chunk count. Resolving the
        // input here rather than inside the first chunk also means the mirror,
        // if one is affordable, is paid for once up front instead of racing the
        // chunk loop.
        //
        // The honest fix for the underlying problem is more free space, so the
        // message says so. It has nowhere better to go than the log today: this
        // controller's repo surface is the seek bar and nothing else.
        val resolvedInput = resolveFfmpegInputPath(item)
        if (resolvedInput.isPiped && numChunks > PIPE_MAX_CHUNKS_BEFORE_ABORT) {
            Log.e(TAG, "Chunked transcode: refusing '${item.name}' -- not enough free space to transcode this file. Its source could not be mirrored locally (needs its own size plus ${MIN_FREE_SPACE_AFTER_MIRROR_MB}MB free in ${workDir.absolutePath}, ${diagnosticSnapshot()}), so all $numChunks chunk(s) would stream it from the start over pipe:0 -- roughly $numChunks full reads of the source. Free up space and retry.")
            releaseSourceMirror(item)
            return false
        }

        // The pre-encode used to run to completion right here, before the first
        // video chunk started, guarded only by "does this item have audio" -- no
        // strategy check at all. transcodeViaHardwareFfmpeg muxes video and
        // audio in one process and never touches precomputedAudioFile, so on
        // hw_ffmpeg (the fast path, the default on Qualcomm) the whole-file
        // audio encode ran for up to 300s and its output was deleted unused.
        //
        // A Deferred fixes both halves. On hw_ffmpeg it starts LAZY -- never
        // started at all unless a chunk genuinely falls back to the safe path
        // mid-file, and started exactly then, which is when it is wanted. On the
        // safe path it starts eagerly so the audio encode overlaps chunk 0's
        // video export rather than serialising ahead of it; the two legs use
        // different codecs and different hardware blocks. A plain
        // `if (strategy != hw_ffmpeg)` guard would have got the first half and
        // lost the fallback path that genuinely uses it; plain LAZY everywhere
        // would have got the second half and parked chunk 0 behind a full-file
        // audio encode.
        val audioCandidate = File(durableWorkDir, "chunked_full_audio_${item.id}_$sessionId.m4a")
        val audioDeferred: Deferred<File?>? =
            if (itemHasAudio(item, audioTrackIndex))
                scope.async(
                    Dispatchers.IO,
                    start = if (strategy == "hw_ffmpeg") CoroutineStart.LAZY else CoroutineStart.DEFAULT
                ) {
                    preEncodeFullAudio(item, audioCandidate, totalDurationMs, rangeStartMs, targetAudioCodec, audioBitrateBps, audioTrackIndex)
                }
            else null

        val chunkFiles = mutableListOf<File>()
        try {
            for (i in chunkBoundaries.indices) {
                val (chunkStartMs, chunkDurMs, startsAtVerifiedKeyframe) = chunkBoundaries[i]
                val chunkFile = File(durableWorkDir, "chunk_${item.id}_${sessionId}_${i.toString().padStart(4, '0')}.mp4")
                // The last chunk's requested end comes from a duration that may be
                // a little long; coming up short there is expected, not a
                // truncation. Everything before it stays strict.
                val toleranceMs = if (i == chunkBoundaries.lastIndex) FINAL_CHUNK_TOLERANCE_MS else CHUNK_DURATION_TOLERANCE_MS

                var outcome: ChunkOutcome = ChunkOutcome.Failed
                var attempt = 0
                while (outcome != ChunkOutcome.Success && attempt <= maxRetriesPerChunk) {
                    if (attempt > 0) {
                        Log.w(TAG, "Chunked transcode: retrying chunk $i/$numChunks for '${item.name}' (attempt ${attempt + 1}, previous=$outcome)")
                        delay(RETRY_DELAY_MS)
                    }
                    // A timeout retried against the identical budget fails the
                    // same way, so it gets double the budget on its one retry.
                    // An outright failure isn't a budget problem and keeps the
                    // normal one.
                    val budgetMultiplier = if (outcome == ChunkOutcome.TimedOut) 2 else 1
                    val chunkTimeoutMs = Budget.forChunk(chunkDurMs, strategy, resolution) * budgetMultiplier
                    outcome = transcodeChunk(
                        item, chunkStartMs, chunkDurMs, targetVideoCodec, resolution, videoBitrateKbps, targetAudioCodec, audioBitrateBps, audioTrackIndex, chunkFile, chunkTimeoutMs, strategy,
                        precomputedAudio = audioDeferred, precomputedAudioBaseMs = rangeStartMs,
                        durationToleranceMs = toleranceMs,
                        sessionId = sessionId, startsAtVerifiedKeyframe = startsAtVerifiedKeyframe
                    )
                    attempt++
                    // Retrying into an exhausted codec pool cannot succeed -- every
                    // attempt fails the same way until the process restarts -- so
                    // this stops the transcode rather than spending the remaining
                    // retry proving it. Logged distinctly so it greps apart from an
                    // ordinary chunk failure.
                    if (outcome != ChunkOutcome.Success && consecutiveCodecAllocationFailures.get() >= CODEC_EXHAUSTION_FAILURE_LIMIT) {
                        Log.e(TAG, "Chunked transcode: codec pool exhausted -- aborting '${item.name}' rather than retrying into it (${consecutiveCodecAllocationFailures.get()} consecutive allocation failure(s))")
                        return false
                    }
                }

                Log.d(TAG, "Chunked transcode: '${item.name}' chunk $i/$numChunks [${chunkStartMs}ms +${chunkDurMs}ms] $outcome (size=${chunkFile.length()})")
                if (outcome != ChunkOutcome.Success) {
                    Log.e(TAG, "Chunked transcode: giving up on '${item.name}' after chunk $i failed ${maxRetriesPerChunk + 1} times ($outcome)")
                    return false
                }
                chunkFiles.add(chunkFile)
            }

            return concatChunks(chunkFiles, finalOutputFile, item.name)
        } finally {
            chunkFiles.forEach { it.delete() }
            // cancel() is a no-op on a Deferred that already completed, and on a
            // LAZY one that was never started it just retires it -- so this
            // covers "hw_ffmpeg never needed it", "it finished normally" and
            // "we're bailing out with it still encoding" in one line. The file
            // is deleted by path rather than through the Deferred's result,
            // since a pre-encode that failed verification has already been
            // cleaned up and one still in flight has no result to ask for.
            audioDeferred?.cancel()
            audioCandidate.delete()
            releaseSourceMirror(item)
        }
    }

    /** Stream-copy remux via ffmpeg's concat demuxer -- no re-encode, just
     *  folds the N chunk files' sample data into one coherent moov/sample
     *  table, written directly to [outputFile]. */
    private suspend fun concatChunks(chunkFiles: List<File>, outputFile: File, itemName: String): Boolean {
        if (chunkFiles.isEmpty()) return false
        val listFile = File(durableWorkDir, "concat_${System.currentTimeMillis()}.txt")
        return try {
            listFile.writeText(chunkFiles.joinToString("\n") { "file '${it.absolutePath.replace("'", "'\\''")}'" })
            val args = listOf(
                ffmpegPath, "-y", "-hide_banner", "-v", "warning",
                "-f", "concat", "-safe", "0", "-i", listFile.absolutePath,
                "-c", "copy", "-movflags", "+faststart",
                "-f", "mp4", outputFile.absolutePath
            )
            // Stream-copy work, budgeted by the size actually being moved rather
            // than a flat 120s -- a 20GB concat and a 200MB one were held to the
            // same number before.
            val timeoutMs = Budget.forStreamCopy(chunkFiles.sumOf { it.length() })
            val result = runProcessCapture(args, timeoutMs = timeoutMs)
            val ok = result.succeededWriting(outputFile)
            Log.d(TAG, "Chunked transcode: concat for '$itemName' ${if (ok) "OK" else "FAILED"} (exitCode=${result.exitCode}, budget=${timeoutMs}ms, size=${outputFile.length()})")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "Chunked transcode: concat for '$itemName' threw: ${e.message}", e)
            false
        } finally {
            listFile.delete()
        }
    }

    /**
     * Lets the app's UI hand this controller a directory the user picked once,
     * via `ACTION_OPEN_DOCUMENT_TREE`, to publish every future transcode into --
     * see E5. Persists the choice (`SharedPreferences`) and takes a durable
     * read/write grant on it (`takePersistableUriPermission`) so it survives
     * process death and reboots, which is the point of asking once rather than
     * on every publish.
     *
     * This is only the controller's half of the architecture Report 2
     * recommends: launching the picker and calling this with its result is the
     * caller's. Until it's called, [publishToSourceDirectory]'s SAF branch
     * falls back to [resolveSafParentUri]'s single-authority guess, which is
     * all this controller could do on its own before.
     */
    fun setPersistedOutputDirectory(treeUri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "setPersistedOutputDirectory: takePersistableUriPermission failed for $treeUri: ${e.message}")
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(PREF_KEY_OUTPUT_DIR, treeUri.toString())
            .apply()
        Log.d(TAG, "setPersistedOutputDirectory: future publishes will prefer $treeUri")
    }

    /** The directory [setPersistedOutputDirectory] was last given, or null if
     *  it's never been called (or the stored value no longer parses as a Uri --
     *  treated the same as never set, rather than as an error, since the
     *  fallback path handles "no override" correctly either way). */
    private fun persistedOutputDirectory(): Uri? {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_KEY_OUTPUT_DIR, null) ?: return null
        return try { Uri.parse(raw) } catch (e: Exception) { null }
    }

    /**
     * Copies a completed transcode from [tempFile] into a real, user-visible
     * location: for a raw filesystem item (item.uri has a "file" scheme), the
     * same directory as its source; for a SAF/DocumentFile-backed item
     * (typically "content"), [persistedOutputDirectory] if the app has been
     * given one (E5), otherwise [resolveSafParentUri]'s guess at the source's
     * own parent.
     *
     * On API 29+, a raw filesystem item is published through MediaStore rather
     * than a direct file write -- scoped storage refuses that write with
     * EACCES for exactly the directories a source video normally lives in, so
     * the "file" branch needs its own real implementation there, not a
     * fallback (E4a). Below 29, the direct write still works and is followed
     * by a media-store scan so the new file doesn't stay invisible to the
     * gallery and other apps until the next full scan (E4b). Either branch
     * checks the destination has room before it starts copying, rather than
     * discovering that partway through a multi-gigabyte write (E4c).
     *
     * Returns the published location (absolute path or content Uri, as a
     * String, for logging) on success, or null if the destination couldn't be
     * resolved, didn't have room, or couldn't be written to. Callers should
     * treat null as saveToSourceDir having failed -- not as a reason to
     * silently keep (or lose track of) the temp copy.
     */
    @OptIn(UnstableApi::class)
    private fun publishToSourceDirectory(item: MediaCollection.MediaNode.Item, tempFile: File): String? {
        val baseName = item.name.substringBeforeLast('.', item.name)
        val targetName = "transcoded_$baseName.mp4"

        val sourceFilePath = item.uri.path
        if (item.uri.scheme == "file" && sourceFilePath != null) {
            val parentDir = File(sourceFilePath).parentFile
            if (parentDir == null || !parentDir.exists() || !parentDir.canWrite()) {
                Log.e(TAG, "Publish: source directory not writable (parentExists=${parentDir?.exists()}, parentWritable=${parentDir?.canWrite()}, path=${parentDir?.absolutePath})")
                return null
            }
            // (E4c) Fail cleanly before a half-written file sits beside the
            // user's source, rather than discovering the disk is full partway
            // through copying a multi-gigabyte transcode.
            if (parentDir.usableSpace < tempFile.length()) {
                Log.e(TAG, "Publish: not enough free space in ${parentDir.absolutePath} (usable=${parentDir.usableSpace}, needed=${tempFile.length()})")
                return null
            }
            // (E4a) Below API 29 a raw file write into external/shared storage
            // still works; at 29+ scoped storage refuses it with EACCES for
            // exactly the directories a source video normally lives in
            // (Movies/, DCIM/, etc.) -- MediaStore is the only route actually
            // granted there.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                return publishViaMediaStore(parentDir, targetName, tempFile)
            }
            val target = File(parentDir, targetName)
            return try {
                tempFile.copyTo(target, overwrite = true)
                Log.d(TAG, "Publish: copied to ${target.absolutePath} (size=${target.length()})")
                // (E4b) Without this the file is invisible to the gallery and
                // any other app browsing media until the next full system
                // scan -- which, for a user's own gallery app, could be never.
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("video/mp4"), null)
                target.absolutePath
            } catch (e: Exception) {
                Log.e(TAG, "Publish: file copy failed: ${e.message}", e)
                null
            }
        }

        // SAF / content:// item. Prefer a directory the user has explicitly
        // chosen for this (E5) over guessing at the source's own parent: the
        // guess below is only reliable for one SAF authority to begin with.
        //
        // DocumentFile.fromSingleUri()/.fromTreeUri() both leave
        // getParentFile() null when constructed from a bare document Uri --
        // parent traversal only works if you already walked down from the tree
        // root via listFiles(). Derive the parent document Uri directly instead,
        // via the standard ExternalStorageProvider document-ID convention
        // ("<root>:relative/path" -- strip the last path segment). This is only
        // reliable for that one authority (the one SAF folder pickers use for
        // local/SD storage); anything else falls through and fails cleanly
        // unless a persisted directory covers it.
        val parentUri = persistedOutputDirectory() ?: resolveSafParentUri(item.uri)
        if (parentUri == null) {
            Log.e(TAG, "Publish: could not resolve a parent directory for uri=${item.uri} (authority=${item.uri.authority}) -- leaving output in temp storage")
            return null
        }
        val parentDoc = DocumentFile.fromTreeUri(context, parentUri)
        if (parentDoc == null || !parentDoc.canWrite()) {
            Log.e(TAG, "Publish: parent DocumentFile at $parentUri is null or not writable")
            return null
        }
        // (E4c) SAF exposes no reliable free-space query on a tree Uri the way
        // a raw File does -- there's no getFreeSpace() equivalent in the
        // DocumentsContract API -- so this branch still can't pre-flight the
        // same way the file:// branch now does. It still fails on a genuine
        // write error below, just not as early.
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

    /**
     * (E4a) Publishes [tempFile] as a new MediaStore video row named
     * [targetName], placed (via `RELATIVE_PATH`) alongside [sourceDir] within
     * primary external storage. The only route scoped storage actually grants
     * on API 29+ for a location like a source video's own folder -- see
     * [publishToSourceDirectory].
     *
     * MediaStore has no upsert. Without the lookup-and-delete below, publishing
     * the same item a second time would accumulate a fresh row (and a
     * "(1)"-disambiguated filename from MediaStore itself) on every run,
     * instead of overwriting in place the way the raw-file and SAF branches
     * both already do. Deleting by the row's own `_ID`, found via a query,
     * rather than passing the same selection straight to `delete()`, because
     * the exact stored form of `RELATIVE_PATH` (trailing slash, normalization)
     * is not perfectly consistent across OEM MediaProvider implementations --
     * a query-then-delete-by-id is the more robust match.
     */
    private fun publishViaMediaStore(sourceDir: File, targetName: String, tempFile: File): String? {
        val externalRoot = Environment.getExternalStorageDirectory()?.absolutePath
        val sourcePath = sourceDir.absolutePath
        val relativePath = if (externalRoot != null && sourcePath.startsWith(externalRoot)) {
            sourcePath.removePrefix(externalRoot).trim('/').let { if (it.isEmpty()) "Movies/" else "$it/" }
        } else {
            Log.w(TAG, "publishViaMediaStore: '$sourcePath' isn't under primary external storage ($externalRoot) -- falling back to Movies/")
            "Movies/"
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        try {
            resolver.query(
                collection, arrayOf(MediaStore.Video.Media._ID),
                "${MediaStore.Video.Media.RELATIVE_PATH}=? AND ${MediaStore.Video.Media.DISPLAY_NAME}=?",
                arrayOf(relativePath, targetName), null
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                while (cursor.moveToNext()) {
                    resolver.delete(ContentUris.withAppendedId(collection, cursor.getLong(idCol)), null, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "publishViaMediaStore: pre-delete of existing row(s) for '$targetName' failed (continuing): ${e.message}")
        }

        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, targetName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val itemUri = try {
            resolver.insert(collection, values)
        } catch (e: Exception) {
            Log.e(TAG, "publishViaMediaStore: insert failed for '$targetName' in $relativePath: ${e.message}", e)
            null
        }
        if (itemUri == null) {
            Log.e(TAG, "publishViaMediaStore: insert returned null for '$targetName' in $relativePath")
            return null
        }
        return try {
            val opened = resolver.openOutputStream(itemUri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false
            if (!opened) {
                Log.e(TAG, "publishViaMediaStore: could not open output stream for $itemUri")
                resolver.delete(itemUri, null, null)
                return null
            }
            resolver.update(itemUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            Log.d(TAG, "publishViaMediaStore: copied to $itemUri (relativePath=$relativePath, size=${tempFile.length()})")
            itemUri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "publishViaMediaStore: write failed for $itemUri: ${e.message}", e)
            try { resolver.delete(itemUri, null, null) } catch (ignored: Exception) {}
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

    /** An ffmpeg/ffprobe input: the `-i` value, plus the stdin source that
     *  must accompany it whenever [path] is the literal `"pipe:0"`. Returned
     *  as one unit from [resolveFfmpegInputPath] so the two can't drift apart
     *  at a call site the way they could before -- every caller used to
     *  re-derive [stdinSource] from [path] with its own copy of
     *  `if (path == "pipe:0") item.uri else null`, four independent copies
     *  that all had to keep agreeing with each other (D18). */
    private data class ResolvedInput(val path: String, val stdinSource: Uri?) {
        val isPiped: Boolean get() = stdinSource != null
    }

    /**
     * Resolves an ffmpeg/ffprobe input path for [item]. For file:// items,
     * just the path -- no tricks needed, ffmpeg opens it directly.
     *
     * For everything else (content:// / SAF, the common case): tries to
     * mirror the source to a real, local, seekable file under [workDir]
     * once per item (a kernel-level bulk copy via FileChannel.transferTo),
     * cache it in [localSourceMirrors], and hand back its plain path --
     * but only when [MIN_FREE_SPACE_AFTER_MIRROR_MB] says there's headroom
     * to spare (checked against the source's real size via
     * ParcelFileDescriptor.statSize and [File.usableSpace] on [workDir],
     * right before committing to the copy). If that check says no -- source
     * size unknown, or not enough free space -- or the copy itself fails
     * partway, this returns the literal string "pipe:0" instead:
     * [runProcessCapture] streams [item.uri] into the child process's
     * stdin as it runs, via ContentResolver, no local copy involved.
     *
     * Why gate it at all rather than always mirroring like this used to:
     * for a large file, or several transcodes overlapping, an unconditional
     * mirror could push cache usage high enough to risk the app running out
     * of disk elsewhere -- worse than the seek performance it buys back.
     * The gate keeps the fast path for the common case (plenty of headroom)
     * without keeping that risk on a device that's already tight on space.
     *
     * The cost when the gate says no: pipe:0 is forward-only. Anything
     * relying on `-ss` before `-i` for fast input-seeking -- chunked
     * transcoding especially, see [transcodeViaHardwareFfmpeg] and
     * [transcodeAudioTrack] -- has to decode-and-discard from byte 0 up to
     * its start offset on every call. For a movie split into chunks, a late
     * chunk can mean minutes of wasted decode just to reach its own start
     * point. It also makes the keyframe probe in [probeKeyframeTimestampsMs]
     * more likely to come back empty (see that function's doc comment) --
     * both are the expected, logged trade-off of a low-space device, not a
     * bug.
     *
     * (Why not hand ffmpeg a raw fd or a /proc/self/fd/<n> path instead of
     * piping, and keep real seeking without a copy? Already tried: ffmpeg
     * and ffprobe run as separate child processes, and Android's
     * ProcessBuilder only wires up stdin/stdout/stderr into a spawned
     * child -- nothing else survives, regardless of FD_CLOEXEC. Confirmed
     * empirically, not assumed: a diagnostic child spawned the identical
     * way, right after opening and clearing CLOEXEC on the source fd,
     * showed only fds 0-4 in its own /proc/self/fd/ -- the source fd was
     * never there. A /proc/self/fd/<n> path handed to that child resolves
     * inside *its own* process, where that fd was never open, so it could
     * never have worked.)
     */
    private suspend fun resolveFfmpegInputPath(item: MediaCollection.MediaNode.Item): ResolvedInput {
        val filePath = item.uri.path
        if (item.uri.scheme == "file" && filePath != null) {
            return ResolvedInput(filePath, null)
        }

        val cacheKey = item.id.toString()
        localSourceMirrors[cacheKey]?.let { existing ->
            if (existing.exists() && existing.length() > 0) return ResolvedInput(existing.absolutePath, null)
            localSourceMirrors.remove(cacheKey)
            mirrorJobs.remove(cacheKey)
        }

        // One resolution per item, decided atomically. The map holds the *job*,
        // not the result, so two concurrent callers share one copy instead of
        // both passing the cache check and both writing the same file. LAZY keeps
        // computeIfAbsent's mapping function side-effect free, as it requires;
        // nothing runs until the await below.
        val job = mirrorJobs.computeIfAbsent(cacheKey) {
            scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) { mirrorOrPipe(item, cacheKey) }
        }
        val path = try {
            job.await()
        } catch (e: Exception) {
            // A Deferred caches its failure forever. Drop it so the next caller
            // retries rather than being pinned to one failed mirror attempt for
            // the controller's life.
            mirrorJobs.remove(cacheKey, job)
            Log.w(TAG, "resolveFfmpegInputPath: mirror job for '${item.name}' failed (${e.message}) -- falling back to pipe:0")
            "pipe:0"
        }
        // mirrorOrPipe's own internal fallbacks (space check, failed copy) also
        // surface here as a plain "pipe:0" string from a successful await -- both
        // that case and the caught-exception case above are pipe:0 for the same
        // reason, so both are normalized into the same ResolvedInput here.
        return ResolvedInput(path, if (path == "pipe:0") item.uri else null)
    }

    /** A real local path for [item] *if one already exists* -- a `file://`
     *  source, or a mirror some earlier call already paid for -- and null
     *  otherwise. For callers that want the cheap answer but must not trigger a
     *  multi-gigabyte copy as a side effect of asking. */
    private fun alreadyLocalInputPath(item: MediaCollection.MediaNode.Item): String? {
        if (item.uri.scheme == "file" && item.uri.path != null) return item.uri.path
        val mirror = localSourceMirrors[item.id.toString()] ?: return null
        return if (mirror.exists() && mirror.length() > 0) mirror.absolutePath else null
    }

    /** Whether [item] belongs to a container or layout (like non-faststart MP4,
     *  AVI, WMV, etc.) known to be unreachable or extremely slow over a
     *  non-seekable `pipe:0`. Callers use this to decide whether to ignore
     *  low-latency flags and force a mirror to cache before probing/encoding. */
    private fun isUnstreamable(item: MediaCollection.MediaNode.Item): Boolean {
        val ext = item.name.substringAfterLast(".", "").lowercase()
        // AVI/WMV/ASF often store indexes at the end. MPEG-TS has no index and 
        // requires constant scanning. Non-faststart MP4 has a trailing moov.
        return !item.isFastStart || ext in setOf("avi", "wmv", "asf", "ts", "mpeg")
    }

    /** The body of [resolveFfmpegInputPath] for a non-`file://` source: mirror
     *  it if there's disk headroom, otherwise report `pipe:0`. Runs on
     *  [Dispatchers.IO] via its caller -- the `transferTo` loop below can move
     *  multiple gigabytes and must not sit on whatever dispatcher asked. */
    private fun mirrorOrPipe(item: MediaCollection.MediaNode.Item, cacheKey: String): String {
        return try {
            val mirror = File(workDir, "source_mirror_$cacheKey")
            val copyStartedAt = System.currentTimeMillis()
            var copiedBytes = 0L
            val opened = context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                val sourceSizeBytes = pfd.statSize
                val usableBytes = workDir.usableSpace
                val requiredFreeBytes = MIN_FREE_SPACE_AFTER_MIRROR_MB * 1024 * 1024
                if (sourceSizeBytes < 0) {
                    Log.d(TAG, "resolveFfmpegInputPath: skipping mirror for ${item.uri} -- source size unknown (statSize failed), not worth the disk risk -- using pipe:0")
                    return@use false
                }
                if (usableBytes - sourceSizeBytes < requiredFreeBytes) {
                    Log.d(TAG, "resolveFfmpegInputPath: skipping mirror for ${item.uri} -- would leave ${(usableBytes - sourceSizeBytes) / (1024 * 1024)}MB free, below the ${MIN_FREE_SPACE_AFTER_MIRROR_MB}MB floor (usable=${usableBytes / (1024 * 1024)}MB, source=${sourceSizeBytes / (1024 * 1024)}MB) -- using pipe:0")
                    return@use false
                }
                FileInputStream(pfd.fileDescriptor).channel.use { input ->
                    FileOutputStream(mirror).channel.use { output ->
                        var position = 0L
                        while (position < sourceSizeBytes) {
                            val transferred = input.transferTo(position, sourceSizeBytes - position, output)
                            if (transferred <= 0) break
                            position += transferred
                        }
                        copiedBytes = position
                    }
                }
                true
            } ?: run {
                Log.w(TAG, "resolveFfmpegInputPath: openFileDescriptor returned null for ${item.uri}, falling back to pipe")
                false
            }

            if (!opened) {
                mirror.delete()
                return "pipe:0"
            }

            Log.d(TAG, "resolveFfmpegInputPath: mirrored ${item.uri} to ${mirror.absolutePath} ($copiedBytes bytes) in ${System.currentTimeMillis() - copyStartedAt}ms -- ffmpeg/ffprobe get a real local path from here on")
            localSourceMirrors[cacheKey] = mirror
            mirror.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "resolveFfmpegInputPath: failed to mirror ${item.uri} to a local file, falling back to pipe: ${e.message}")
            "pipe:0"
        }
    }

    /** Deletes and forgets the cached local mirror (see
     *  [resolveFfmpegInputPath]) for [item], once its transcode is done
     *  with it, so mirrors don't accumulate across a long-running session.
     *  A no-op if [item] was never mirrored in the first place (disk-space
     *  gate said no, or it streamed via pipe:0 for some other reason). */
    private fun releaseSourceMirror(item: MediaCollection.MediaNode.Item) = releaseSourceMirror(item.id.toString())

    /** By-key form, for [release] and any other caller that holds an id rather
     *  than the item. Drops the resolution job along with the file, so a later
     *  request for the same item mirrors again instead of being handed the path
     *  of a mirror that no longer exists. */
    private fun releaseSourceMirror(itemId: String) {
        mirrorJobs.remove(itemId)
        localSourceMirrors.remove(itemId)?.let {
            Log.d(TAG, "releaseSourceMirror: deleting ${it.absolutePath} (${it.length()} bytes)")
            it.delete()
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
        timeoutMs: Long,
        fragmented: Boolean = false
    ): ChunkOutcome {
        val input = resolveFfmpegInputPath(item)
        val sourceMetadata = probeSourceMedia(item, input)
        val outputBox = resolveOutputBox(item, resolution)
        val paddedOutputBox = PixelSize(alignUpForEncoder(outputBox.width), alignUpForEncoder(outputBox.height))

        val args = mutableListOf(
            ffmpegPath, "-y", "-hide_banner", "-hwaccel", "mediacodec",
            "-ndk_codec", "1", // applies to the decoder for -i, below -- see the matching flag after -c:v
            // (13) genpts regenerates missing/broken presentation timestamps --
            // camcorder exports and files that have already been through another
            // lossy transcode are the common real-world source. (14)
            // analyzeduration/probesize give the demuxer a bigger window to get
            // container structure right on unusual muxing than ffmpeg's default
            // probe budget allows; probeSourceMedia's own ffprobe pass already
            // covers most of what this buys, but it's cheap insurance on the
            // encode command itself too, particularly for the containers
            // isUnstreamable already flags as trickier (AVI/WMV/ASF/TS/MPEG).
            "-fflags", "+genpts",
            "-analyzeduration", "20M", "-probesize", "20M"
        )
        if (startTimeMs > 0) args.addAll(listOf("-ss", (startTimeMs / 1000.0).toString()))
        args.addAll(listOf("-i", input.path))
        args.addAll(listOf(
            "-map", "0:v:0", "-vf", videoScaleFilter(outputBox, deinterlace = isInterlaced(sourceMetadata)), "-c:v", targetVideo.encoderId,
            "-b:v", "${videoBitrateKbps}k", "-bitrate_mode", "vbr",
            "-ndk_codec", "1" // applies to the encoder for -c:v, above -- not a duplicate of the flag before -i
        ))
        args.addAll(encoderTuningArgs(targetVideo, resolution, sourceMetadata.frameRate))
        // (10) Tag the output with whatever color metadata the source actually
        // declared -- see colorMetadataArgs' doc comment for exactly what this
        // does and does not do.
        args.addAll(colorMetadataArgs(sourceMetadata))
        // Both the "is there audio to map" decision and the layout args come from
        // the track this call is actually mapping (0:a:$audioTrackIndex), not from
        // the item's default track -- see resolveTrackChannelCount.
        if ((resolveTrackChannelCount(item, audioTrackIndex) ?: 0) > 0) {
            args.addAll(listOf("-map", "0:a:$audioTrackIndex?", "-c:a", targetAudio.encoderId))
            args.addAll(listOf("-b:a", "${audioBitrateBps / 1000}k"))
            args.addAll(channelArgs(resolveItemChannelLayout(item, audioTrackIndex)))
        }
        // The streaming (fragmented) case is unchanged: relative -t duration,
        // timestamps reset near zero by avoid_negative_ts make_zero, corrected
        // downstream by patchFragmentTimestamps against the live session's own
        // offset -- that path is already verified against a real bug and
        // doesn't need a second, different fix layered on top of it.
        //
        // The file-save case instead asks for an ABSOLUTE end (-to, not -t)
        // and keeps real source-relative timestamps in the output (-copyts):
        // -t measures duration from ffmpeg's internal seek origin, which
        // -copyts's whole point is to stop normalizing to zero, and mixing
        // the two silently corrupts the output (confirmed directly: -copyts
        // with -t here produces "PTS has no value" / non-monotonic DTS
        // garbage; -copyts with -to does not -- this isn't a matter of
        // taste). The payoff is verifyChunkStartAlignment below: with real
        // timestamps preserved, this chunk's own first frame can be checked
        // against startTimeMs directly, rather than trusting that whatever
        // `-ss` + mediacodec hwaccel decoded actually landed where it was
        // asked to.
        if (fragmented) {
            if (durationMs != null) args.addAll(listOf("-t", (durationMs / 1000.0).toString()))
        } else {
            args.add("-copyts")
            if (durationMs != null) args.addAll(listOf("-to", ((startTimeMs + durationMs) / 1000.0).toString()))
        }
        if (fragmented) args.addAll(listOf("-movflags", "frag_keyframe+empty_moov+default_base_moof"))
        // (13) avoid_negative_ts handles containers/encoders that legitimately
        // produce negative timestamps; max_muxing_queue_size is the standard
        // mitigation for ffmpeg's "Too many packets buffered for output stream",
        // which shows up when audio and video have a large initial PTS gap.
        // make_zero is right for the fragmented case (it wants to start at
        // zero); it would fight -copyts's whole purpose on the file-save case,
        // so that one only shifts if a timestamp is genuinely negative.
        args.addAll(listOf("-avoid_negative_ts", if (fragmented) "make_zero" else "make_non_negative", "-max_muxing_queue_size", "1024"))
        args.addAll(listOf("-f", "mp4", outputFile.absolutePath))

        Log.d(TAG, "HW ffmpeg: Starting transcode to ${outputFile.absolutePath}, input=${input.path}, source=${item.resolution}@${sourceMetadata.frameRate ?: "?"}fps, fit=$outputBox, padded=$paddedOutputBox, budget=${timeoutMs}ms, fragmented=$fragmented (${diagnosticSnapshot()})")
        val result = runProcessCapture(args, timeoutMs = timeoutMs, stdinSource = input.stdinSource)
        val success = result.succeededWriting(outputFile)
        Log.d(TAG, "HW ffmpeg: Finished success=$success, exitCode=${result.exitCode}, timedOut=${result.timedOut}, size=${outputFile.length()}, ${diagnosticSnapshot()}")
        // timedOut was already known here and thrown away by a Boolean return.
        // The retry loop needs it: a timeout retried against the same budget
        // fails the same way, where a larger budget might not.
        return when {
            !success -> if (result.timedOut) ChunkOutcome.TimedOut else ChunkOutcome.Failed
            // No seek happened, so there's nothing whose landing needs
            // checking: this chunk covers its input from true frame zero by
            // construction. Also skips the fragmented case, which never
            // carries real timestamps to check in the first place (see above).
            fragmented || startTimeMs == 0L -> ChunkOutcome.Success
            verifyChunkStartAlignment(outputFile, startTimeMs, item.name) -> ChunkOutcome.Success
            else -> ChunkOutcome.Failed
        }
    }

    /**
     * Confirms [outputFile] -- produced by the `-ss`-seeked, `-copyts`'d
     * encode just above -- actually starts at [expectedStartMs], not merely
     * that it has the right duration. verifyChunkDuration cannot see this
     * class of bug: a chunk that starts a few frames too early and is then
     * cut to its full nominal length reports exactly the requested duration
     * while silently re-covering content the previous chunk already
     * produced, which is duplicated video at the seam once both chunks are
     * concatenated. ffmpeg's own accurate-seek (on by default here, since
     * neither this call nor any other in this file passes
     * `-noaccurate_seek`) is reliable for this exact seek-then-encode
     * sequence in plain software decode -- confirmed directly, not assumed:
     * fed the same keyframe-snapped boundaries this file computes, encoded
     * with the same `-ss`-before-`-i` placement, concatenated the same way,
     * against a frame-indexed reference file, with zero frames dropped or
     * duplicated. What that test cannot reach is `-hwaccel mediacodec`
     * decode on real device hardware, which is a materially different code
     * path inside ffmpeg, and which this file's own history already shows
     * is where this app's ffmpeg-related surprises live. Rather than assume
     * the software result transfers, this checks every seeked chunk and
     * lets a bad one fall back to the safe path (already hardened
     * separately -- see setStartsAtKeyFrame in transcodeViaSafePath) instead
     * of shipping it.
     *
     * The tolerance is deliberately wide: a chunk's true first frame can
     * legitimately land tens of milliseconds after [expectedStartMs] on its
     * own -- confirmed directly, again, not assumed -- because an encoder
     * with B-frames enabled has its own small initial reorder delay, and
     * that delay is visible in the timestamps even when nothing about the
     * seek was wrong. [CHUNK_START_ALIGNMENT_TOLERANCE_MS] sits comfortably
     * above that noise floor and comfortably below the smallest plausible
     * wrong-keyframe error (at least one whole missed GOP -- seconds, not
     * tens of milliseconds, for any realistic source).
     */
    private suspend fun verifyChunkStartAlignment(outputFile: File, expectedStartMs: Long, itemName: String): Boolean {
        val actualStartMs = probeFirstFramePtsMs(outputFile)
        if (actualStartMs == null) {
            Log.w(TAG, "verifyChunkStartAlignment: '$itemName' -- couldn't probe ${outputFile.name} to verify alignment, trusting it rather than failing a chunk over a probe issue")
            return true
        }
        val deltaMs = actualStartMs - expectedStartMs
        val ok = kotlin.math.abs(deltaMs) <= CHUNK_START_ALIGNMENT_TOLERANCE_MS
        if (!ok) {
            Log.w(TAG, "verifyChunkStartAlignment: '$itemName' -- chunk requested at ${expectedStartMs}ms actually starts at ${actualStartMs}ms (${deltaMs}ms off, tolerance ${CHUNK_START_ALIGNMENT_TOLERANCE_MS}ms) -- rejecting rather than shipping a chunk that overlaps or gaps its neighbor")
        }
        return ok
    }

    /**
     * The minimum pts across every video frame in [file], in ms -- not the
     * first packet in file/decode order, since B-frame reordering means
     * those can differ: a GOP's first *decoded* packet is not necessarily
     * its first *displayed* frame. Confirmed directly against a
     * representative software H.264 encode with B-frames on (not this app's
     * exact mediacodec settings, which a container without hardware codec
     * access can't run, but the same class of encoder): on a fresh chunk
     * with zero seek involved at all, first packet decode-order pts still
     * landed at ~83ms, and the true minimum -- also ~83ms -- was several
     * packets later in the stream, confirming this can happen with nothing
     * about the seek wrong. Sorting rather than reading the first line is
     * what makes this reliable either way. Returns null on any probe
     * failure or an empty/unparseable result, rather than throwing, so a
     * probe hiccup degrades to "trust the chunk" instead of failing it
     * outright -- verifyChunkStartAlignment's caller decides what a null
     * actually means for that chunk.
     */
    private suspend fun probeFirstFramePtsMs(file: File): Long? {
        val args = listOf(
            ffprobePath, "-hide_banner", "-v", "error", "-select_streams", "v:0",
            "-show_entries", "frame=pts_time", "-of", "csv=p=0", file.absolutePath
        )
        val result = runProcessCapture(args, timeoutMs = KEYFRAME_PROBE_TIMEOUT_MS)
        if (result.exitCode != 0) return null
        // Same regex-over-raw-stdout approach as
        // probeKeyframeTimestampsMsViaFfprobe, not a per-line toDoubleOrNull:
        // the ffprobe build used to test this emits a stray trailing comma on
        // the first csv row of any `-show_entries frame=...` output (for any
        // file, not just this one), which fails a strict per-line parse on
        // exactly the frame most likely to be the minimum. Matching numeric
        // substrings directly sidesteps that and whatever else csv=p=0 is
        // liable to do across ffprobe builds.
        return FFPROBE_NUMBER_REGEX.findAll(result.stdout)
            .mapNotNull { it.value.toDoubleOrNull() }
            .minOrNull()
            ?.let { (it * 1000).toLong() }
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
                // Wraps the suspendCancellableCoroutine below so the progress
                // monitor can be launched as its structured child instead of on
                // the long-lived `scope` field -- see the comment on that launch
                // call for what that buys.
                coroutineScope {
                    suspendCancellableCoroutine { continuation ->
                        val videoMimeType = when (targetCodec) {
                            Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264
                            Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265
                            else -> MimeTypes.VIDEO_H264
                        }

                        // Assigned once the export actually starts, below.
                        lateinit var progressJob: Job

                        val transformer = Transformer.Builder(context)
                            .setVideoMimeType(videoMimeType)
                            .setAudioMimeType(MimeTypes.AUDIO_AAC)
                            .setEncoderFactory(DefaultEncoderFactory.Builder(context)
                                .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                                    .setBitrate(resolution.targetBitrateKbps * 1000)
                                    .build())
                                .build())
                            // The muxer's own internal stuck-pipeline timeout, separate
                            // from the withTimeoutOrNull budget above. This file already
                            // documents MediaCodec hanging on specific hardware, so a
                            // second, independently-tuned timeout racing the one this
                            // file controls is more likely to interfere than help.
                            // TIME_UNSET disables it and relies on the outer budget
                            // instead.
                            .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                            .addListener(object : Transformer.Listener {
                                override fun onCompleted(composition: Composition, exportResult: ExportResult) { 
                                    Log.d(TAG, "Full path: Export completed")
                                    progressJob.cancel()
                                    if (continuation.isActive) continuation.resume(true) 
                                }
                                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                    progressJob.cancel()
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

                            // Progress logging loop -- a structured child of this
                            // coroutineScope (the same scope the export itself runs
                            // under) rather than of the long-lived `scope` field. That
                            // makes two things true that weren't before: the enclosing
                            // withTimeoutOrNull firing now cancels this immediately via
                            // ordinary structured-concurrency propagation instead of the
                            // loop having to notice on its next 2-second tick that
                            // `continuation` went inactive, and explicitly cancelling it
                            // in the listener above (rather than waiting for it to notice
                            // on its own) is what lets this coroutineScope actually
                            // complete once the export does.
                            val progressHolder = ProgressHolder()
                            progressJob = launch(Dispatchers.Main) {
                                var lastBytes = -1L
                                var lastGrowthAt = System.currentTimeMillis()
                                while (isActive) {
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
                            continuation.invokeOnCancellation {
                                progressJob.cancel()
                                CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).launch { try { transformer.cancel() } catch (e: Exception) {} }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Full path: Transformer setup failed: ${e.message}", e)
                            if (continuation.isActive) continuation.resume(false)
                        }
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
        timeoutMs: Long,
        fragmented: Boolean = false,
        precomputedAudioFile: File? = null,
        precomputedAudioBaseMs: Long = 0,
        durationToleranceMs: Long = CHUNK_DURATION_TOLERANCE_MS,
        sessionId: String,
        startsAtVerifiedKeyframe: Boolean = false
    ): ChunkOutcome {
        val videoOnlyFile = File(durableWorkDir, "safepath_video_${item.id}_$sessionId.mp4")
        val audioFile = File(durableWorkDir, "safepath_audio_${item.id}_$sessionId.m4a")
        listOf(videoOnlyFile, audioFile).forEach { if (it.exists()) it.delete() }

        // The requested resolution used to reach the encoder as a bitrate budget
        // only: a 4K source asked for at 1080p was encoded at full 4K with a
        // 1080p bitrate, i.e. 4x the pixels at a quarter of the bits per pixel.
        // A Presentation effect makes the composition actually scale, which also
        // makes the export cheaper (fewer pixels through the encoder) and so less
        // likely to hit the per-chunk timeout. Null means the scan's resolution
        // string was unparseable -- in that case encode at source size, as before,
        // rather than guessing at a box.
        //
        // encodeTargetSize, not a local derivation: this has to be the identical
        // box the ffmpeg path pads to, or a chunk that falls back mid-file changes
        // size at the seam. Presentation letterboxes by up to one alignment step
        // to reach it, which is the same black bar ffmpeg's `pad` adds -- that
        // equivalence is the point.
        val targetSize = encodeTargetSize(item, resolution)
        if (targetSize == null) {
            Log.w(TAG, "Safe path: source resolution '${item.resolution}' unparseable for '${item.name}' -- encoding at source size, no scaling applied")
        }

        if (!encoderAdvertisesSupport(targetCodec, resolution)) {
            // Definitive in milliseconds via MediaCodecInfo/EncoderUtil -- fail
            // this chunk now rather than spending its whole timeout budget
            // starting a Transformer export the device doesn't even advertise
            // being able to run.
            Log.e(TAG, "Safe path: device does not advertise support for $targetCodec @ ${resolution.name} -- failing '${item.name}' without attempting the export")
            return ChunkOutcome.Failed
        }

        // Visible outside the suspendCancellableCoroutine so the finally below can
        // reach the Transformer: cancellation has to run on the error and timeout
        // paths alike, which invokeOnCancellation alone didn't cover.
        var transformerRef: Transformer? = null
        var exportStarted = false
        var exportSettled = false

        val videoResult: Boolean? = withContext(Dispatchers.Main) {
            try {
            withTimeoutOrNull(timeoutMs.milliseconds) {
                // Wraps the suspendCancellableCoroutine below so the progress
                // monitor can be launched as its structured child instead of on
                // the long-lived `scope` field -- see the comment on that launch
                // call for what that buys.
                coroutineScope {
                    suspendCancellableCoroutine { continuation ->
                    val videoMimeType = when (targetCodec) {
                        Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264
                        Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265
                        else -> MimeTypes.VIDEO_H264
                    }

                    // Assigned once the export actually starts, below.
                    lateinit var progressJob: Job

                    val transformer = Transformer.Builder(context)
                        .setVideoMimeType(videoMimeType)
                        .setEncoderFactory(DefaultEncoderFactory.Builder(context)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder()
                                .setBitrate(resolution.targetBitrateKbps * 1000)
                                // Same GOP target the ffmpeg path derives in
                                // encoderTuningArgs. Without it Media3's default
                                // 1-second I-frame interval applied -- exactly what
                                // `-g 24` used to impose, left in place on the one
                                // path that wasn't touched, so a mid-file fallback
                                // changed GOP structure as well as dimensions.
                                .setiFrameIntervalSeconds(GOP_TARGET_SECONDS.toFloat())
                                .build())
                            .build())
                        // The muxer's own internal stuck-pipeline timeout, separate
                        // from STALL_ABORT_MS below and the outer withTimeoutOrNull
                        // budget. Both of those already bound a wedged export on
                        // exactly the hardware (Tensor chips) this file documents
                        // hanging elsewhere; TIME_UNSET disables this third,
                        // independently-tuned timeout and relies on those
                        // already-verified bounds instead of racing them.
                        .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                exportSettled = true
                                progressJob.cancel()
                                Log.d(TAG, "Safe path: Video-only export completed")
                                if (continuation.isActive) continuation.resume(true) 
                            }
                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                exportSettled = true
                                progressJob.cancel()
                                if (looksLikeCodecExhaustion(exportException.message) || looksLikeCodecExhaustion(exportException.cause?.message)) {
                                    val consecutive = consecutiveCodecAllocationFailures.incrementAndGet()
                                    Log.e(TAG, "Safe path: codec allocation failure #$consecutive for '${item.name}' -- ${exportException.message}")
                                }
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
                    transformerRef = transformer
                    try {
                        val mediaItemBuilder = MediaItem.Builder().setUri(item.uri).setMimeType(item.mimeType)
                        if (limitDurationMs != null || offsetMs > 0) {
                            mediaItemBuilder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(offsetMs)
                                .setEndPositionMs(if (limitDurationMs != null) offsetMs + limitDurationMs else C.TIME_END_OF_SOURCE)
                                // Without this, Transformer treats offsetMs as an
                                // arbitrary position: seek to the keyframe before it,
                                // decode forward, discard frames until the target is
                                // reached. That discard-near-a-boundary path is exactly
                                // where Transformer has had a confirmed bug before
                                // (androidx/media#829, "clipping produces black initial
                                // frames" -- fixed, but the same class of off-by-a-few-
                                // frames issue at a clip boundary, not a one-off). Every
                                // offsetMs this function is ever called with is either 0
                                // or came from computeChunkBoundaries, which only sets
                                // startsAtVerifiedKeyframe when offsetMs is a keyframe it
                                // actually found in the source -- so telling Transformer
                                // that plainly, when true, skips the discard step (and
                                // whatever bugs live in it) instead of trusting it.
                                .setStartsAtKeyFrame(startsAtVerifiedKeyframe)
                                .build())
                        }
                        val mediaItem = mediaItemBuilder.build()
                        val editedMediaItemBuilder = EditedMediaItem.Builder(mediaItem).setRemoveAudio(true)
                        if (targetSize != null) {
                            // targetSize is encodeTargetSize's already-aligned box, not
                            // the raw fitted one -- so LAYOUT_SCALE_TO_FIT can add a
                            // small bar here (e.g. 1080 aligned up to 1088), same as it
                            // would for any source whose aspect ratio doesn't survive
                            // alignment exactly. That's intentional, not a leftover
                            // distortion: it's the identical bar videoScaleFilter's
                            // `pad` adds on the ffmpeg path for the same box, which is
                            // the whole point of sharing encodeTargetSize -- a chunk
                            // that falls back mid-file lands on the same pixel
                            // dimensions either way.
                            val presentation = Presentation.createForWidthAndHeight(
                                targetSize.width, targetSize.height, Presentation.LAYOUT_SCALE_TO_FIT
                            )
                            editedMediaItemBuilder.setEffects(
                                Effects(ImmutableList.of<AudioProcessor>(), ImmutableList.of<Effect>(presentation))
                            )
                        }
                        val editedMediaItem = editedMediaItemBuilder.build()
                        Log.d(TAG, "Safe path: Starting video-only export for '${item.name}' to ${videoOnlyFile.absolutePath} (source=${item.resolution}, target=${targetSize ?: "source size"}, tier=${resolution.name} ${tierBox(resolution)}, budget=${timeoutMs}ms, ${diagnosticSnapshot()})")
                        transformer.start(editedMediaItem, videoOnlyFile.absolutePath)
                        exportStarted = true

                        // Progress logging loop -- a structured child of this
                        // coroutineScope (the same scope the export runs under)
                        // rather than of the long-lived `scope` field. The enclosing
                        // withTimeoutOrNull firing now cancels this immediately via
                        // ordinary structured-concurrency propagation instead of the
                        // loop having to notice on its next 2-second tick that
                        // `continuation` went inactive, and explicitly cancelling it
                        // in the listener above (rather than waiting for it to notice
                        // on its own) is what lets this coroutineScope actually
                        // complete once the export does.
                        val progressHolder = ProgressHolder()
                        progressJob = launch(Dispatchers.Main) {
                            var lastBytes = -1L
                            var lastGrowthAt = System.currentTimeMillis()
                            while (isActive) {
                                val state = transformer.getProgress(progressHolder)
                                val bytes = videoOnlyFile.length()
                                val now = System.currentTimeMillis()
                                if (bytes != lastBytes) { lastGrowthAt = now; lastBytes = bytes }
                                val stalledForMs = now - lastGrowthAt
                                if (state != Transformer.PROGRESS_STATE_WAITING_FOR_AVAILABILITY) {
                                    // No diagnosticSnapshot here. This loop runs
                                    // on Dispatchers.Main for the whole duration
                                    // of every export, so interpolating one put a
                                    // filesystem stat and two system-service
                                    // lookups on the UI thread every two seconds
                                    // for nothing -- progress alone is what this
                                    // line is for. The stall paths below still
                                    // carry it, because that is where it answers
                                    // a question, and they take it off Main.
                                    Log.d(TAG, "Safe path: Video progress: ${progressHolder.progress}% (state=$state, bytesWritten=$bytes)")
                                }
                                if (stalledForMs >= 6000L) {
                                    val snapshot = withContext(Dispatchers.IO) { diagnosticSnapshot() }
                                    Log.w(TAG, "Safe path: STALL SUSPECTED for '${item.name}' -- no byte growth for ${stalledForMs}ms (state=$state, bytesWritten=$bytes, $snapshot)")
                                }
                                // The counterweight to Budget's generous budgets: a
                                // wedged export now fails in ~30s instead of sitting
                                // out a multi-minute budget, while a device that is
                                // merely slow still gets the whole budget. This used
                                // to warn and keep waiting.
                                if (stalledForMs >= STALL_ABORT_MS) {
                                    val snapshot = withContext(Dispatchers.IO) { diagnosticSnapshot() }
                                    Log.e(TAG, "Safe path: aborting '${item.name}' -- no byte growth for ${stalledForMs}ms (state=$state, bytesWritten=$bytes, $snapshot)")
                                    if (continuation.isActive) continuation.resume(false)
                                    return@launch
                                }
                                delay(2000.milliseconds)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Safe path: Transformer setup failed: ${e.message}", e)
                        if (looksLikeCodecExhaustion(e.message)) consecutiveCodecAllocationFailures.incrementAndGet()
                        if (continuation.isActive) continuation.resume(false)
                    }
                    }
                }
            }
            } finally {
                // Every exit from here -- success, error, timeout, outer
                // cancellation -- goes through this. NonCancellable because on the
                // cancellation path a suspending cleanup would otherwise be
                // cancelled itself, which is precisely when the codecs most need
                // releasing; bounded by TRANSFORMER_CANCEL_TIMEOUT_MS so it can't
                // become the next hang. Skipped when the export already settled:
                // the listener cancelAndAwait adds cannot observe a completion that
                // has already fired, so calling it then would burn the timeout and
                // log a warning for a clean run.
                val pending = transformerRef
                if (pending != null && exportStarted && !exportSettled) {
                    withContext(NonCancellable) { cancelAndAwait(pending) }
                }
            }
        }
        if (videoResult == null) {
            Log.e(TAG, "Safe path: video leg for '${item.name}' exceeded its ${timeoutMs}ms budget (${diagnosticSnapshot()})")
            videoOnlyFile.delete()
            return ChunkOutcome.TimedOut
        }
        if (!videoResult || !videoOnlyFile.exists() || videoOnlyFile.length() == 0L) {
            Log.e(TAG, "Safe path: video leg failed for '${item.name}' (${diagnosticSnapshot()})")
            videoOnlyFile.delete()
            return ChunkOutcome.Failed
        }
        // A video leg that produced output proves the codec pool is not exhausted.
        consecutiveCodecAllocationFailures.set(0)

        val expectedDurationMs = limitDurationMs ?: (resolveSourceDurationMs(item) - offsetMs)
        // One probe of the video-only leg, shared by the two checks below. They
        // read different fields of the same container and nothing writes to the
        // file between them, so the second spawn was pure duplication.
        val videoLegProbe = probeMedia(videoOnlyFile)
        if (!verifyChunkDuration(videoOnlyFile, expectedDurationMs, item.name, durationToleranceMs, probe = videoLegProbe)) {
            Log.e(TAG, "Safe path: video leg for '${item.name}' completed without error but produced truncated output -- treating as failure (known Media3 Transformer long-export issue, androidx/media#758)")
            videoOnlyFile.delete()
            return ChunkOutcome.Failed
        }

        // Verification here has only ever covered duration, which is why the
        // ignored resolution request went unnoticed for so long. Now that a scale
        // is actually requested, confirm it was honored.
        if (targetSize != null && !verifyOutputResolution(videoOnlyFile, targetSize, item.name, probe = videoLegProbe)) {
            Log.e(TAG, "Safe path: video leg for '${item.name}' ignored the requested output size -- treating as failure rather than shipping a chunk at the wrong resolution")
            videoOnlyFile.delete()
            return ChunkOutcome.Failed
        }

        if (!itemHasAudio(item, audioTrackIndex)) {
            Log.d(TAG, "Safe path: No audio on track $audioTrackIndex, copying video-only file to ${outputFile.absolutePath}")
            try {
                videoOnlyFile.copyTo(outputFile, overwrite = true)
                videoOnlyFile.delete()
                val success = outputFile.exists() && outputFile.length() > 0
                Log.d(TAG, "Safe path: Video-only copy success=$success (size=${outputFile.length()})")
                return if (success) ChunkOutcome.Success else ChunkOutcome.Failed
            } catch (e: Exception) {
                Log.e(TAG, "Safe path: Failed to copy video-only file: ${e.message}", e)
                return ChunkOutcome.Failed
            }
        }

        val audioOk: Boolean
        if (precomputedAudioFile != null) {
            val relativeStartMs = offsetMs - precomputedAudioBaseMs
            Log.d(TAG, "Safe path: Slicing pre-encoded audio for '${item.name}' [${relativeStartMs}ms +${expectedDurationMs}ms rel. to ${precomputedAudioFile.name}] -> ${audioFile.absolutePath}")
            audioOk = sliceAudioSegment(precomputedAudioFile, relativeStartMs, expectedDurationMs, audioFile)
        } else {
            Log.d(TAG, "Safe path: Starting audio leg for '${item.name}' -> ${audioFile.absolutePath} (${diagnosticSnapshot()})")
            val audioLegStartedAt = System.currentTimeMillis()
            // The chunk's own budget, not the 300s the audio leg used to hardcode
            // regardless of what its caller was allowed: a "60-second chunk" could
            // take five minutes on its audio alone.
            audioOk = transcodeAudioTrack(item, audioFile, durationS = limitDurationMs?.let { it / 1000.0 }, startTimeMs = offsetMs, encoder = targetAudioCodec.encoderId, audioBitrateBps = audioBitrateBps, audioTrackIndex = audioTrackIndex, timeoutMs = timeoutMs)
            Log.d(TAG, "Safe path: Audio leg for '${item.name}' finished after ${System.currentTimeMillis() - audioLegStartedAt}ms, success=$audioOk")
        }
        if (!audioOk) {
            Log.e(TAG, "Safe path: audio leg failed for '${item.name}' (${diagnosticSnapshot()})")
            videoOnlyFile.delete()
            return ChunkOutcome.Failed
        }

        val muxOk = remuxVideoAndAudio(videoOnlyFile, audioFile, outputFile, fragmented = fragmented)
        videoOnlyFile.delete()
        audioFile.delete()
        if (!muxOk || !outputFile.exists() || outputFile.length() == 0L) return ChunkOutcome.Failed
        return if (verifyMuxedAvSync(outputFile, item.name)) ChunkOutcome.Success else ChunkOutcome.Failed
    }

    /** Fallback for [itemHasAudio] when the library scan recorded no audio
     *  metadata at all. Cached per item: this is the call that cost a full
     *  source read per chunk on a piped input, so it must happen at most once. */
    private suspend fun probedHasAudioTrack(item: MediaCollection.MediaNode.Item): Boolean {
        val cacheKey = item.id.toString()
        probedAudioPresence[cacheKey]?.let { return it }
        val input = resolveFfmpegInputPath(item)
        val args = listOf(ffprobePath, "-hide_banner", "-v", "error", "-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", input.path)
        val result = runProcessCapture(args, timeoutMs = PROBE_TIMEOUT_MS, stdinSource = input.stdinSource)
        val hasAudio = result.exitCode == 0 && result.stdout.isNotBlank()
        probedAudioPresence[cacheKey] = hasAudio
        return hasAudio
    }

    /**
     * Cancels [transformer] and waits for it to actually report that it released
     * its codecs.
     *
     * Fire-and-forget cancellation leaks a decoder, an encoder and a GL context
     * per timeout. Android's budget of concurrent hardware codec instances is
     * small and process-global, so a handful of those exhausts it, after which
     * every subsequent `Transformer.start` fails for the life of the process --
     * which is what a stall hitting ~50% of runs with no clean codec or
     * resolution correlation looks like from the outside.
     *
     * Only call this when an export was started and never settled: the listener
     * added here can't observe a completion that already happened, so calling it
     * after `onCompleted` would just burn the timeout and log a false warning.
     */
    @OptIn(UnstableApi::class)
    private suspend fun cancelAndAwait(transformer: Transformer, timeoutMs: Long = TRANSFORMER_CANCEL_TIMEOUT_MS) {
        val released = CompletableDeferred<Unit>()
        withContext(Dispatchers.Main) {
            // addListener, not setListener: this must not displace the export's
            // own listener, which may still resume its continuation.
            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) { released.complete(Unit) }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) { released.complete(Unit) }
            })
            runCatching { transformer.cancel() }
                .onFailure { Log.w(TAG, "cancelAndAwait: cancel() threw: ${it.message}") }
        }
        if (withTimeoutOrNull(timeoutMs) { released.await() } == null) {
            Log.w(TAG, "cancelAndAwait: Transformer did not report release within ${timeoutMs}ms -- codec pool may be degraded")
        }
    }

    /** Whether a failure message looks like the device's codec pool being gone
     *  rather than anything about this particular file. Retrying these cannot
     *  succeed, so [transcodeInChunks] stops instead of spending its retry
     *  budget proving it. */
    private fun looksLikeCodecExhaustion(text: String?): Boolean {
        if (text == null) return false
        return text.contains("Failed to allocate", ignoreCase = true) ||
            text.contains("CodecException", ignoreCase = true) ||
            text.contains("0x80001001", ignoreCase = true)
    }

    /**
     * Why a chunk attempt ended. Only the one bit [ProcessOutput] and
     * `withTimeoutOrNull` already know, propagated instead of discarded: a
     * `Boolean` return made "the encoder timed out" and "this file can't be
     * encoded" indistinguishable, so the retry loop reran a timeout against an
     * identical budget and failed identically.
     *
     * Deliberately not the full sealed failure hierarchy -- that touches every
     * function in the transcode path, and line-number stability against logcat
     * has real value while the hang investigation is open.
     */
    private sealed interface ChunkOutcome {
        data object Success : ChunkOutcome
        data object TimedOut : ChunkOutcome
        data object Failed : ChunkOutcome
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

    @OptIn(UnstableApi::class)
    private inner class SurfaceAssetLoaderBridge(context: Context, codec: Constants.Transcoder.VideoCodec, width: Int, height: Int) {
        val outputFile = File(workDir, "transformer_video_only_${codec.name.lowercase()}.mp4")
        private val surfaceDeferred = CompletableDeferred<Surface>(); private val completionDeferred = CompletableDeferred<Boolean>(); private var loaderRef: SurfaceAssetLoader? = null; private val transformer: Transformer
        init {
            if (outputFile.exists()) outputFile.delete()
            val mimeType = when (codec) { 
                Constants.Transcoder.VideoCodec.H264 -> MimeTypes.VIDEO_H264
                Constants.Transcoder.VideoCodec.HEVC -> MimeTypes.VIDEO_H265
                else -> MimeTypes.VIDEO_H264
            }
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
        // (9) Was fire-and-forget: launching transformer.cancel() and returning
        // immediately, without awaiting the release that cancelAndAwait's own
        // doc comment says is the entire reason it exists -- a leaked decoder,
        // encoder and GL context per un-awaited cancellation. Delegate to it
        // instead of re-implementing its listener/timeout logic here.
        suspend fun cancel() { if (!completionDeferred.isCompleted) cancelAndAwait(transformer) }
    }

    private data class DecodeResult(val frameCount: Int, val success: Boolean?)

    private suspend fun transcodeAudioTrack(
        item: MediaCollection.MediaNode.Item,
        outFile: File,
        durationS: Double? = null,
        startTimeMs: Long = 0,
        encoder: String = "aac",
        audioBitrateBps: Int = 128_000,
        audioTrackIndex: Int = 0,
        timeoutMs: Long = 300_000L
    ): Boolean {
        val input = resolveFfmpegInputPath(item)
        // No -hwaccel/-ndk_codec here: those are video decode acceleration hints
        // and this call has no video output (-vn, only an audio stream mapped).
        // They were previously copied over from the video encode args, and on
        // this hardware mediacodec hwaccel is known to hang (see
        // transcodeViaHardwareFfmpeg's callers/useHardwareFfmpegPipeline) --
        // a very plausible explanation for a whole-file audio pre-encode taking
        // minutes when it should take seconds.
        val args = mutableListOf(ffmpegPath, "-y", "-hide_banner")
        if (startTimeMs > 0) args.addAll(listOf("-ss", (startTimeMs / 1000.0).toString()))
        args.addAll(listOf("-i", input.path, "-vn", "-sn", "-map", "0:a:$audioTrackIndex?", "-c:a", encoder, "-b:a", "${audioBitrateBps / 1000}k"))
        if (durationS != null) args.addAll(listOf("-t", durationS.toString()))
        args.addAll(channelArgs(resolveItemChannelLayout(item, audioTrackIndex)))
        args.addAll(listOf("-f", "mp4", outFile.absolutePath))
        // timeoutMs used to be a flat 300s regardless of what was being asked for
        // -- a 3-minute clip and a 3-hour film shared one budget, and a chunk's
        // audio leg could take five minutes against a "60-second chunk" budget.
        // Callers now derive this from the work (Budget.forAudio for the
        // whole-range pre-encode, the chunk's own budget for a per-chunk leg).
        val result = runProcessCapture(args, timeoutMs = timeoutMs, stdinSource = input.stdinSource)
        return result.succeededWriting(outFile)
    }

    /**
     * Muxes an already-encoded video-only file and audio-only file into one
     * container via stream copy. The audio map is mandatory (no trailing `?`)
     * -- callers only reach this once they've already confirmed audio is
     * expected (see [itemHasAudio]), so a failed map means something is
     * genuinely wrong with the audio leg and should fail the chunk instead of
     * silently shipping a video-only file reported as a success.
     */
    private suspend fun remuxVideoAndAudio(videoFile: File, audioFile: File, outFile: File, fragmented: Boolean = false): Boolean {
        Log.d(TAG, "Remuxing: V=${videoFile.absolutePath} (${videoFile.length()} bytes), A=${audioFile.absolutePath} (${audioFile.length()} bytes) -> ${outFile.absolutePath}, fragmented=$fragmented")
        // (13) max_muxing_queue_size is the standard mitigation for ffmpeg's "Too
        // many packets buffered for output stream", which shows up when the two
        // inputs have a large initial PTS gap -- a real possibility here since
        // video and audio are two independently-produced legs, not two streams
        // demuxed from one source with a shared timeline. avoid_negative_ts and
        // genpts are cheap insurance alongside it.
        val args = mutableListOf(
            ffmpegPath, "-y", "-hide_banner", "-fflags", "+genpts",
            "-i", videoFile.absolutePath, "-i", audioFile.absolutePath,
            "-map", "0:v:0", "-map", "1:a:0", "-c", "copy",
            "-avoid_negative_ts", "make_zero", "-max_muxing_queue_size", "1024"
        )
        if (fragmented) args.addAll(listOf("-movflags", "frag_keyframe+empty_moov+default_base_moof"))
        args.addAll(listOf("-f", "mp4", outFile.absolutePath))
        // Stream-copy work, budgeted by bytes rather than a flat 60s -- a remux
        // of a multi-gigabyte 4K leg and one of a small 480p leg were held to the
        // same number before.
        val timeoutMs = Budget.forStreamCopy(videoFile.length() + audioFile.length())
        val result = runProcessCapture(args, timeoutMs = timeoutMs)
        val success = result.succeededWriting(outFile)
        Log.d(TAG, "Remuxing finished: success=$success, exitCode=${result.exitCode}, budget=${timeoutMs}ms, targetSize=${outFile.length()}, ${diagnosticSnapshot()}")
        return success
    }

    /**
     * Cuts [durationMs] of already-encoded audio starting at [startMs] out of
     * [sourceAudioFile] via stream copy -- no re-encode, so this introduces no
     * new AAC encoder priming/delay at the chunk boundary. Used once a
     * continuous audio track has already been produced for the whole transcode
     * range (see the file-mode branch of [transcodeInChunks]), so a chunk's
     * audio becomes a cheap copy instead of an independent decode+encode of
     * the raw source -- eliminating both the per-chunk encoder-restart glitch
     * and the risk of the per-chunk leg drifting from the video leg's timing.
     */
    private suspend fun sliceAudioSegment(sourceAudioFile: File, startMs: Long, durationMs: Long, outFile: File): Boolean {
        val startS = startMs.coerceAtLeast(0) / 1000.0
        val durationS = durationMs / 1000.0
        val args = listOf(ffmpegPath, "-y", "-hide_banner", "-ss", startS.toString(), "-i", sourceAudioFile.absolutePath, "-t", durationS.toString(), "-c", "copy", "-f", "mp4", outFile.absolutePath)
        val result = runProcessCapture(args, timeoutMs = AUDIO_SLICE_TIMEOUT_MS)
        val success = result.succeededWriting(outFile)
        if (!success) {
            Log.e(TAG, "sliceAudioSegment: failed to slice [${startMs}ms +${durationMs}ms] from ${sourceAudioFile.absolutePath} (exitCode=${result.exitCode})")
        }
        return success
    }

    private fun currentAppVersionCode(): Long = try { val info = context.packageManager.getPackageInfo(context.packageName, 0); info.longVersionCode } catch (e: PackageManager.NameNotFoundException) { -1L }

    /** Last [diagnosticSnapshot] result and when it was taken. Volatile rather
     *  than locked: a torn read is impossible for a reference, and two threads
     *  both missing the TTL cost one redundant snapshot, not a correctness
     *  problem. */
    @Volatile private var cachedSnapshot: Pair<Long, String>? = null

    /** Cheap point-in-time system health snapshot. Thermal throttling and memory
     *  pressure are the obvious suspects for a stall that hits ~50% of runs with
     *  no clean codec/resolution/audio-format correlation over a multi-hour test --
     *  attach this to progress/failure logs so a rerun can show whether stalls
     *  cluster with elevated thermal state or low available memory. Also reports
     *  free space on both of this controller's directories (E3 split them):
     *  [workDir], where only the regenerable source mirror lands -- the figure
     *  [resolveFfmpegInputPath] itself checks before mirroring -- and
     *  [durableWorkDir], where chunk files, the concat list, and the safe path's
     *  video-only/audio-only legs land instead. A write failure or an
     *  unexpected fallback to pipe:0 could come from either running out
     *  independently, since they need not be the same underlying volume.
     *
     *  Memoized for [SNAPSHOT_TTL_MS]. "Cheap" was optimistic: two
     *  getSystemService lookups, a MemoryInfo allocation and two filesystem
     *  stats, built eagerly at every one of 13 call sites because Kotlin
     *  evaluates a string template whether or not the log survives its level
     *  filter. */
    private fun diagnosticSnapshot(): String {
        val now = System.currentTimeMillis()
        cachedSnapshot?.let { (at, value) -> if (now - at < SNAPSHOT_TTL_MS) return value }
        val value = buildDiagnosticSnapshot()
        cachedSnapshot = now to value
        return value
    }

    private fun buildDiagnosticSnapshot(): String {
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
            val freeCacheMB = workDir.usableSpace / (1024 * 1024)
            val freeDurableMB = durableWorkDir.usableSpace / (1024 * 1024)
            "thermal=$thermalStr, availMemMB=${memInfo.availMem / (1024 * 1024)}, lowMemory=${memInfo.lowMemory}, freeCacheMB=$freeCacheMB, freeDurableMB=$freeDurableMB"
        } catch (e: Exception) {
            "diagnosticSnapshot failed: ${e.message}"
        }
    }

    /**
     * One ffprobe pass for every field this class asks files about: container
     * duration, per-stream video/audio duration, pixel dimensions, frame rate
     * and audio presence.
     *
     * Replaces probeDurationMs, probeVideoDimensions, probeStreamDurationMs and
     * the stream half of probeSourceMetadata, each of which spawned its own
     * process for one field. An ffprobe spawn costs process creation, binary
     * load and a container header read; the marginal cost of asking it for six
     * fields instead of one is nil, so the spawn count was the whole cost.
     *
     * Null means ffprobe failed or its output was unparseable -- distinct from
     * a [MediaProbe] whose fields are individually null, which means ffprobe
     * ran and that container simply doesn't declare them.
     */
    private suspend fun probeMedia(path: String, stdinSource: Uri? = null): MediaProbe? {
        val args = listOf(
            ffprobePath, "-hide_banner", "-v", "error",
            "-show_entries",
            // color_primaries/color_transfer/color_space (10) and field_order
            // (15) added to this one existing pass rather than spawning a
            // separate ffprobe for either -- same rationale as every other
            // field already here (see this function's doc comment).
            "format=duration:stream=index,codec_type,duration,width,height,avg_frame_rate,r_frame_rate,channels,color_primaries,color_transfer,color_space,field_order",
            "-of", "json",
            path
        )
        val result = runProcessCapture(args, timeoutMs = PROBE_TIMEOUT_MS, stdinSource = stdinSource)
        if (result.exitCode != 0) return null
        return parseProbeJson(result.stdout)
    }

    /** File form of [probeMedia]. Null for a missing or empty file, without
     *  spawning anything -- every caller already treats that as a failure. */
    private suspend fun probeMedia(file: File): MediaProbe? {
        if (!file.exists() || file.length() == 0L) return null
        return probeMedia(file.absolutePath)
    }

    /** ffprobe emits "N/A" or omits the key entirely for a stream that doesn't
     *  declare a duration (fragmented MP4, most notably), and optString gives
     *  back "" for a missing key -- both land on null here rather than 0, which
     *  a caller would read as a real zero-length stream. */
    private fun durationMsOf(obj: JSONObject?): Long? =
        obj?.optString("duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() }?.takeIf { it > 0 }

    /** Same "missing/undetected" collapse as [durationMsOf], for the
     *  string-valued stream fields (10, 15) requested alongside it --
     *  color_primaries/color_transfer/color_space and field_order all come
     *  back as ffprobe's own literal "unknown" when undetected, or the key is
     *  absent entirely on a container that never declares it, rather than
     *  being omitted outright -- both collapse to null here instead of every
     *  caller having to know to check for the string "unknown" itself. */
    private fun probeStringField(obj: JSONObject?, key: String): String? =
        obj?.optString(key)?.trim()?.takeIf { it.isNotEmpty() && it != "unknown" }

    private fun parseProbeJson(json: String): MediaProbe? = try {
        val root = JSONObject(json)
        val streams = root.optJSONArray("streams")
        var video: JSONObject? = null
        var audio: JSONObject? = null
        for (i in 0 until (streams?.length() ?: 0)) {
            val stream = streams?.optJSONObject(i) ?: continue
            when (stream.optString("codec_type")) {
                // First of each kind, which is what "v:0"/"a:0" meant to the
                // per-stream probes this replaces.
                "video" -> if (video == null) video = stream
                "audio" -> if (audio == null) audio = stream
            }
        }
        MediaProbe(
            formatDurationMs = durationMsOf(root.optJSONObject("format")),
            videoDurationMs = durationMsOf(video),
            audioDurationMs = durationMsOf(audio),
            size = video?.let {
                val width = it.optInt("width", -1)
                val height = it.optInt("height", -1)
                if (width > 0 && height > 0) PixelSize(width, height) else null
            },
            frameRate = parseFrameRate(video?.optString("avg_frame_rate"))
                ?: parseFrameRate(video?.optString("r_frame_rate")),
            hasAudio = audio != null,
            colorPrimaries = probeStringField(video, "color_primaries"),
            colorTransfer = probeStringField(video, "color_transfer"),
            colorSpace = probeStringField(video, "color_space"),
            fieldOrder = probeStringField(video, "field_order")
        )
    } catch (e: Exception) {
        Log.w(TAG, "parseProbeJson: could not parse ffprobe output (${e.message})")
        null
    }

    /**
     * One cached [probeMedia] pass over the *source*, for everything this file
     * would otherwise have to guess at about it: the container's real duration
     * and the real frame rate, chiefly.
     *
     * Returns empty metadata for a piped source and never probes one:
     * [runProcessCapture] streams the entire file into the child's stdin, so a
     * probe against pipe:0 costs a full read of the source (see the pipe:0
     * notes on [resolveFfmpegInputPath]). The callers' fallbacks -- library
     * metadata for duration, [ASSUMED_FRAME_RATE_FPS] for GOP sizing -- are far
     * cheaper than that and right often enough. Chunk-file probes are always
     * local paths and so are unaffected by this guard.
     *
     * Frame rate still belongs in the library scan next to resolution and
     * channel count -- the scan already runs the probe that would yield it, and
     * putting it there removes this source-side spawn entirely. Until then the
     * cache is what keeps it to one per item rather than one per chunk.
     */
    private suspend fun probeSourceMedia(item: MediaCollection.MediaNode.Item, input: ResolvedInput): MediaProbe {
        if (input.isPiped) return MediaProbe()
        val cacheKey = item.id.toString()
        probedSourceMedia[cacheKey]?.let { return it }

        // An empty result is cached alongside a real one, deliberately: a probe
        // that failed once for this item will fail the same way for every
        // remaining chunk, and re-spawning it each time buys nothing.
        val probe = probeMedia(input.path) ?: MediaProbe().also {
            Log.w(TAG, "probeSourceMedia: ffprobe failed for '${item.name}' -- falling back to scan metadata and an assumed frame rate")
        }

        Log.d(TAG, "probeSourceMedia: '${item.name}' duration=${probe.formatDurationMs}ms frameRate=${probe.frameRate} size=${probe.size} hasAudio=${probe.hasAudio} (scan duration=${item.durationMs}ms)")
        probedSourceMedia[cacheKey] = probe
        return probe
    }

    /** ffprobe reports frame rates as a rational, "24000/1001". Implausible
     *  values -- a VFR container reporting 1000/1, a zero denominator, a
     *  missing entry -- come back null so callers fall back rather than sizing
     *  a GOP or a frame-rate cap off nonsense. */
    private fun parseFrameRate(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.split("/")
        val numerator = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return null
        val denominator = if (parts.size > 1) (parts[1].trim().toDoubleOrNull() ?: return null) else 1.0
        if (numerator <= 0.0 || denominator <= 0.0) return null
        val fps = numerator / denominator
        return if (fps in 1.0..240.0) fps else null
    }

    /**
     * ffmpeg args tagging an encode with whatever color metadata [probe]
     * carried over from the source (10): `-color_primaries`/`-color_trc`/
     * `-colorspace`, one flag per field ffprobe actually reported. Empty for
     * any field ffprobe didn't detect, which is the same as omitting it today
     * -- this only fixes the fields that *were* detected and were previously
     * dropped on the floor.
     *
     * Purely descriptive: this does not tone-map or otherwise transform
     * anything, so an HDR/wide-gamut source is still encoded exactly as it
     * would have been before -- it's now just tagged as what it actually is
     * (e.g. bt2020/smpte2084) instead of left untagged, which most renderers
     * then interpret as ordinary BT.709 SDR -- a real washed-out/oversaturated
     * bug for genuinely HDR content. Whether HDR sources should instead be
     * tone-mapped down to SDR for renderers that can't display HDR, or
     * preserved end-to-end for the ones that can, is a product decision, not
     * an engineering one -- deliberately not made here. transcodeViaSafePath's
     * Media3 leg and SurfaceAssetLoaderBridge (which hardcodes
     * ColorInfo.SDR_BT709_LIMITED) still don't carry this through either;
     * making all three consistent depends on that decision being made first.
     */
    private fun colorMetadataArgs(probe: MediaProbe): List<String> {
        val args = mutableListOf<String>()
        probe.colorPrimaries?.let { args.addAll(listOf("-color_primaries", it)) }
        probe.colorTransfer?.let { args.addAll(listOf("-color_trc", it)) }
        probe.colorSpace?.let { args.addAll(listOf("-colorspace", it)) }
        return args
    }

    /** Whether [probe]'s source reported an interlaced field order (15) --
     *  anything ffprobe named other than "progressive" ("tt"/"bb"/"tb"/"bt"),
     *  with a null [MediaProbe.fieldOrder] (undetected, or the container
     *  never declares it) treated as progressive rather than assumed
     *  interlaced. Trusts ffprobe's own container-level field rather than
     *  running a separate idet pre-pass over the frames themselves -- cheaper,
     *  and it rides along on the same probeMedia pass everything else here
     *  already pays for. See [videoScaleFilter]'s `deinterlace` parameter for
     *  where this feeds in. */
    private fun isInterlaced(probe: MediaProbe): Boolean =
        probe.fieldOrder != null && probe.fieldOrder != "progressive"

    /**
     * How long [item] really is, in ms: the container's own duration where it
     * can be read cheaply, otherwise the library scan's `item.durationMs`.
     *
     * The scan value being slightly long is the expensive case: the final chunk
     * of a range requests past EOF, produces short output, is rejected, retries
     * identically, and aborts a transcode in which every other chunk already
     * succeeded. [FINAL_CHUNK_TOLERANCE_MS] absorbs that where this can't
     * (piped sources).
     */
    private suspend fun resolveSourceDurationMs(item: MediaCollection.MediaNode.Item): Long {
        // useHardwareFfmpegPipeline=false or isUnstreamable(item): resolveFfmpegInputPath
        // can decide to mirror the entire source to cache. We only do that if the 
        // item is unstreamable or if we have already mirrored it.
        val cheapPath = alreadyLocalInputPath(item)
        val input = if (cheapPath != null || isUnstreamable(item)) {
            resolveFfmpegInputPath(item)
        } else {
            ResolvedInput("pipe:0", item.uri)
        }
        val probedMs = probeSourceMedia(item, input).formatDurationMs
            ?: return item.durationMs
        val deltaMs = probedMs - item.durationMs
        if (kotlin.math.abs(deltaMs) > CHUNK_DURATION_TOLERANCE_MS) {
            Log.w(TAG, "resolveSourceDurationMs: '${item.name}' container reports ${probedMs}ms but the library scan says ${item.durationMs}ms (${deltaMs}ms out) -- trusting the container")
        }
        return probedMs
    }

    /**
     * Confirms an encoded video leg came out no larger than [requested].
     *
     * Exists because the safe path used to pass its resolution request to the
     * encoder as a bitrate budget and nothing else, so an ignored scale request
     * was indistinguishable from an honored one: verification only ever looked
     * at duration.
     *
     * Deliberately hard to trip, because a rejection here costs a chunk retry
     * and then the entire transcode (see transcodeInChunks' retry-then-abort
     * loop). Three allowances:
     *  - [ENCODER_ALIGNMENT_SLACK_PX] of overshoot per axis, since encoders
     *    align dimensions up and Media3's encoder fallback may substitute a
     *    nearby supported resolution;
     *  - either orientation, since a rotated source can legitimately come out
     *    with its axes swapped relative to the scanned dimensions the box was
     *    derived from;
     *  - an unreadable probe result passes, since "ffprobe couldn't tell us"
     *    is not evidence of a wrong resolution.
     * What's left is the case this exists for: the scale request being ignored
     * outright and a full-size frame being encoded against a smaller tier's
     * bitrate budget.
     */
    private suspend fun verifyOutputResolution(
        file: File,
        requested: PixelSize,
        itemName: String,
        probe: MediaProbe? = null
    ): Boolean {
        // Pass [probe] wherever the caller has already probed this exact file
        // and hasn't written to it since -- the safe path checks duration and
        // resolution back to back on the same video-only leg, which used to be
        // two ffprobe spawns for one file.
        val actual = (probe ?: probeMedia(file))?.size
        if (actual == null) {
            Log.w(TAG, "verifyOutputResolution: '$itemName' dimensions unreadable in ${file.absolutePath} -- skipping the check rather than failing the chunk on a probe miss")
            return true
        }
        fun fitsWithin(box: PixelSize): Boolean =
            actual.width - box.width <= ENCODER_ALIGNMENT_SLACK_PX &&
                actual.height - box.height <= ENCODER_ALIGNMENT_SLACK_PX
        val ok = fitsWithin(requested) || fitsWithin(PixelSize(requested.height, requested.width))
        Log.d(TAG, "verifyOutputResolution: '$itemName' requested=$requested actual=$actual ${if (ok) "OK" else "REJECTED"}")
        if (!ok) {
            Log.e(TAG, "verifyOutputResolution: '$itemName' encoded at $actual against a requested $requested (${ENCODER_ALIGNMENT_SLACK_PX}px slack, either orientation) -- the scale request was not applied")
        } else if (actual != requested) {
            Log.d(TAG, "verifyOutputResolution: '$itemName' landed at $actual rather than the requested $requested -- within tolerance, accepted")
        }
        return ok
    }

    /**
     * Confirms [file] actually covers [expectedMs] of content before it's
     * trusted. This exists specifically because of the memory-investigation
     * finding: Media3 Transformer's video-only export can complete cleanly
     * (onCompleted, no exception) while having silently produced only a
     * fraction of the requested duration -- see androidx/media#758. Without
     * this check that truncated leg gets muxed against a full-length audio
     * track and shipped as SUCCESS.
     *
     * Overrun is checked too, but against a wider bar
     * ([CHUNK_OVERRUN_TOLERANCE_MS] against [CHUNK_DURATION_TOLERANCE_MS]).
     * The asymmetry is intentional and documented on those constants: an
     * over-long chunk is a real failure mode, and a false rejection is the
     * expensive one. Don't collapse the two into a single symmetric tolerance.
     */
    private suspend fun verifyChunkDuration(
        file: File,
        expectedMs: Long,
        itemName: String,
        toleranceMs: Long = CHUNK_DURATION_TOLERANCE_MS,
        overrunToleranceMs: Long = CHUNK_OVERRUN_TOLERANCE_MS,
        probe: MediaProbe? = null
    ): Boolean {
        if (!file.exists() || file.length() == 0L) {
            Log.e(TAG, "verifyChunkDuration: '$itemName' output file missing or empty (${file.absolutePath})")
            return false
        }
        // See verifyOutputResolution's note on [probe]: supplied where the
        // caller has already probed this file, re-probed here otherwise.
        val actualMs = (probe ?: probeMedia(file))?.formatDurationMs
        if (actualMs == null) {
            Log.e(TAG, "verifyChunkDuration: '$itemName' ffprobe could not read duration of ${file.absolutePath}")
            return false
        }
        val shortfallMs = expectedMs - actualMs
        val overrunMs = -shortfallMs
        val ok = shortfallMs <= toleranceMs && overrunMs <= overrunToleranceMs
        Log.d(TAG, "verifyChunkDuration: '$itemName' expected=${expectedMs}ms actual=${actualMs}ms shortfall=${shortfallMs}ms (tolerance ${toleranceMs}ms short / ${overrunToleranceMs}ms long) ${if (ok) "OK" else "REJECTED"}")
        if (!ok && shortfallMs > toleranceMs) {
            Log.e(TAG, "verifyChunkDuration: '$itemName' is ${shortfallMs}ms shorter than expected -- rejecting rather than shipping a silently truncated file")
        } else if (!ok) {
            Log.e(TAG, "verifyChunkDuration: '$itemName' is ${overrunMs}ms longer than expected -- rejecting rather than shipping content that overlaps the previous chunk")
        } else if (overrunMs > 0) {
            Log.d(TAG, "verifyChunkDuration: '$itemName' ran ${overrunMs}ms long, within the ${overrunToleranceMs}ms overrun tolerance -- accepted (encoder padding and clip-end rounding both land here)")
        }
        return ok
    }

    /**
     * Confirms a just-muxed chunk actually has an audio track and that its
     * duration agrees with the video track's, tightly. This exists because
     * [verifyChunkDuration] only checks the container's overall duration (and,
     * for the video leg, only the video-only file before audio was even added)
     * -- it could not catch a chunk that muxed "successfully" with no audio
     * track at all (see the now-mandatory map in [remuxVideoAndAudio]), nor one
     * where the independently-produced video and audio legs simply drifted
     * apart by less than verifyChunkDuration's own tolerance.
     */
    private suspend fun verifyMuxedAvSync(
        file: File,
        itemName: String,
        toleranceMs: Long = AV_SYNC_TOLERANCE_MS,
        probe: MediaProbe? = null
    ): Boolean {
        // One probe for both stream durations, where this used to spawn ffprobe
        // twice against the same file for one field each.
        val resolved = probe ?: probeMedia(file)
        val videoDurationMs = resolved?.videoDurationMs
        val audioDurationMs = resolved?.audioDurationMs
        if (videoDurationMs == null) {
            Log.e(TAG, "verifyMuxedAvSync: '$itemName' has no readable video stream in ${file.absolutePath}")
            return false
        }
        if (audioDurationMs == null) {
            // hasAudio separates the two cases the old two-spawn version
            // collapsed into one message: a chunk that muxed with no audio track
            // at all, versus one whose audio track is present but declares no
            // duration. Both still reject -- the second would sail past an
            // AV-sync check that has nothing to compare.
            val detail = if (resolved?.hasAudio == true) "an audio stream that declares no duration" else "no audio stream"
            Log.e(TAG, "verifyMuxedAvSync: '$itemName' has $detail in ${file.absolutePath} -- rejecting rather than shipping a silently video-only chunk")
            return false
        }
        val driftMs = kotlin.math.abs(videoDurationMs - audioDurationMs)
        val ok = driftMs <= toleranceMs
        Log.d(TAG, "verifyMuxedAvSync: '$itemName' video=${videoDurationMs}ms audio=${audioDurationMs}ms drift=${driftMs}ms ${if (ok) "OK" else "REJECTED"}")
        if (!ok) {
            Log.e(TAG, "verifyMuxedAvSync: '$itemName' audio/video duration drift ${driftMs}ms exceeds tolerance ${toleranceMs}ms")
        }
        return ok
    }

    /**
     * Every keyframe's presentation timestamp in [item], in ms, sorted --
     * cached per item, since the index is a property of the file and never
     * changes. Null only if both probes fail, at which point
     * [computeChunkBoundaries] gives up on keyframe-snapped chunks in favor
     * of fixed-time ones.
     *
     * Two probes, tried in whichever order the input supports:
     *  - **ffprobe `-skip_frame nokey`** reads the whole bitstream but only
     *    decodes frame headers, not full frames -- fast even for a long
     *    file, and with much wider container coverage. It leads for a real
     *    local path.
     *  - **[probeKeyframeTimestampsMsViaMediaExtractor]** leads for a
     *    `pipe:0` input. ffprobe's pass comes back empty for a content://
     *    [item] on a low-space device whose container keeps its keyframe
     *    index anywhere but the front (moov/stss, for MP4 -- anything not
     *    already "faststart"): indexing that over a pipe would mean seeking
     *    back after reaching the end, and a pipe can't. MediaExtractor gets
     *    real random access to [item.uri] via ContentResolver regardless of
     *    whether a mirror exists, so on a pipe it is the likelier winner as
     *    well as the cheaper one -- trying it second meant paying ffprobe's
     *    full budget first to learn nothing.
     *
     * @param budgetMs wall-clock ceiling for each probe. The default is the
     *   full [KEYFRAME_PROBE_TIMEOUT_MS]; the streaming path passes
     *   [KEYFRAME_PROBE_STREAM_BUDGET_MS] instead.
     * @param allowMirror whether resolving the input may copy a content://
     *   source to local cache first. False on any path with a latency
     *   deadline.
     */
    private suspend fun probeKeyframeTimestampsMs(
        item: MediaCollection.MediaNode.Item,
        budgetMs: Long = KEYFRAME_PROBE_TIMEOUT_MS,
        allowMirror: Boolean = true
    ): List<Long>? {
        val cacheKey = item.id.toString()
        keyframeIndex[cacheKey]?.let {
            Log.d(TAG, "probeKeyframeTimestampsMs: '${item.name}' -- ${it.size} keyframe(s) already indexed, no probe needed")
            return it
        }

        // allowMirror=false on the streaming path: resolveFfmpegInputPath can
        // decide to mirror the entire source to cache, and doing that here would
        // put a multi-gigabyte copy in front of the first byte sent to the
        // renderer -- the same reason resolveSourceDurationMs uses the cheap
        // form. A mirror some other call already paid for is still used.
        // HOWEVER, if the container is unstreamable (AVI/WMV/etc.), we MUST mirror
        // because the probe is guaranteed to fail over a pipe.
        val mustMirror = allowMirror || isUnstreamable(item)
        val input = if (mustMirror) {
            resolveFfmpegInputPath(item)
        } else {
            alreadyLocalInputPath(item)?.let { ResolvedInput(it, null) } ?: ResolvedInput("pipe:0", item.uri)
        }
        val piped = input.isPiped

        // Order by what the input can actually do. Over a pipe, ffprobe is
        // forward-only and cannot reach a keyframe index that isn't near the
        // front -- which is precisely the non-faststart case that put the item
        // on the pipe path to begin with -- while MediaExtractor opens item.uri
        // through ContentResolver, seeks freely, and reads the container's own
        // sync-sample table. For a real local path ffprobe leads, for its much
        // wider container coverage. This function's own doc comment reached that
        // conclusion long before the code order agreed with it.
        val timestamps = if (piped) {
            probeKeyframeTimestampsMsViaMediaExtractor(item, budgetMs)
                ?: probeKeyframeTimestampsMsViaFfprobe(item, input, budgetMs)
        } else {
            probeKeyframeTimestampsMsViaFfprobe(item, input, budgetMs)
                ?: probeKeyframeTimestampsMsViaMediaExtractor(item, budgetMs)
        }

        if (timestamps != null) {
            keyframeIndex[cacheKey] = timestamps
        } else {
            Log.w(TAG, "probeKeyframeTimestampsMs: '${item.name}' -- neither probe found keyframes within ${budgetMs}ms (input=${input.path}); computeChunkBoundaries will fall back to fixed-time chunks")
        }
        return timestamps
    }

    /** The ffprobe half of [probeKeyframeTimestampsMs]: one `-skip_frame nokey`
     *  pass over [input]'s resolved path, bounded by [budgetMs]. Null on
     *  failure or an empty parse, so the caller can try the other half. */
    private suspend fun probeKeyframeTimestampsMsViaFfprobe(
        item: MediaCollection.MediaNode.Item,
        input: ResolvedInput,
        budgetMs: Long
    ): List<Long>? {
        val ffprobeStartedAt = System.currentTimeMillis()
        // Use default output format and parse manually with regex to be extremely resilient to variations
        val args = listOf(ffprobePath, "-hide_banner", "-v", "error", "-select_streams", "v:0", "-skip_frame", "nokey", "-show_entries", "frame=pts_time", "-of", "csv=p=0", input.path)
        val result = runProcessCapture(args, timeoutMs = budgetMs, stdinSource = input.stdinSource)
        val ffprobeElapsedMs = System.currentTimeMillis() - ffprobeStartedAt

        if (result.exitCode != 0) {
            Log.w(TAG, "probeKeyframeTimestampsMsViaFfprobe: failed for '${item.name}' after ${ffprobeElapsedMs}ms (exitCode=${result.exitCode}, timedOut=${result.timedOut}, budget=${budgetMs}ms, input=${input.path}) -- see runProcessCapture's stderr-tail log above for detail")
            return null
        }
        
        val parsed = FFPROBE_NUMBER_REGEX.findAll(result.stdout)
            .mapNotNull { it.value.toDoubleOrNull() }
            .map { (it * 1000).toLong() }
            .distinct()
            .sorted()
            .toList()

        if (parsed.isEmpty()) {
            Log.w(TAG, "probeKeyframeTimestampsMsViaFfprobe: exited OK for '${item.name}' in ${ffprobeElapsedMs}ms but parsed zero keyframes (input=${input.path}, stdout length=${result.stdout.length}) -- Stdout head: ${result.stdout.take(100)}")
            return null
        }
        Log.d(TAG, "probeKeyframeTimestampsMsViaFfprobe: found ${parsed.size} keyframe(s) for '${item.name}' in ${ffprobeElapsedMs}ms, first=${parsed.first()}ms last=${parsed.last()}ms")
        return parsed
    }

    /**
     * Same job as the ffprobe pass in [probeKeyframeTimestampsMs] --
     * keyframe presentation timestamps for [item]'s video track, in ms --
     * via android.media.MediaExtractor instead of shelling out to ffprobe.
     * Second choice for a real local path, where ffprobe's much wider
     * container/codec coverage wins and this has no test coverage to match
     * it. *First* choice for a `pipe:0` input, because it has two things
     * ffprobe over a pipe doesn't:
     *  - It opens [item.uri] directly (MediaExtractor#setDataSource(Context,
     *    Uri, Map) goes through ContentResolver, in this process) and gets
     *    real random access to it -- seeking freely -- rather than a
     *    forward-only pipe. A container with its keyframe index at the end
     *    (non-faststart MP4, e.g.) is exactly where ffprobe's pipe:0 pass
     *    fails and this doesn't.
     *  - Where the container has a proper sync-sample index, seeking
     *    MediaExtractor with SEEK_TO_NEXT_SYNC jumps straight between
     *    keyframes using that index, rather than reading every frame
     *    header -- cheap even without ffprobe's own optimizations.
     *
     * Walks sync samples on the first video track from t=0, each time
     * seeking just past the last one found, until a seek makes no forward
     * progress (no more sync samples) or [KEYFRAME_EXTRACTOR_MAX_SAMPLES]
     * is hit. Bounded by the lower of [KEYFRAME_EXTRACTOR_TIMEOUT_MS] and
     * the caller's [budgetMs] -- best effort only: that guards how long the
     * *caller* waits, but a genuinely wedged native call inside
     * MediaExtractor (a hung content provider, say) can still keep the
     * underlying IO thread blocked past it, same as [runProcessCapture]
     * can't fully guarantee a stuck ffmpeg child releases its thread the
     * instant its own timeout fires.
     *
     * Returns null if there's no video track, the source can't be opened,
     * zero sync samples are found, or the probe times out.
     */
    private suspend fun probeKeyframeTimestampsMsViaMediaExtractor(
        item: MediaCollection.MediaNode.Item,
        budgetMs: Long = KEYFRAME_EXTRACTOR_TIMEOUT_MS
    ): List<Long>? {
        val startedAt = System.currentTimeMillis()
        val effectiveTimeoutMs = budgetMs.coerceAtMost(KEYFRAME_EXTRACTOR_TIMEOUT_MS)
        val result = withContext(Dispatchers.IO) {
            withTimeoutOrNull(effectiveTimeoutMs) {
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, item.uri, null)

                    // Find the best video track (largest resolution, avoiding thumbnails)
                    var bestTrack = -1
                    var maxPixels = -1
                    for (i in 0 until extractor.trackCount) {
                        val format = extractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                        if (mime.startsWith("video/")) {
                            // Avoid common thumbnail codecs if possible
                            if (mime.contains("mjpeg") || mime.contains("image")) continue
                            
                            val w = format.getInteger(MediaFormat.KEY_WIDTH, 0)
                            val h = format.getInteger(MediaFormat.KEY_HEIGHT, 0)
                            val pixels = w * h
                            if (pixels > maxPixels) {
                                maxPixels = pixels
                                bestTrack = i
                            }
                        }
                    }
                    
                    // Fallback to first video track if no "good" track found
                    if (bestTrack == -1) {
                        bestTrack = (0 until extractor.trackCount).firstOrNull { i ->
                            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
                        } ?: -1
                    }

                    if (bestTrack == -1) {
                        Log.w(TAG, "probeKeyframeTimestampsMsViaMediaExtractor: '${item.name}' -- no video track found among ${extractor.trackCount} track(s)")
                        return@withTimeoutOrNull null
                    }
                    extractor.selectTrack(bestTrack)
                    Log.d(TAG, "probeKeyframeTimestampsMsViaMediaExtractor: '${item.name}' -- selected track $bestTrack (${extractor.getTrackFormat(bestTrack).getString(MediaFormat.KEY_MIME)})")

                    val timestampsUs = mutableListOf<Long>()
                    extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                    var lastSampleUs = -1L
                    var consecutiveStalls = 0
                    
                    while (timestampsUs.size < KEYFRAME_EXTRACTOR_MAX_SAMPLES) {
                        val sampleUs = extractor.sampleTime
                        if (sampleUs < 0) break // end of stream
                        
                        val isSync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
                        if (isSync && sampleUs > lastSampleUs) {
                            timestampsUs.add(sampleUs)
                            lastSampleUs = sampleUs
                            consecutiveStalls = 0
                        }
                        
                        // Attempt to jump to the next sync sample
                        extractor.seekTo(lastSampleUs + 1, MediaExtractor.SEEK_TO_NEXT_SYNC)
                        
                        val newSampleUs = extractor.sampleTime
                        if (newSampleUs <= lastSampleUs || newSampleUs < 0) {
                            // Jump failed or end reached. Try manual advance to find the next sync point.
                            var foundNext = false
                            // Don't advance too many frames manually, it's too slow.
                            // 1000 frames is ~30-40 seconds of content, a reasonable GOP limit.
                            for (i in 0 until 1000) {
                                if (!extractor.advance()) break
                                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                                    foundNext = true
                                    break
                                }
                            }
                            if (!foundNext) {
                                // If still no progress, try one last aggressive seek to where we think the next GOP might be
                                consecutiveStalls++
                                if (consecutiveStalls > 5) break // Really stuck
                                
                                val skipTarget = lastSampleUs + 5_000_000 // +5s
                                extractor.seekTo(skipTarget, MediaExtractor.SEEK_TO_NEXT_SYNC)
                                if (extractor.sampleTime <= lastSampleUs) break
                            }
                        }
                    }
                    if (timestampsUs.size >= KEYFRAME_EXTRACTOR_MAX_SAMPLES) {
                        Log.w(TAG, "probeKeyframeTimestampsMsViaMediaExtractor: '${item.name}' -- hit the $KEYFRAME_EXTRACTOR_MAX_SAMPLES-sample cap, stopping early")
                    }
                    if (timestampsUs.isEmpty()) {
                        Log.w(TAG, "probeKeyframeTimestampsMsViaMediaExtractor: '${item.name}' -- video track $bestTrack selected but zero sync samples found")
                        return@withTimeoutOrNull null
                    }

                    timestampsUs.map { it / 1000 }.sorted()
                } catch (e: Exception) {
                    Log.w(TAG, "probeKeyframeTimestampsMsViaMediaExtractor: '${item.name}' failed: ${e.message}")
                    null
                } finally {
                    try { extractor.release() } catch (e: Exception) {}
                }
            }
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        Log.d(TAG, "probeKeyframeTimestampsMsViaMediaExtractor: '${item.name}' -- finished after ${elapsedMs}ms, ${result?.size ?: 0} keyframe(s) (null=${result == null}, budget=${effectiveTimeoutMs}ms, possibleTimeout=${result == null && elapsedMs >= effectiveTimeoutMs})")
        if (result != null && result.isNotEmpty()) {
            Log.d(TAG, "probeKeyframeTimestampsMsViaMediaExtractor: '${item.name}' -- first=${result.first()}ms last=${result.last()}ms")
        }
        return result
    }

    /**
     * One chunk's `[startMs, startMs + durationMs)` window, plus whether
     * [startMs] is a *verified* real source keyframe rather than merely the
     * nominal target the snap loop below was aiming for.
     *
     * `true` for a range start of 0ms (the first frame of any valid
     * container is a keyframe by construction, independent of whether the
     * probe below happened to succeed) and for every cut point that came
     * directly out of the probed keyframe list. `false` for every boundary
     * produced by the fixed-time fallback, and for a nonzero range start
     * that isn't independently confirmed to be a keyframe.
     *
     * [transcodeViaSafePath] uses this to tell Media3 the start position is
     * already a valid random-access point
     * (`ClippingConfiguration.setStartsAtKeyFrame`) only when that claim is
     * actually true -- asserting it for an unverified position risks
     * skipping decoding Media3 genuinely needs, which is worse than not
     * asserting it at all.
     */
    private data class ChunkBoundary(val startMs: Long, val durationMs: Long, val startsAtVerifiedKeyframe: Boolean)

    /**
     * Chunk boundaries for [rangeStartMs, rangeStartMs + totalDurationMs),
     * each snapped to the nearest actual source keyframe at or after its
     * nominal target -- the same rule ffmpeg's own segment muxer uses
     * without forced keyframes. A boundary that falls mid-GOP has no
     * natural cut point: the previous chunk's independent re-encode and the
     * next chunk's independent re-encode each decide, on their own, what
     * "the frame at time T" means, with no guarantee they agree -- risking
     * a duplicated or dropped frame, and a small audio discontinuity, at
     * every chunk boundary. Snapping both sides of a cut to the same real
     * keyframe timestamp removes the *ambiguity* in what the cut point
     * should be. Chunks won't be exactly [chunkTargetMs] as a result --
     * that's expected, not a bug.
     *
     * Removing the ambiguity in what the cut point *should* be is not the
     * same claim as either chunk's independent decoder actually *landing*
     * on that exact frame -- confirmed by direct testing against this exact
     * algorithm (software decode, ffmpeg's own accurate-seek, snapped
     * keyframes in, zero dropped or duplicated frames out against a
     * frame-indexed reference), but that test exercises the demuxer/seek
     * math this function controls, not `-hwaccel mediacodec` decode or
     * Media3 Transformer's own clip-boundary frame selection -- both real
     * decoders this file hands the *result* to, on hardware this test
     * cannot reach. Each strategy now verifies its own landing rather than
     * trusting it: see the `-copyts` verification in
     * [transcodeViaHardwareFfmpeg] and [startsAtVerifiedKeyframe] /
     * `setStartsAtKeyFrame` in [transcodeViaSafePath] (the latter directly
     * targets a confirmed, if since-fixed, Transformer bug in exactly this
     * area -- androidx/media#829).
     *
     * Falls back to fixed-time slicing if the keyframe probe fails or
     * returns nothing usable in range, rather than blocking on it -- every
     * boundary from that fallback carries `startsAtVerifiedKeyframe =
     * false` (barring rangeStartMs 0), since a fixed-time cut is exactly
     * the mid-GOP, no-natural-cut-point case two paragraphs up describes.
     */
    private suspend fun computeChunkBoundaries(
        item: MediaCollection.MediaNode.Item,
        chunkTargetMs: Long,
        rangeStartMs: Long,
        rangeDurationMs: Long?,
        keyframeBudgetMs: Long = KEYFRAME_PROBE_TIMEOUT_MS,
        allowMirror: Boolean = true
    ): List<ChunkBoundary> {
        val totalDurationMs = rangeDurationMs ?: (resolveSourceDurationMs(item) - rangeStartMs)
        val rangeEndMs = rangeStartMs + totalDurationMs

        val probeStartedAt = System.currentTimeMillis()
        val rawKeyframes = probeKeyframeTimestampsMs(item, keyframeBudgetMs, allowMirror)
        val probeElapsedMs = System.currentTimeMillis() - probeStartedAt
        Log.d(TAG, "computeChunkBoundaries: '${item.name}' -- keyframe probe took ${probeElapsedMs}ms, returned ${rawKeyframes?.size ?: 0} timestamp(s) total (null=${rawKeyframes == null}, uri=${item.uri}, scheme=${item.uri.scheme})")

        val keyframes = rawKeyframes?.filter { it in rangeStartMs..rangeEndMs }
        if (rawKeyframes != null) {
            Log.d(TAG, "computeChunkBoundaries: '${item.name}' -- ${keyframes?.size ?: 0}/${rawKeyframes.size} keyframe(s) fall within requested range [$rangeStartMs, $rangeEndMs)")
        }

        // True independent of whether the probe above succeeded: the first
        // frame of any valid container is a keyframe. A nonzero
        // rangeStartMs is verified only if the probe both succeeded and
        // happens to have found this exact timestamp.
        val rangeStartIsKeyframe = rangeStartMs == 0L || keyframes?.contains(rangeStartMs) == true

        if (keyframes.isNullOrEmpty()) {
            val reason = when {
                rawKeyframes == null -> "both the ffprobe and MediaExtractor keyframe probes failed, found nothing, or timed out (see probeKeyframeTimestampsMs/probeKeyframeTimestampsMsViaMediaExtractor logs above for detail)"
                rawKeyframes.isEmpty() -> "keyframe probe ran but returned zero timestamps"
                else -> "keyframe probe returned ${rawKeyframes.size} timestamp(s) (first=${rawKeyframes.first()}ms, last=${rawKeyframes.last()}ms) but none fall in the requested range [$rangeStartMs, $rangeEndMs) -- check rangeStartMs/item.durationMs for a mismatch"
            }
            Log.w(TAG, "computeChunkBoundaries: '${item.name}' falling back to fixed-time chunks after ${probeElapsedMs}ms -- $reason")
            val boundaries = mutableListOf<ChunkBoundary>()
            var t = rangeStartMs
            while (t < rangeEndMs) {
                val dur = min(chunkTargetMs, rangeEndMs - t)
                boundaries.add(ChunkBoundary(t, dur, startsAtVerifiedKeyframe = t == rangeStartMs && rangeStartIsKeyframe))
                t += dur
            }
            Log.d(TAG, "computeChunkBoundaries: '${item.name}' -- ${boundaries.size} fixed-time chunk(s) of up to ${chunkTargetMs}ms each")
            return boundaries
        }

        val cutPoints = mutableListOf(rangeStartMs)
        var nextTarget = rangeStartMs + chunkTargetMs
        while (nextTarget < rangeEndMs) {
            val snapped = keyframes.firstOrNull { it >= nextTarget } ?: rangeEndMs
            if (snapped <= cutPoints.last()) {
                Log.d(TAG, "computeChunkBoundaries: '${item.name}' -- no further usable keyframe at/after target ${nextTarget}ms (last cut point was ${cutPoints.last()}ms), stopping snap loop early with ${cutPoints.size} cut point(s) so far")
                break // no further usable keyframe before the end
            }
            cutPoints.add(snapped)
            nextTarget = snapped + chunkTargetMs
        }
        if (cutPoints.last() < rangeEndMs) {
            Log.d(TAG, "computeChunkBoundaries: '${item.name}' -- appending final cut point at rangeEndMs=${rangeEndMs}ms (last keyframe-snapped cut was ${cutPoints.last()}ms, ${rangeEndMs - cutPoints.last()}ms short)")
            cutPoints.add(rangeEndMs)
        }

        // Every cutPoints entry from index 1 on came directly out of
        // `keyframes` (the loop above only ever adds a `snapped` value or
        // breaks) so is verified by construction; only index 0 depends on
        // rangeStartIsKeyframe. cutPoints' own last entry is only ever used
        // below as an END (zipWithNext pairs it that way), so the
        // occasional synthetic rangeEndMs appended just above never gets
        // mistaken for a verified START.
        val boundaries = cutPoints.zipWithNext { start, end -> start to (end - start) }
            .mapIndexed { i, (start, dur) -> ChunkBoundary(start, dur, startsAtVerifiedKeyframe = if (i == 0) rangeStartIsKeyframe else true) }
        Log.d(TAG, "computeChunkBoundaries: '${item.name}' -- ${boundaries.size} keyframe-snapped chunk(s): ${boundaries.joinToString { "[${it.startMs}+${it.durationMs}]" }}")
        return boundaries
    }

    /**
     * Fragmented MP4 chunks each start with their own ftyp+moov(empty)
     * header, which only the first chunk appended to a stream may keep --
     * a second header mid-stream would confuse most demuxers. Scans
     * forward past top-level ISO-BMFF boxes (4-byte big-endian size +
     * 4-byte ASCII type, or an 8-byte extended size when size==1) and
     * returns the byte offset of the first 'moof' box. Falls back to 0
     * (send the whole chunk as-is) if no moof is found -- shouldn't happen
     * for genuinely fragmented ffmpeg output, but avoids silently
     * corrupting the stream if it ever does.
     *
     * The extended-size branch reads 8 bytes past the `pos + 8 <= len` guard
     * above it, with nothing checking those 8 bytes are actually there -- a
     * chunk truncated right after such a header throws EOFException, which
     * used to propagate out of the responder and kill the connection with a
     * stack trace instead of just sending from offset 0 like any other
     * unparseable case here already does.
     */
    private fun findFirstMoofOffset(file: File): Long = try {
        RandomAccessFile(file, "r").use { raf ->
            var pos = 0L
            val len = raf.length()
            while (pos + 8 <= len) {
                raf.seek(pos)
                val header = ByteArray(8)
                raf.readFully(header)
                var boxSize = ((header[0].toLong() and 0xFF) shl 24) or ((header[1].toLong() and 0xFF) shl 16) or
                        ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
                val type = String(header, 4, 4, Charsets.US_ASCII)
                if (boxSize == 1L) {
                    val ext = ByteArray(8)
                    raf.readFully(ext)
                    boxSize = 0L
                    for (b in ext) boxSize = (boxSize shl 8) or (b.toLong() and 0xFF)
                }
                if (type == "moof") return pos
                if (boxSize <= 0L) break
                pos += boxSize
            }
        }
        0L
    } catch (e: EOFException) {
        Log.w(TAG, "findFirstMoofOffset: truncated file ${file.absolutePath} -- sending from offset 0")
        0L
    }

    /**
     * Seam-stutter fix (streaming investigation, see TranscoderController_Seam_Investigation.md).
     *
     * Every streamed chunk is a separate ffmpeg/Transformer invocation. Input-side
     * seeking (-ss before -i for ffmpeg; Media3's ClippingConfiguration for the
     * safe path) resets each invocation's own output timeline to start near zero
     * -- this is ffmpeg's own documented behavior, confirmed directly: a synthetic
     * two-chunk test showed chunk 1's internal pts_time running 0.0-7.98s, byte
     * for byte identical to chunk 0's, despite representing entirely different
     * source content. findFirstMoofOffset strips a later chunk's ftyp/moov and
     * sends its raw moof/mdat bytes straight onto the HTTP response with nothing
     * correcting the fragment's own tfdt (track fragment decode time) -- so the
     * composite stream's declared timeline snaps backward at every chunk seam.
     * Verified with ffprobe: an unpatched two-chunk composite reported half its
     * true duration and a single non-monotonic timestamp landing exactly on the
     * seam. -output_ts_offset was tried first and does not fix this -- confirmed
     * by comparing the raw tfdt bytes with and without it: identical either way.
     *
     * The functions below patch each fragment's tfdt.baseMediaDecodeTime in place
     * so it reflects the chunk's real position in the streaming session's
     * timeline instead. Verified end to end: a patched two-chunk composite's
     * reported duration and packet timeline are now indistinguishable from a
     * single unchunked reference encode of the same content (both report
     * duration=16.023281s; 0 non-increasing transitions; the seam itself reads
     * ...7.983281, 8.0, 8.063281... -- continuous).
     *
     * verifyChunkDuration/verifyOutputResolution/verifyMuxedAvSync can't see this
     * defect and tightening them can't catch it either: every chunk file is
     * completely correct in isolation (the right duration, the right resolution,
     * audio and video in sync with each other) -- it's the composite byte stream
     * this section assembles afterward where the problem lives, and none of
     * those checks ever look at that.
     */

    /** Reads one 4-byte-size-or-extended + 4-byte-type ISO-BMFF box header at
     *  [pos], the same layout findFirstMoofOffset already parses. Returns
     *  (type, boxSize) or null if there isn't room for a full header (or a
     *  full extended-size header) at [pos] -- callers treat that the same way
     *  findFirstMoofOffset treats a parse failure: stop, don't guess. */
    private fun readBoxHeader(raf: RandomAccessFile, pos: Long): Pair<String, Long>? {
        val len = raf.length()
        if (pos + 8 > len) return null
        raf.seek(pos)
        val head = ByteArray(8)
        raf.readFully(head)
        var boxSize = ((head[0].toLong() and 0xFF) shl 24) or ((head[1].toLong() and 0xFF) shl 16) or
                ((head[2].toLong() and 0xFF) shl 8) or (head[3].toLong() and 0xFF)
        val type = String(head, 4, 4, Charsets.US_ASCII)
        if (boxSize == 1L) {
            if (pos + 16 > len) return null
            val ext = ByteArray(8)
            raf.readFully(ext)
            boxSize = 0L
            for (b in ext) boxSize = (boxSize shl 8) or (b.toLong() and 0xFF)
        }
        return type to boxSize
    }

    private fun RandomAccessFile.readIntBE(): Int {
        val b = ByteArray(4); readFully(b)
        return ((b[0].toInt() and 0xFF) shl 24) or ((b[1].toInt() and 0xFF) shl 16) or
                ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
    }

    private fun RandomAccessFile.readLongBE(): Long {
        val b = ByteArray(8); readFully(b)
        var v = 0L
        for (x in b) v = (v shl 8) or (x.toLong() and 0xFF)
        return v
    }

    private fun RandomAccessFile.writeIntBE(v: Int) {
        write(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()))
    }

    private fun RandomAccessFile.writeLongBE(v: Long) {
        val b = ByteArray(8)
        for (i in 0 until 8) b[i] = (v ushr (8 * (7 - i))).toByte()
        write(b)
    }

    /** Walks [start, end) for a direct child box of type [targetType]. Not
     *  recursive -- callers pass the exact byte range of the parent box's own
     *  content, one nesting level at a time (moov -> trak -> tkhd/mdia,
     *  mdia -> mdhd), same as the rest of this file's box parsing. */
    private fun findTopLevelBox(raf: RandomAccessFile, start: Long, end: Long, targetType: String): Pair<Long, Long>? {
        var pos = start
        while (pos + 8 <= end) {
            val (type, size) = readBoxHeader(raf, pos) ?: return null
            if (type == targetType) return pos to (pos + size)
            if (size <= 0L) return null
            pos += size
        }
        return null
    }

    /** Reads each track's ID (tkhd) and timescale (mdia/mdhd) from [file]'s
     *  still-present moov. Must run against chunk 0 specifically, once per
     *  streaming session, before its file gets overwritten by chunk 1 -- chunk
     *  0 is the only chunk sent with its moov intact (see the streaming loop
     *  in transcodeMediaFile). Every later chunk in the same session shares
     *  these track IDs and timescales: same item, same codec, same settings,
     *  for the life of the session. Returns an empty map on anything
     *  unparseable rather than throwing -- patchFragmentTimestamps treats an
     *  empty map as "leave this chunk alone", the same fail-open posture
     *  findFirstMoofOffset already takes on a chunk it can't parse. */
    private fun readTrackTimescales(file: File): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val moov = findTopLevelBox(raf, 0L, len, "moov") ?: return emptyMap()
                var pos = moov.first + 8
                while (pos + 8 <= moov.second) {
                    val (type, size) = readBoxHeader(raf, pos) ?: break
                    if (type == "trak") {
                        val trakEnd = pos + size
                        val tkhd = findTopLevelBox(raf, pos + 8, trakEnd, "tkhd")
                        val mdia = findTopLevelBox(raf, pos + 8, trakEnd, "mdia")
                        if (tkhd != null && mdia != null) {
                            raf.seek(tkhd.first + 8)
                            val tkhdVersion = raf.readByte().toInt()
                            raf.seek(tkhd.first + 8 + (if (tkhdVersion == 1) 20 else 12))
                            val trackId = raf.readIntBE()
                            val mdhd = findTopLevelBox(raf, mdia.first + 8, mdia.second, "mdhd")
                            if (mdhd != null) {
                                raf.seek(mdhd.first + 8)
                                val mdhdVersion = raf.readByte().toInt()
                                raf.seek(mdhd.first + 8 + (if (mdhdVersion == 1) 20 else 12))
                                val timescale = raf.readIntBE()
                                result[trackId] = timescale
                            }
                        }
                    }
                    if (size <= 0L) break
                    pos += size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readTrackTimescales: couldn't parse ${file.absolutePath}'s moov (${e.message}) -- later chunks in this session will be sent unpatched")
            return emptyMap()
        }
        return result
    }

    private fun patchTrafAt(raf: RandomAccessFile, start: Long, end: Long, trackTimescales: Map<Int, Int>, sessionOffsetMs: Long) {
        var trackId: Int? = null
        var tfdtValuePos = -1L
        var tfdtIs64Bit = false
        var pos = start + 8
        while (pos + 8 <= end) {
            val (type, size) = readBoxHeader(raf, pos) ?: break
            when (type) {
                "tfhd" -> {
                    raf.seek(pos + 8 + 4) // box header(8) + version/flags(4) -> track_ID
                    trackId = raf.readIntBE()
                }
                "tfdt" -> {
                    raf.seek(pos + 8)
                    tfdtIs64Bit = raf.readByte().toInt() == 1
                    tfdtValuePos = pos + 8 + 4 // version/flags(4) -> baseMediaDecodeTime
                }
            }
            if (size <= 0L) break
            pos += size
        }
        val timescale = trackTimescales[trackId] ?: return
        if (tfdtValuePos < 0) return
        val offsetTicks = Math.round(sessionOffsetMs / 1000.0 * timescale)
        if (tfdtIs64Bit) {
            raf.seek(tfdtValuePos)
            val cur = raf.readLongBE()
            raf.seek(tfdtValuePos)
            raf.writeLongBE(cur + offsetTicks)
        } else {
            raf.seek(tfdtValuePos)
            val cur = raf.readIntBE().toLong() and 0xFFFFFFFFL
            raf.seek(tfdtValuePos)
            raf.writeIntBE(((cur + offsetTicks) and 0xFFFFFFFFL).toInt())
        }
    }

    private fun patchMoofAt(raf: RandomAccessFile, start: Long, end: Long, trackTimescales: Map<Int, Int>, sessionOffsetMs: Long) {
        var pos = start + 8
        while (pos + 8 <= end) {
            val (type, size) = readBoxHeader(raf, pos) ?: break
            if (type == "traf") patchTrafAt(raf, pos, pos + size, trackTimescales, sessionOffsetMs)
            if (size <= 0L) break
            pos += size
        }
    }

    /**
     * Rewrites every fragment's tfdt.baseMediaDecodeTime in [file], in place,
     * by [sessionOffsetMs]. Called for every streamed chunk after the first.
     *
     * [sessionOffsetMs] must be time elapsed since this streaming session's
     * FIRST chunk -- chunkStartMs minus chunkBoundaries.first().startMs, NOT the
     * chunk's raw position in the source file. Chunk 0 always starts a fresh
     * HTTP response at its own local zero (that's correct, and it's why it's
     * sent unpatched); every later chunk must stay relative to *that*, not to
     * the source file's own absolute timeline. Verified concretely why this
     * distinction matters, not just asserted: patching with the source-absolute
     * timestamp instead (i.e. as if sessionOffsetMs == chunkStartMs) was tested
     * against a session starting at a 40s seek -- it produces a stream whose
     * declared duration is right for the wrong reason (56s instead of 16s) and
     * leaves a 40-second gap with zero frames in it between chunk 0's end and
     * chunk 1's start, which no real player would recover from cleanly. Using
     * the session-relative offset on the same input gives the correct 16s,
     * fully continuous, matching an unchunked reference exactly.
     *
     * No-ops if [trackTimescales] is empty (readTrackTimescales couldn't parse
     * chunk 0's moov) or [sessionOffsetMs] is 0 (shouldn't happen for index>0,
     * since chunk boundaries strictly increase, but costs nothing to guard) --
     * both leave the chunk exactly as it would have been sent before this fix,
     * rather than risking a worse, half-patched fragment.
     */
    private fun patchFragmentTimestamps(file: File, trackTimescales: Map<Int, Int>, sessionOffsetMs: Long) {
        if (trackTimescales.isEmpty() || sessionOffsetMs == 0L) return
        try {
            RandomAccessFile(file, "rw").use { raf ->
                var pos = 0L
                val len = raf.length()
                while (pos + 8 <= len) {
                    val (type, size) = readBoxHeader(raf, pos) ?: break
                    if (type == "moof") patchMoofAt(raf, pos, pos + size, trackTimescales, sessionOffsetMs)
                    if (size <= 0L) break
                    pos += size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "patchFragmentTimestamps: failed on ${file.absolutePath} (${e.message}) -- this chunk will play with a seam discontinuity")
        }
    }

    private data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean = false)

    /** What counts as "wrote the output" everywhere in this file: a clean
     *  exit plus a non-empty file on disk. One source of truth so a call site
     *  can't drift -- forgetting the `length() > 0` half, say -- the way five
     *  independently-typed copies of this check could. */
    private fun ProcessOutput.succeededWriting(file: File): Boolean =
        exitCode == 0 && file.exists() && file.length() > 0

    /**
     * verbose and logStderrLive used to gate three things -- a "Starting
     * ProcessBuilder" log, a live per-line stderr echo, and an "OK after Xms"
     * success log -- but nothing anywhere ever passed verbose=true, so all
     * three were permanently dead code regardless of logStderrLive (F1).
     *
     * Resolved as: delete both flags: the per-line stderr echo added nothing
     * a caller couldn't already get from the bounded failure-tail
     * ([TailBuffer]/B4c already exists for exactly that), and the
     * "Starting ProcessBuilder" log was never independently useful. The
     * "OK after Xms" timing log is the one exception -- C2's own acceptance
     * criterion needs it to actually fire to measure the exitValue-polling
     * fix -- so rather than deleting it with the rest, it's unconditional now:
     * the *stated* default (log failures with their stderr tail always; log
     * success timing always; never echo stderr live) instead of an accident
     * of two flags nobody set.
     */
    private suspend fun runProcessCapture(args: List<String>, timeoutMs: Long = PROBE_TIMEOUT_MS, stdinSource: Uri? = null): ProcessOutput {
        val startedAt = System.currentTimeMillis()
        val process = try { ProcessBuilder(args).start() } catch (e: Exception) {
            Log.e(TAG, "runProcessCapture: failed to start process: ${e.message} | args=${args.joinToString(" ")}", e)
            return ProcessOutput(-1, "", "Failed to start process: ${e.message}")
        }
        // So release() has something to reach even if it's called while this
        // process is still running -- see release()'s own comment.
        liveProcesses.add(process)
        val lock = Any()
        val stderrTail = TailBuffer()
        val stdoutBuilder = StringBuilder()
        // Scope per call, threads shared process-wide: constructing a
        // CoroutineScope is free, constructing a thread pool is not. See
        // processIoDispatcher.
        val detachedIoScope = CoroutineScope(processIoDispatcher + SupervisorJob())
        // stderr is bounded to a tail -- at `-v debug` this accumulated megabytes
        // that the failure log below only ever read the last few thousand
        // characters of anyway. stdout stays unbounded: it's parsed by every
        // probeXxx function, so losing its head would corrupt a result, not just
        // a log line.
        val stderrJob = detachedIoScope.launch { try { process.errorStream.bufferedReader().use { r -> r.forEachLine { synchronized(lock) { stderrTail.add(it) } } } } catch (e: Exception) {} }
        val stdoutJob = detachedIoScope.launch { try { process.inputStream.bufferedReader().use { r -> r.forEachLine { synchronized(lock) { stdoutBuilder.appendLine(it) } } } } catch (e: Exception) {} }
        // Holds the stdin source's fd while a copy is in flight, so a stuck process
        // (below) can force it closed instead of leaving a ContentResolver read
        // blocked indefinitely on a child nothing is waiting on anymore. This is
        // load-bearing now for every content:// item (see resolveFfmpegInputPath),
        // not just an occasional fallback, hence logging bytes/timing instead of
        // swallowing failures silently.
        val stdinPfdRef = AtomicReference<ParcelFileDescriptor?>(null)
        stdinSource?.let { uri ->
            detachedIoScope.launch {
                val copyStartedAt = System.currentTimeMillis()
                var copiedBytes = 0L
                try {
                    val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                    if (pfd == null) {
                        Log.w(TAG, "runProcessCapture: openFileDescriptor returned null for stdin source $uri")
                        return@launch
                    }
                    stdinPfdRef.set(pfd)
                    pfd.use {
                        FileInputStream(pfd.fileDescriptor).use { input ->
                            process.outputStream.use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val n = input.read(buffer)
                                    if (n < 0) break
                                    output.write(buffer, 0, n)
                                    copiedBytes += n
                                }
                            }
                        }
                    }
                    Log.d(TAG, "runProcessCapture: stdin copy from $uri finished, $copiedBytes byte(s) in ${System.currentTimeMillis() - copyStartedAt}ms")
                } catch (e: Exception) {
                    Log.w(TAG, "runProcessCapture: stdin copy from $uri failed after $copiedBytes byte(s) / ${System.currentTimeMillis() - copyStartedAt}ms: ${e.message}")
                } finally {
                    stdinPfdRef.set(null)
                }
            }
        }
        // Block on the child rather than polling exitValue() every 100ms. The
        // poll cost 50ms of pure added latency per process on average (100ms
        // worst case) -- ~6 processes per safe-path chunk -- and it occupied a
        // Dispatchers.Default worker for the entire process lifetime, i.e. 25%
        // of the default pool per concurrent process on a 4-core device, for
        // minutes at a time on a long encode.
        //
        // Dispatchers.IO, not Default: this is a blocking call and belongs on
        // the elastic pool. runInterruptible is what makes it cancellable --
        // waitFor() responds to Thread.interrupt(), so the timeout below (and
        // an outer cancellation) actually unblocks the thread instead of
        // stranding it. Process.waitFor(long, TimeUnit) would express the
        // timeout directly, but it is API 26+ and this module still ships a
        // legacy flavour; withTimeoutOrNull has no such floor and the same
        // semantics.
        val exitCode: Int? = withTimeoutOrNull(timeoutMs) {
            runInterruptible(Dispatchers.IO) { process.waitFor() }
        }
        if (exitCode == null) {
            CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                try {
                    stdinPfdRef.get()?.let { try { it.close() } catch (e: Exception) {} }
                    process.destroyForcibly(); process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
                } catch (e: Exception) {}
            }
        } //} else { try { process.inputStream.close(); process.errorStream.close(); process.outputStream.close() } catch (e: Exception) {} }
        //In runProcessCapture, the child process (like ffprobe) runs, and the main coroutine waits for it using process.waitFor().
        // Meanwhile, asynchronous coroutines (stdoutJob and stderrJob) read lines from the streams into memory buffers.
        //When ffprobe finishes executing and returns successfully, process.waitFor() unblocks immediately.
        // However, the asynchronous reader coroutines haven't necessarily finished pulling the remaining buffered data out of the stream yet.
        //This immediately slammed the stream shut on the active stdoutJob reader. The reader threw a swallowed IOException,
        // missing the remaining buffered lines (the trailing structure of the JSON output). This explains why the JSON ended abruptly
        // at the "format": { "duration": ... block and missed its closing braces, resulting in the Unterminated object error during parsing.



        liveProcesses.remove(process)
        // The readers used to be killed the instant the process exited, with the
        // buffers read on the very next line -- anything still in the pipe, or
        // buffered inside BufferedReader between readLine() calls, was lost.
        // Every probeXxx function parses stdout, so a truncated read there
        // silently became a valid-but-wrong number, which failed a verification
        // check for the wrong reason. Bounded, not unbounded: on the timeout
        // path above the process was just force-killed, and its streams may
        // never reach EOF, so an unbounded join would hang exactly where this
        // file has hung before.
        withTimeoutOrNull(READER_DRAIN_GRACE_MS) { joinAll(stderrJob, stdoutJob) }
        // Cancel the scope, but don't shut anything down: the threads belong to
        // processIoDispatcher now and are about to serve the next spawn. The
        // stdin copier's blocking read was never interruptible anyway -- what
        // actually stops it is the pfd force-close and stream closes above, same
        // as before.
        detachedIoScope.cancel()
        var stderrDropped = 0
        val finalStderr = synchronized(lock) { stderrDropped = stderrTail.droppedLines(); stderrTail.toString() }
        val finalStdout = synchronized(lock) { stdoutBuilder.toString() }
        val out = if (exitCode == null) ProcessOutput(-1, finalStdout, finalStderr, true) else ProcessOutput(exitCode, finalStdout, finalStderr)
        val elapsedMs = System.currentTimeMillis() - startedAt
        if (out.exitCode != 0 || out.timedOut) {
            Log.e(TAG, "runProcessCapture: FAILED after ${elapsedMs}ms (exitCode=${out.exitCode}, timedOut=${out.timedOut}, timeoutMs=$timeoutMs, ${diagnosticSnapshot()})")
            Log.e(TAG, "runProcessCapture: args=${args.joinToString(" ")}")
            Log.e(TAG, "runProcessCapture: stderr tail=${out.stderr}${if (stderrDropped > 0) " ($stderrDropped earlier line(s) dropped)" else ""}")
        } else {
            Log.d(TAG, "runProcessCapture: OK after ${elapsedMs}ms")
        }
        return out
    }

    private fun updateSeekBarPosition(position: String, duration: String) {}
    /**
     * Releases everything this controller is holding.
     *
     * Before this, "release" only cancelled [scope]. That left child ffmpeg
     * processes running -- a process outlives the coroutine that spawned it,
     * and cancelling a scope says nothing to a separate OS process -- in-flight
     * source mirrors still copying, and every mirror already on disk never
     * deleted.
     *
     * Order matters: processes and mirrors are torn down explicitly first,
     * `scope.cancel()` last, so a mirror job still in flight is dropped from
     * the registry (and its file cleaned up if it had already published one)
     * before the coroutine backing it is cancelled out from under it -- rather
     * than racing the two.
     */
    fun release() {
        stopPlayingLocal()
        liveProcesses.toList().forEach { runCatching { it.destroyForcibly() } }
        mirrorJobs.keys.toList().forEach { releaseSourceMirror(it) }
        scope.cancel()
        // Last, after the processes whose pumps run on it are already dead and
        // the scope that owns them is cancelled -- closing it first would reject
        // an in-flight dispatch and turn a clean teardown into a logged failure.
        processIoDispatcher.close()
    }
    private fun stopPlayingLocal() { repo.setSeekBarDuration("00:00:00"); repo.setSeekBarPosition("00:00:00") }
}
