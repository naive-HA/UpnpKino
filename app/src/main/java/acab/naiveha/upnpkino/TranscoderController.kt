package acab.naiveha.upnpkino

import android.content.ContentValues
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.app.ActivityManager
import android.os.PowerManager
import android.os.BatteryManager
import android.os.Debug
import android.content.IntentFilter
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
import java.util.concurrent.locks.LockSupport
import android.provider.MediaStore
import android.provider.DocumentsContract
import android.util.Log
import android.system.Os
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
import androidx.media3.transformer.InAppFragmentedMp4Muxer
import androidx.media3.common.Metadata
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Muxer
import java.nio.ByteBuffer
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class TranscoderController(val context: Context, val upnpService: UpnpService) {
    companion object {
        private const val TAG = "TranscoderController"
        private const val RETRY_DELAY_MS = 500L

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

        /** Floor [resolveFfmpegInputPath] insists stay free, after accounting
         *  for the source's own size, before it will mirror a content:// item
         *  to local cache. A round, conservative number rather than derived
         *  from anything -- big enough that a mirror can't be the thing that
         *  tips the device into a real low-storage state, small enough to
         *  still allow the fast-seek path on any reasonably-provisioned
         *  device. Tune freely; nothing else depends on the exact value. */
        private const val MIN_FREE_SPACE_AFTER_MIRROR_MB = 500L

        /** How far the finished file's duration may fall short of what was
         *  requested before [verifyAssembledDuration] rejects it by default. Was
         *  2000ms -- loose enough that a real AV-sync problem (a few hundred ms)
         *  would sail through as long as it stayed under 2 full seconds. Tightened
         *  to just above realistic frame/AAC-block quantization slack, so a
         *  genuine drift actually gets caught. (The end of a range is the one place
         *  allowed more: see [FINAL_CHUNK_TOLERANCE_MS].) */
        private const val CHUNK_DURATION_TOLERANCE_MS = 250L

        /** How far the finished file may *overrun* the requested duration before
         *  it's rejected. Deliberately looser than [CHUNK_DURATION_TOLERANCE_MS],
         *  and the asymmetry is the point: shortfall is the truncation bug the
         *  check exists for, while overrun has legitimate sources -- an AAC encode
         *  ending on a whole packet, an encoder's padding, clip-end rounding. A
         *  false rejection is maximally expensive (it throws away a finished
         *  transcode), so the loose side stays loose. Don't "fix" this back to
         *  symmetric. */
        private const val CHUNK_OVERRUN_TOLERANCE_MS = 1000L

        /** Length of the fragments the one whole-range audio encode is written in
         *  (see [transcodeAudioTrack]), in microseconds. Audio can't ride
         *  `frag_keyframe` the way video does -- every AAC packet is a sync
         *  sample, so that would cut a fragment per 21ms packet and spend a
         *  few percent of the file on moof headers -- hence an explicit
         *  duration. 2s costs ~2% container overhead and is also the
         *  granularity at which the readable prefix of a still-encoding file
         *  grows, which is what the merge works from. */
        private const val AUDIO_FRAGMENT_DURATION_US = 2_000_000L

        /** How far the finished file's duration may sit from the requested
         *  total before [verifyAssembledDuration] warns (it never fails on this
         *  -- see its own thresholds). A clean merge lands within a few ms: the
         *  final audio packet keeps its full 1024-sample duration. 100ms is a
         *  couple of frames, so it is well clear of that and still flags real
         *  drift. */
        private const val ASSEMBLED_DRIFT_WARN_MS = 100L

        /** Shortfall tolerance for the end of the range. The range end comes from
         *  library-scan metadata; when the real file is slightly shorter, an encode
         *  that runs to the real end legitimately comes back short of it.
         *  Rejecting that would throw away a finished transcode, so the end of the
         *  range gets slack the rest of the file doesn't: a leg's coverage and the
         *  finished file's duration are both judged against it.
         *  [resolveSourceDurationMs] removes the cause for local sources; this
         *  covers the piped ones it can't. */
        private const val FINAL_CHUNK_TOLERANCE_MS = 2000L

        /** How long [FragmentAssembler] waits, once both encodes have started,
         *  before it writes anything. The encoders get a head start, so the
         *  first portion handed to the output (or to a renderer) is a real run
         *  of media rather than a fragment or two; after that, each portion is
         *  simply whatever both legs have finished since the one before.
         *
         *  Wall-clock, not media time: a slow encoder must not be able to hold
         *  the first byte back indefinitely. It ends early when both legs have
         *  finished or one has failed, so a short clip is not made to wait out
         *  the rest of it. */
        private const val ASSEMBLER_STARTUP_WAIT_MS = 10_000L

        /** How much encoded media a PIPED leg may have queued, unserved, before
         *  its reader stops reading. A pipe holds about 64 KB, so from then on
         *  the encoder blocks in its own write call: this is what paces the
         *  encoders to whoever consumes the stream, and what bounds both the
         *  memory held (about one GOP-sized fragment per two seconds queued)
         *  and the work wasted when a viewer pauses or leaves. Should comfortably
         *  exceed [ASSEMBLER_STARTUP_WAIT_MS], or the head start is wasted. */
        private const val STREAM_LEAD_MS = 20_000L

        /** Permissions of a leg's FIFO: owner read/write (octal 0600). */
        private const val FIFO_MODE = 384

        /** Fragment length of the Safe Path's whole-range export. Media3's
         *  fragmented muxer cuts a fragment once this much has elapsed (at a
         *  sync sample, for video), so it is also the granularity at which the
         *  readable prefix of the still-running export grows. Same figure as
         *  the audio leg's ([AUDIO_FRAGMENT_DURATION_US]). */
        private const val SAFE_PATH_FRAGMENT_DURATION_MS = 2_000L

        /** No growth in the video leg's output for this long and its encode is
         *  killed as wedged. A fragmented writer grows in whole fragments (about
         *  one GOP), not byte by byte, so this has to sit well above the encode
         *  time of a GOP on the slowest device worth supporting (the 30 s this
         *  replaces was sized for a file that grew continuously). Without
         *  per-chunk budgets this and [Budget.forVideo] are all that stop a hung
         *  encoder: the first within a minute and a half, the second only after
         *  hours. Time a piped leg spends held back on purpose does not count.
         *  Nor, on the hw_ffmpeg plan, does the copy of a source nobody has
         *  mirrored yet, or a piped source being read through to a late start
         *  ([resolveInputFor]): its encode reads the source through ffmpeg, and
         *  both leave it silent without being stuck. */
        private const val VIDEO_LEG_STALL_ABORT_MS = 90_000L

        /** The same for the audio leg: no growth in its output for this long and
         *  its encode is killed as wedged. Without it the only bound on a hung
         *  audio encode was [Budget.forAudio], which is sized to the whole range
         *  (minutes for a film) -- and a hung audio leg holds the merge, and
         *  through it everything the video leg encodes meanwhile, for all of it.
         *
         *  The figure is the video leg's, for a different reason. A fragment here
         *  is 2s of audio, cut in about a tenth of a second on a Pixel 7, so
         *  encode time is not what it has to clear. What can legitimately keep
         *  the output from growing is the source: ffmpeg's start-up, a slow
         *  content provider behind a pipe, an index-less container scanned to a
         *  late start. Slow must not be mistaken for broken, so it stays generous.
         *
         *  The one silence too long for any fixed figure is a piped source being
         *  read through to a late start offset (see [resolveFfmpegInputPath]);
         *  that run is not watched at all ([resolveInputFor]), and neither is the
         *  copy of a source nobody has mirrored yet. Time a piped OUTPUT leg
         *  spends held back on purpose does not count. */
        private const val AUDIO_LEG_STALL_ABORT_MS = 90_000L

        /** How long a Transformer export may go without growing before the
         *  threads it is made of are dumped to the log -- once per export, well
         *  inside [VIDEO_LEG_STALL_ABORT_MS] so the dump is taken while the
         *  export is still stuck, not after it has been torn down. */
        private const val STALL_DUMP_AFTER_MS = 20_000L

        /** The fastest a FILE video leg's Transformer export may run, in seconds
         *  of media per second of wall clock; 0 lifts the limit (the default).
         *  Enforced by [ExportThrottle], in the muxer wrapper.
         *
         *  Do not use. Tried at 2x on a Pixel 7 on 2026-10-12, it did not slow
         *  the decoder, which went on running ~10x ahead; the pipeline shed
         *  most of its frames instead (a 30 fps source came out at 2-9 fps),
         *  and the 8-bit export still wedged, on a cool device at a sixth of
         *  the load. Blocking the muxer is not backpressure on this pipeline.
         *  Kept so the experiment can be repeated. */
        private const val SAFE_PATH_FILE_MAX_SPEED = 0.0

        /** How many frames the Safe Path's Transformer may have in the video
         *  encoder at once; 0 leaves Media3's default, which is no limit.
         *
         *  Why: the 2026-10-12 telemetry shows the process holding ~4,000 file
         *  descriptors, and the system ~2.3 GB poorer, within six seconds of an
         *  export starting -- the same for 8-bit and 10-bit sources -- and the
         *  contiguous-memory pool (CmaFree) at 0 of 988 MB from then on. The
         *  decoder runs ~10x ahead of the encoder and nothing pushes back, so
         *  frames pile up in the encoder's queue until memory runs out. In all
         *  three stalls so far the decoder's input stopped first, and the GL
         *  thread was then found in glClear, most likely waiting for a buffer
         *  to be allocated for the encoder's input surface -- which an
         *  exhausted pool cannot give it. A bound on frames in flight is the
         *  direct test: if this is the cause, fds and CmaFree stay flat and
         *  the stall goes.
         *
         *  Applied by reflection ([applyMaxFramesInEncoder]): the setter is
         *  Media3's experimental Transformer.Builder.experimentalSetMaxFramesInEncoder
         *  and the version in use may not have it. The log says which. 16 is
         *  generous -- an encoder's own lookahead is a handful of frames. */
        private const val SAFE_PATH_MAX_FRAMES_IN_ENCODER = 16

        /** The telemetry reads one file per dma-buf descriptor to total their
         *  size; that is done on every this-many'th sample. */
        private const val EXPORT_TELEMETRY_DEEP_EVERY = 5

        /** How often [launchExportTelemetry] writes its line. */
        private const val EXPORT_TELEMETRY_INTERVAL_MS = 2_000L

        /** How far the first audible audio sample may sit from the first video
         *  frame in the finished file before [verifyAssembledStart] warns:
         *  about one frame. Both legs start at zero by construction, so more
         *  than this means the video leg's first frame is not at the start of
         *  its range (an encoder's B-frame reorder delay, say). Rejection is at
         *  [AV_SYNC_TOLERANCE_MS]. */
        private const val AV_START_OFFSET_WARN_MS = 40L

        /** A gap between consecutive video frames counts as a held frame when it
         *  exceeds this many times the typical frame interval. */
        private const val VIDEO_CONTINUITY_GAP_FACTOR = 1.5

        /** Packets read from the start of each stream by [verifyAssembledStart]:
         *  enough to see past a B-frame reorder or an AAC priming packet to the
         *  first one a viewer would see or hear. */
        private const val START_PROBE_PACKETS = 16

        /** Dimension alignment some MediaCodec encoders require. This is where
         *  the long-standing 1088 comes from -- 1080 rounded up to a multiple of
         *  16 -- and why [videoScaleFilter] pads rather than stretches to reach
         *  it: the alignment is real (this file's history includes encoder-init
         *  failures at unaligned heights), the distortion it used to cause was
         *  not intended. */
        private const val ENCODER_DIMENSION_ALIGNMENT_PX = 16

        /** GOP target in seconds, converted against the real source frame rate
         *  by [encoderTuningArgs]. The old fixed `-g 24` was a 1-second GOP at
         *  the equally fixed 24fps; ~2s is a bitrate win at the same quality, and
         *  it is also the length of a video fragment: the video leg cuts one per
         *  keyframe, so this is how coarsely its output becomes readable. */
        private const val GOP_TARGET_SECONDS = 2.0

        /** Frame rate assumed when the real one can't be determined (piped
         *  source, failed probe), used only for GOP sizing. */
        private const val ASSUMED_FRAME_RATE_FPS = 30.0

        /** Output frame-rate ceiling. Nothing is ever resampled *up* to this and
         *  content at or below it passes through untouched -- it exists only to
         *  cap genuinely high-rate sources. */
        private const val MAX_OUTPUT_FRAME_RATE_FPS = 60

        /** Max allowed difference between the finished file's video-track and
         *  audio-track durations, and between where its audio and its video
         *  start. Catches drift between the two independently produced legs that
         *  a container-level duration check can miss -- see [verifyMuxedAvSync]
         *  and [verifyAssembledStart]. */
        private const val AV_SYNC_TOLERANCE_MS = 200L

        /** How far over the requested output box an encoder may legitimately land
         *  before [verifyOutputResolution] calls it a real miss: one alignment
         *  step. Encoders are free to align dimensions up (and Media3's
         *  DefaultEncoderFactory fallback will adjust a requested resolution to
         *  something the device actually supports), so a few pixels over is
         *  expected and only worth logging. A rejection here fails the whole
         *  transcode, so the check only fires on a miss big
         *  enough to mean the scale request was ignored outright. */
        private const val ENCODER_ALIGNMENT_SLACK_PX = ENCODER_DIMENSION_ALIGNMENT_PX

        const val DEFAULT_TIMEOUT_S = 60L

        /** Kept for callers that still refer to it; the controller no longer
         *  encodes in chunks, so nothing in here reads it. */
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

        /** How long the *streaming* path will wait for the hardware probe before
         *  proceeding on the safe path. A DLNA renderer typically abandons a
         *  connection that produces no bytes within 5-15s, so an unbounded wait on
         *  a 10s probe would cause the very timeout it exists to avoid. The
         *  file-save path has no such deadline and awaits properly. */
        private const val HW_PROBE_STREAM_WAIT_MS = 2_000L

        /** Metadata probes read a container header; they don't scale with content
         *  length. The keyframe probe does walk the file, hence its own budget. */
        private const val PROBE_TIMEOUT_MS = 15_000L
        private const val KEYFRAME_PROBE_TIMEOUT_MS = 60_000L

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
         * deliberately generous for that reason; [VIDEO_LEG_STALL_ABORT_MS] and
         * [AUDIO_LEG_STALL_ABORT_MS] are what distinguish the two.
         *
         * Self-contained by design: no reference to the outer constants, so the
         * numbers here can be tuned without reading the rest of the file.
         */
        private object Budget {
            private const val MIN_CHUNK_BUDGET_MS = 30_000L
            private const val MIN_COPY_BUDGET_MS = 30_000L
            private const val MAX_COPY_BUDGET_MS = 900_000L
            private const val MIN_AUDIO_BUDGET_MS = 60_000L
            private const val MAX_AUDIO_BUDGET_MS = 3_600_000L

            /** Slowest real-time factor worth waiting for, by pipeline. */
            private fun rtFactor(strategy: String, resolution: Constants.Transcoder.Resolution): Double = when {
                strategy == "hw_ffmpeg" -> 4.0
                resolution == Constants.Transcoder.Resolution.UHD_4K -> 12.0
                else -> 8.0
            }

            /** The one video encode that spans the whole range. There is no
             *  per-chunk budget any more, so this is only the outer bound for a
             *  wedged process; [VIDEO_LEG_STALL_ABORT_MS] is what catches a hung
             *  one quickly. */
            fun forVideo(durationMs: Long, strategy: String, resolution: Constants.Transcoder.Resolution): Long =
                (durationMs * rtFactor(strategy, resolution)).toLong().coerceAtLeast(MIN_CHUNK_BUDGET_MS)

            /** Copy-only work (concat, remux): budget by bytes at a pessimistic
             *  throughput floor rather than by a fixed number that a 20GB input
             *  and a 200MB input are held to equally. */
            fun forStreamCopy(bytes: Long, floorBytesPerSec: Long = 8L * 1024 * 1024): Long =
                (bytes / floorBytesPerSec * 1000).coerceIn(MIN_COPY_BUDGET_MS, MAX_COPY_BUDGET_MS)

            /** The one audio encode that spans the whole range (see
             *  [transcodeAudioTrack]). Cheap per second of content, but it still
             *  decodes the source. It runs alongside the video encode for as long
             *  as that takes, not in a burst of its own, so the bar is "no slower
             *  than half real time" -- far below what any device manages, but
             *  generous enough that a long film on a loaded phone isn't killed
             *  for being slow. The cap is there for a wedged process, not a slow
             *  one; [AUDIO_LEG_STALL_ABORT_MS] is what catches a hung one quickly. */
            fun forAudio(durationMs: Long): Long =
                (durationMs / 2).coerceIn(MIN_AUDIO_BUDGET_MS, MAX_AUDIO_BUDGET_MS)
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

    /** Home for everything else this controller writes: the encoders' leg
     *  files and pipes, the merged file, the pre-publish output -- in-progress work that
     *  is small but expensive to redo, not disposable the way a source mirror
     *  is. `cacheDir` is reclaimable by the OS under storage pressure with no
     *  warning and no regard for whether a write is in flight; losing
     *  a nearly finished encode moments before the final remux runs loses the
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
     *  Reset by any successful video leg. Counted and logged by the Safe Path: once
     *  the device's global codec budget is gone, every attempt fails identically
     *  until the process restarts. (A failed run is no longer retried on the same
     *  encoder, so nothing reads it to stop one.) */
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

    private val MediaCollection.MediaNode.Item.defaultVideoTrack: MediaCollection.VideoTrack?
        get() = videoTracks.firstOrNull()

    private val MediaCollection.MediaNode.Item.defaultAudioTrack: MediaCollection.AudioTrack?
        get() = audioTracks.firstOrNull()

    private val MediaCollection.MediaNode.Item.defaultVideoCodec: Constants.Transcoder.VideoCodec
        get() = defaultVideoTrack?.codec ?: Constants.Transcoder.VideoCodec.UNKNOWN

    private val MediaCollection.MediaNode.Item.defaultResolution: String
        get() = defaultVideoTrack?.resolution ?: "0x0"

    private val MediaCollection.MediaNode.Item.defaultAudioCodec: Constants.Transcoder.AudioCodec
        get() = defaultAudioTrack?.codec ?: Constants.Transcoder.AudioCodec.UNKNOWN

    private val MediaCollection.MediaNode.Item.defaultChannelCount: Int?
        get() = defaultAudioTrack?.channelCount

    fun selectTranscodingStrategy(item: MediaCollection.MediaNode.Item): String? {
        val vCodec = item.defaultVideoCodec
        val aCodec = item.defaultAudioCodec

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
        val frameHeight = item.defaultResolution.split("x").getOrNull(1)?.toIntOrNull() ?: 0
        val resolution = Constants.Transcoder.Resolution.entries.sortedBy { it.maxHeight }.find { it.maxHeight >= frameHeight }
        if (item.defaultVideoCodec == Constants.Transcoder.VideoCodec.UNKNOWN || frameHeight == 0 || resolution == null){
            return null
        }
        val dtsAudio = item.defaultAudioCodec == Constants.Transcoder.AudioCodec.DCA ||item.audioTracks.any { it.codec == Constants.Transcoder.AudioCodec.DCA }
        val audioStrategy = when{
            item.defaultAudioCodec == Constants.Transcoder.AudioCodec.AC3 -> "ffmpeg"
            item.defaultAudioCodec == Constants.Transcoder.AudioCodec.AAC -> "ffmpeg"
            dtsAudio -> "media3"
            else -> null
        }
        var videoStrategy = when{
            resolution == Constants.Transcoder.Resolution.UHD_4K -> "media3"
            item.defaultVideoCodec == Constants.Transcoder.VideoCodec.MPEG4 ||
                    item.defaultVideoCodec == Constants.Transcoder.VideoCodec.WMV3 ||
                    item.name.lowercase().endsWith(".avi") -> "media3"
            item.defaultVideoCodec == Constants.Transcoder.VideoCodec.HEVC -> "ffmpeg"
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
            break
            // Smart detection of unstreamable containers
            if (!item.isFastStart) {
                Log.d(TAG, "UNIFIED TEST: Skipping '${item.name}' (moov atom at end of file, piping not possible)")
                continue
            }
            val targetVideoCodec = if (item.defaultVideoCodec == Constants.Transcoder.VideoCodec.H264) {
                Constants.Transcoder.VideoCodec.HEVC
            } else {
                Constants.Transcoder.VideoCodec.H264
            }
            Log.d(TAG, "UNIFIED TEST: Entering transcodeMediaFile")
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
        item.audioTracks.getOrNull(audioTrackIndex)?.channelCount ?: item.defaultChannelCount

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
     * Deliberately pure -- no logging here. This is called more than once per
     * transcode (see [transcodeAudioTrack]) as well as once per request (see
     * [transcodeMediaFile]), so a downmix warning placed here would fire
     * repeatedly instead of once per transcode.
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
     * output dimensions deterministic for a given box, which also keeps what the
     * hardware and Transformer encoders produce the same size. When no
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
        val parts = item.defaultResolution.split("x")
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
     * A run that falls back from hardware to the Safe Path starts again on the
     * other encoder, and the two have to agree on the output size for what the
     * viewer gets not to depend on which of them ran.
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
                val widthHeight = EncoderUtil.getSupportedResolutionRanges(encoderInfo, mimeType)
                widthHeight.first.contains(box.width) && widthHeight.second.contains(box.height) &&
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
        return HwProbeOutcome.FAIL






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
        // the fragmented-MP4 fragments that the assembler merges and
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
        // is the one call to it per transcode request. The legs and
        // transcodeAudioTrack call it again, with the same result; logging
        // there used to turn one warning into many (D23r.3).
        resolveTrackChannelCount(item, audioTrackIndex)?.let { channelCount ->
            if (channelCount !in setOf(1, 2, 6)) {
                Log.w(TAG, "Downmixing '${item.name}' audio track $audioTrackIndex from ${channelCount}ch to stereo (unsupported layout)")
            }
        }

        val isLegacyVideo = item.defaultVideoCodec == Constants.Transcoder.VideoCodec.MPEG4 ||
                item.defaultVideoCodec == Constants.Transcoder.VideoCodec.WMV3 ||
                item.name.lowercase().endsWith(".avi")
        val is4k = resolution == Constants.Transcoder.Resolution.UHD_4K
        val hasDtsAudio = item.defaultAudioCodec == Constants.Transcoder.AudioCodec.DCA ||
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
            is4k -> "hybrid" // (a whole-file Media3 path was tried here once)
            isLegacyVideo -> "hybrid"
            hasDtsAudio -> "hybrid"
            else -> "hybrid"
        }

        Log.d(TAG, "Transcode Start: '${item.name}' (startTime=${startTimeMs}ms)")
        Log.d(TAG, "  > Strategy:   $strategy (mode=${if (responder != null) "stream" else "file"})")
        Log.d(TAG, "  > Source V:   ${item.defaultVideoCodec.name} (${item.defaultResolution})")
        Log.d(TAG, "  > Source A:   ${item.defaultAudioCodec.name} (track $audioTrackIndex, ${resolveTrackChannelCount(item, audioTrackIndex)}ch; item default ${item.defaultChannelCount}ch)")
        Log.d(TAG, "  > Target V:   ${targetVideoCodec.name} (${encodeTargetSize(item, resolution) ?: resolveOutputBox(item, resolution)} within ${resolution.name} ${tierBox(resolution)} @ ${videoBitrateKbps}kbps)")
        Log.d(TAG, "  > Target A:   ${targetAudioCodec.name} (${audioLayout.channelCount}ch @ ${audioBitrateBps / 1000}kbps)")
        if (responder != null) {
            val status = if (startTimeMs > 0) true else null
            if (subtitleTrackIndex != null) {
                Log.w(TAG, "transcodeMediaFile: subtitleTrackIndex=$subtitleTrackIndex requested for streaming but not yet supported -- ignoring")
            }

            // One id per stream session. A DLNA renderer routinely opens two or
            // three concurrent connections against one item (probe, playback,
            // seek); every pipe and temp file a session makes carries its id, so
            // two sessions never share one.
            val sessionId = UUID.randomUUID().toString().take(8)
            val totalDurationMs = resolveSourceDurationMs(item) - startTimeMs
            if (totalDurationMs <= 0L) {
                Log.e(TAG, "transcodeMediaFile: nothing to stream for '${item.name}' (startTimeMs=$startTimeMs)")
                return false
            }

            // Streaming is momentary and saves nothing: the video and audio encoders write
            // fragmented mp4 into pipes, the assembler merges what they produce and serves it,
            // and whatever has been served is dropped. A client that reads slowly (or pauses)
            // holds the encoders back through the pipes rather than letting them run ahead.
            var streamedOk = false
            responder.invoke(status, "video/mp4") { out ->
                for ((attempt, plan) in legPlans(strategy, streaming = true).withIndex()) {
                    currentCoroutineContext().ensureActive()
                    if (attempt > 0) Log.w(TAG, "transcodeMediaFile: '${item.name}' -- nothing had been sent yet; starting again on [$plan]")
                    val run = runLegsOnce(
                        item, plan, targetVideoCodec, resolution, videoBitrateKbps,
                        targetAudioCodec, audioBitrateBps, audioTrackIndex,
                        startTimeMs, null, totalDurationMs, sessionId, out
                    )
                    val assembly = run.assembly
                    if (assembly.ok) {
                        Log.d(TAG, "transcodeMediaFile: streamed '${item.name}' via [$plan] -- ${assembly.fragmentsWritten} fragments, ${assembly.bytesWritten / 1024} KiB, video ${assembly.videoEndMs}ms, audio ${assembly.audioEndMs}ms, start offset ${assembly.startOffsetMs}ms")
                        streamedOk = true
                        break
                    }
                    Log.e(TAG, "transcodeMediaFile: '${item.name}' [$plan] failed -- ${assembly.reason} (video reached ${assembly.videoEndMs}ms, audio ${assembly.audioEndMs}ms, of ${totalDurationMs}ms; ${assembly.bytesWritten} bytes sent)")
                    // Once anything has gone out the stream cannot be restarted on another plan (the
                    // client already holds this one's moov), and a failing sink means nobody is listening.
                    if (assembly.bytesWritten > 0L || run.sinkFailed) break
                }
            }
            return streamedOk
        } else {
            // File mode: Save to source directory.
            val outputFile = File(durableWorkDir, "transcoding_${item.id}.mp4")
            if (outputFile.exists()) outputFile.delete()

            // When saving to file, we generally transcode the whole file from start to finish
            // unless a specific seek was intended. For the unified test, we use startTimeMs=0.
            val success = transcodeSinglePass(item, targetVideoCodec, resolution, videoBitrateKbps, targetAudioCodec, audioBitrateBps, audioTrackIndex, outputFile, startTimeMs, null, strategy)
            var finalSuccess = success
            try {
                if (success) {
                    Log.d(TAG, "transcodeMediaFile: '${item.name}' transcoded -- exporting ${outputFile.length() / 1024} KiB to the source directory")
                    val published = publishToSourceDirectory(item, outputFile)
                    if (published != null) {
                        Log.d(TAG, "Transcode finished. File preserved at: $published")
                        // Diagnostic only, and after the export so that nothing it does -- or
                        // how long it takes on a long film -- can hold the export up.
                        try {
                            scanVideoContinuity(outputFile, item.name)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "transcodeMediaFile: continuity scan of '${item.name}' failed (ignored): ${e.message}")
                        }
                    } else {
                        Log.e(TAG, "Transcode succeeded but publishing failed.")
                        finalSuccess = false
                    }
                } else {
                    Log.e(TAG, "transcodeMediaFile: '${item.name}' did not transcode successfully -- nothing to export")
                }
            } finally {
                if (outputFile.exists()) outputFile.delete()
            }
            return finalSuccess
        }
    }

    /** `h:mm:ss.mmm` -- the form a player's seek bar shows, so a logged
     *  timestamp can be typed straight into one. */
    private fun formatTimestamp(ms: Long): String {
        val sign = if (ms < 0) "-" else ""
        val a = Math.abs(ms)
        return String.format(Locale.US, "%s%d:%02d:%02d.%03d", sign, a / 3_600_000, (a / 60_000) % 60, (a / 1000) % 60, a % 1000)
    }

    /**
     * Confirms the finished [outputFile] actually covers [expectedTotalMs].
     *
     * The encoders' own legs are judged for coverage as they finish (a leg that
     * ends early is failed there -- Media3 Transformer's video-only export can
     * complete cleanly, no exception, having produced only a fraction of the
     * requested duration, androidx/media#758). This is the check on the file
     * that actually ships, after the merge and the final remux: the one place
     * a lost or duplicated stretch introduced by either would show.
     *
     * Also logs the video and audio tracks' own durations next to the
     * container's, because the container reports whichever is longer: a total
     * that's off says something is wrong, the per-track split says which track
     * it's on.
     *
     * A rejection here has no cheap retry (the whole transcode would have to be
     * redone), so it is meant as a loud signal to investigate rather than
     * something to build automatic retry logic around. Overrun is judged
     * against a wider bar than shortfall ([CHUNK_OVERRUN_TOLERANCE_MS] against
     * [CHUNK_DURATION_TOLERANCE_MS]) on purpose: a false rejection throws away
     * a finished transcode, and overrun has legitimate sources (encoder
     * padding, clip-end rounding). Don't collapse the two into one symmetric
     * tolerance.
     */
    private suspend fun verifyAssembledDuration(
        outputFile: File,
        expectedTotalMs: Long,
        itemName: String,
        toleranceMs: Long = CHUNK_DURATION_TOLERANCE_MS,
        overrunToleranceMs: Long = CHUNK_OVERRUN_TOLERANCE_MS
    ): Boolean {
        val probe = probeMedia(outputFile)
        val containerMs = probe?.formatDurationMs
        // The video track says how long the file is, not whichever track is
        // longest: the audio runs a little past the video by construction (an
        // AAC encode ends on a packet boundary), and a measure that followed it
        // could let a short video through.
        val actualMs = probe?.videoDurationMs ?: containerMs
        if (probe == null || actualMs == null) {
            Log.e(TAG, "verifyAssembledDuration: '$itemName' ffprobe could not read duration of assembled ${outputFile.absolutePath}")
            return false
        }
        val videoMs = probe.videoDurationMs
        val audioMs = probe.audioDurationMs
        Log.d(TAG, "verifyAssembledDuration: '$itemName' per-track -- video=${videoMs}ms (delta=${videoMs?.let { it - expectedTotalMs }}ms vs expected) " +
            "audio=${audioMs}ms (delta=${audioMs?.let { it - expectedTotalMs }}ms vs expected) container=${containerMs}ms (delta=${containerMs?.let { it - expectedTotalMs }}ms); judged on ${if (videoMs != null) "video" else "container"}")
        val shortfallMs = expectedTotalMs - actualMs
        val ok = shortfallMs <= toleranceMs && -shortfallMs <= overrunToleranceMs
        Log.d(TAG, "verifyAssembledDuration: '$itemName' expected=${expectedTotalMs}ms actual=${actualMs}ms shortfall=${shortfallMs}ms ${if (ok) "OK" else "MISMATCH"}")
        // The failure thresholds above are deliberately loose (a false rejection
        // throws away a finished transcode), so there's a second, quieter tier
        // that never fails anything: anything past a couple of frames on the
        // finished file is worth seeing, however small.
        if (ok && Math.abs(shortfallMs) > ASSEMBLED_DRIFT_WARN_MS) {
            Log.w(TAG, "verifyAssembledDuration: '$itemName' within tolerance but ${Math.abs(shortfallMs)}ms ${if (shortfallMs > 0) "short" else "long"} -- more than the ~${ASSEMBLED_DRIFT_WARN_MS}ms a clean assembly lands within (the trailing audio packet accounts for under 10)")
        }
        if (!ok) {
            Log.e(TAG, "verifyAssembledDuration: '$itemName' assembled output is ${kotlin.math.abs(shortfallMs)}ms ${if (shortfallMs > 0) "SHORTER" else "LONGER"} than the range that was asked for -- the merge or the final remux lost or duplicated content")
        }
        return ok
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
        Log.d(TAG, "Publish: '${item.name}' -> '$targetName' (${tempFile.length() / 1024} KiB; source is ${item.uri.scheme}:, ${item.uri})")

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
                if (target.length() != tempFile.length()) {
                    Log.e(TAG, "Publish: ${target.absolutePath} holds ${target.length()} bytes, not the ${tempFile.length()} that were copied -- the export is incomplete (left in place for inspection)")
                    return null
                }
                Log.d(TAG, "Publish: copied to ${target.absolutePath} (size=${target.length()}, verified)")
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
            Log.d(TAG, "Publish: copying ${tempFile.length() / 1024} KiB into $parentUri as $targetName")
            val opened = context.contentResolver.openOutputStream(newDoc.uri)?.use { out ->
                tempFile.inputStream().use { input -> input.copyTo(out) }
                true
            } ?: false
            if (!opened) {
                Log.e(TAG, "Publish: could not open output stream for ${newDoc.uri}")
                return null
            }
            // Confirm what arrived, not just that the copy returned: a provider that accepted
            // the stream and kept less is exactly the "finished, but nothing there" failure.
            val written = newDoc.length()
            if (written != tempFile.length()) {
                Log.e(TAG, "Publish: ${newDoc.uri} holds $written bytes, not the ${tempFile.length()} that were copied -- the export is incomplete (left in place for inspection)")
                return null
            }
            Log.d(TAG, "Publish: copied to ${newDoc.uri} (size=$written, verified)")
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
            // Confirm what arrived, from the file itself rather than the (lagging) MediaStore row.
            var written = -1L
            try {
                resolver.openFileDescriptor(itemUri, "r")?.use { pfd -> written = pfd.statSize }
            } catch (e: Exception) {
                Log.w(TAG, "publishViaMediaStore: could not read back the size of $itemUri: ${e.message}")
            }
            if (written >= 0L && written != tempFile.length()) {
                Log.e(TAG, "publishViaMediaStore: $itemUri holds $written bytes, not the ${tempFile.length()} that were copied -- the export is incomplete (left in place for inspection)")
                return null
            }
            Log.d(TAG, "publishViaMediaStore: copied to $itemUri (relativePath=$relativePath, size=${tempFile.length()}, destination reports ${if (written >= 0L) written.toString() else "unknown"})")
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
     * relying on `-ss` before `-i` for fast input-seeking -- a streaming
     * seek, see [transcodeVideoLegViaHardwareFfmpeg] and [transcodeAudioTrack]
     * -- has to decode-and-discard from byte 0 up to its start offset on every
     * call, which for a late seek can mean minutes of wasted decode just to
     * reach the start point. That is the expected, logged trade-off of a
     * low-space device, not a bug.
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

    /**
     * [resolveFfmpegInputPath] on behalf of [leg], whose encode is about to read the result.
     *
     * Two things the leg's stall clock must not count, because to it they look like an encode
     * that has stopped growing:
     *  - this call. A source nobody has mirrored yet is copied to the cache by it (a stream is the
     *    first to ask), which can take minutes for a large remux.
     *  - a piped source with a late start. It is read through from byte 0 to [startMs] before the
     *    first fragment exists, decoding all of it for video (see [resolveFfmpegInputPath]); for as
     *    long as that takes the leg is silent, and no fixed figure tells it from a hang. That is the
     *    expected trade-off of a low-space device, so such a leg stays unwatched.
     *
     * Done in the leg's own encode, not in [runLegsOnce] ahead of it: the merge's head-start clock
     * starts when it runs, and holding that back by the copy would delay a stream's first byte by
     * as much.
     */
    private suspend fun resolveInputFor(leg: FragmentLeg, item: MediaCollection.MediaNode.Item, startMs: Long): ResolvedInput {
        leg.stallExempt = true
        val input = resolveFfmpegInputPath(item)
        leg.stallExempt = input.isPiped && startMs > 0L
        if (leg.stallExempt) Log.d(TAG, "${leg.label}: piped source read through to ${startMs}ms -- not watched for stalls")
        return input
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

    // =====================================================================
    // The single-pass pipeline.
    //
    // One video encode and one audio encode, each over the whole range and
    // each written as a fragmented mp4, merged into the output by
    // FragmentAssembler while they are still being written. There is no
    // chunking: nothing is cut, re-timed or joined between the encoders and
    // the output, which is what the chunked design could not promise at its
    // seams.
    //
    // Saving to a file: both encoders write FILES that are tailed as they
    // grow. The merge goes to a temporary fragmented file, and one
    // stream-copy remux at the end makes the final progressive file.
    //
    // Streaming: both encoders write PIPES (FIFOs), the merge goes straight
    // to the client, and whatever has been served is dropped. Nothing
    // accumulates on disk, and a slow client holds the encoders back through
    // the pipes instead of letting them run ahead.
    // =====================================================================

    /** Where a leg's encoder writes: a file in the work directory, or a FIFO there when the leg is piped. */
    private fun legPath(item: MediaCollection.MediaNode.Item, sessionId: String, kind: String, pipe: Boolean): File =
        File(durableWorkDir, "${kind}_leg_${item.id}_$sessionId.${if (pipe) "fifo" else "mp4"}")

    private fun createFifo(path: File) {
        path.delete()
        Os.mkfifo(path.absolutePath, FIFO_MODE)
    }

    /** Counts what reaches the sink and remembers whether the sink itself failed -- the one failure no
     *  other plan can fix, since it is the far end (a renderer that went away, a full disk) that is broken. */
    private class CountingSink(private val delegate: OutputStream) : OutputStream() {
        var bytes = 0L
            private set
        var failed = false
            private set

        override fun write(b: Int) { guard { delegate.write(b) }; bytes++ }
        override fun write(b: ByteArray, off: Int, len: Int) { guard { delegate.write(b, off, len) }; bytes += len }
        override fun flush() { guard { delegate.flush() } }
        override fun close() {} // the delegate belongs to whoever opened it

        private inline fun guard(block: () -> Unit) {
            try { block() } catch (e: IOException) { failed = true; throw e }
        }
    }

    private class PipelineRun(
        val assembly: AssemblyResult,
        /** The audio leg's presentation start inside its own media timeline (its encoder-delay packet), in ticks; null without audio. */
        val audioPrimingTicks: Long?,
        /** A write to the sink failed: the far end is gone, and trying again elsewhere would meet the same wall. */
        val sinkFailed: Boolean
    )

    /** One way of running the legs: which encoder makes the video, and whether the legs travel through pipes. */
    private class LegPlan(val videoStrategy: String, val pipes: Boolean) {
        override fun toString() = "$videoStrategy/${if (pipes) "pipe" else "file"}"
    }

    /**
     * The plans to try, in order, for a run of [strategy].
     *
     * A run moves on to the next plan only if the one before it failed before
     * the first byte reached the output -- an encoder that cannot handle the
     * source, or a pipe that cannot be used. Once output exists there is no
     * second plan: a failure then fails the transcode (a half-written run cannot
     * be continued by a different encoder, whose codec configuration would
     * differ, and re-doing hours of work behind the caller's back is not what
     * was asked for).
     *
     * Streaming pipes its legs, and each encoder is tried with pipes first, then
     * with ordinary temp files: where pipes cannot be used (Media3's muxer
     * writing into one is the combination not yet seen working on a device) the
     * stream still plays, with files that grow with the film instead of nothing.
     */
    private fun legPlans(strategy: String, streaming: Boolean): List<LegPlan> {
        val encoders = if (strategy == "hw_ffmpeg") listOf("hw_ffmpeg", "safe") else listOf("safe")
        val plans = ArrayList<LegPlan>()
        for (encoder in encoders) {
            plans += LegPlan(encoder, pipes = streaming)
            if (streaming) plans += LegPlan(encoder, pipes = false)
        }
        return plans
    }

    /**
     * One complete pass of the pipeline over the range starting at
     * [rangeStartMs]: starts the audio leg and a video leg, merges them into
     * [sink] as they are written, and tears both down however it ends. Raw leg
     * files and FIFOs never outlive the call.
     *
     * [limitMs] is an explicit length for the range (null: to the end of the
     * source); [totalDurationMs] is how much is *expected* -- it sizes the
     * budgets and is what a leg's coverage is judged against, so a leg that
     * stops early without any error is caught as truncated.
     */
    private suspend fun runLegsOnce(
        item: MediaCollection.MediaNode.Item,
        plan: LegPlan,
        targetVideoCodec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        videoBitrateKbps: Int,
        targetAudioCodec: Constants.Transcoder.AudioCodec,
        audioBitrateBps: Int,
        audioTrackIndex: Int,
        rangeStartMs: Long,
        limitMs: Long?,
        totalDurationMs: Long,
        sessionId: String,
        sink: OutputStream
    ): PipelineRun {
        val videoPath = legPath(item, sessionId, "video", plan.pipes)
        val audioPath = legPath(item, sessionId, "audio", plan.pipes)
        val counting = CountingSink(sink)
        fun failedRun(reason: String) =
            PipelineRun(AssemblyResult(false, counting.bytes, 0, 0L, null, null, reason), null, counting.failed)
        videoPath.delete()
        audioPath.delete()
        try {
            val hasAudio = itemHasAudio(item, audioTrackIndex)
            if (plan.pipes) {
                createFifo(videoPath)
                if (hasAudio) createFifo(audioPath)
            }
            return coroutineScope {
                val videoLeg = FragmentLeg(FragmentLeg.Kind.VIDEO, "video leg [$plan]", videoPath, plan.pipes)
                val audioLeg = if (hasAudio) FragmentLeg(FragmentLeg.Kind.AUDIO, "audio leg [$plan]", audioPath, plan.pipes) else null
                if (audioLeg == null) Log.d(TAG, "runLegsOnce: '${item.name}' has no audio track -- video only")
                // Media3's fragmented writer lets the video timeline drift; the Safe Path records the
                // timestamps its muxer is handed and the leg re-times itself from them.
                val sampleTimes = SampleTimes()
                if (plan.videoStrategy != "hw_ffmpeg") videoLeg.timeCorrector = FragmentTimeCorrector(sampleTimes, videoLeg.label)
                try {
                    launchLeg(videoLeg, STREAM_LEAD_MS, VIDEO_LEG_STALL_ABORT_MS, totalDurationMs, FINAL_CHUNK_TOLERANCE_MS) {
                        if (plan.videoStrategy == "hw_ffmpeg") {
                            resolveInputFor(videoLeg, item, rangeStartMs) // the callee's own resolve is then instant
                            transcodeVideoLegViaHardwareFfmpeg(
                                item, targetVideoCodec, resolution, videoBitrateKbps, videoLeg,
                                startTimeMs = rangeStartMs, durationMs = limitMs,
                                timeoutMs = Budget.forVideo(totalDurationMs, "hw_ffmpeg", resolution)
                            )
                        } else {
                            transcodeVideoLegViaSafePath(
                                item, targetVideoCodec, resolution, videoLeg, sampleTimes,
                                offsetMs = rangeStartMs, limitDurationMs = limitMs,
                                timeoutMs = Budget.forVideo(totalDurationMs, "hybrid", resolution)
                            )
                        }
                    }
                    if (audioLeg != null) {
                        launchLeg(audioLeg, STREAM_LEAD_MS, AUDIO_LEG_STALL_ABORT_MS, totalDurationMs, FINAL_CHUNK_TOLERANCE_MS) {
                            resolveInputFor(audioLeg, item, rangeStartMs) // the callee's own resolve is then instant
                            transcodeAudioTrack(
                                item, audioPath,
                                durationS = limitMs?.let { it / 1000.0 },
                                startTimeMs = rangeStartMs,
                                encoder = targetAudioCodec.encoderId,
                                audioBitrateBps = audioBitrateBps,
                                audioTrackIndex = audioTrackIndex,
                                timeoutMs = Budget.forAudio(totalDurationMs),
                                written = { audioLeg.outputBytes() }
                            )
                        }
                    }
                    val assembly = FragmentAssembler(videoLeg, audioLeg, item.name, ASSEMBLER_STARTUP_WAIT_MS).run(counting)
                    PipelineRun(assembly, audioLeg?.header?.editStartTicks, counting.failed)
                } finally {
                    // Whatever ended the run -- done, failed, the client leaving -- the encoders stop with it.
                    videoLeg.cancel()
                    audioLeg?.cancel()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "runLegsOnce [$plan]: '${item.name}' threw ${e.javaClass.simpleName}: ${e.message}", e)
            return failedRun("${e.javaClass.simpleName}: ${e.message}")
        } finally {
            videoPath.delete()
            audioPath.delete()
        }
    }

    /**
     * The video leg through ffmpeg's hardware path: ONE encode of the whole
     * range into [leg]'s fragmented mp4 (a file, or a FIFO when streaming).
     *
     * `frag_keyframe+empty_moov` is what makes the output readable while it is
     * still being written -- a moov up front, then a self-contained fragment
     * per GOP -- and `default_base_moof` keeps each fragment's offsets relative
     * to itself so it can be copied elsewhere unchanged. No `-copyts`: the leg
     * is normalised to start at zero (`avoid_negative_ts make_zero`) exactly as
     * the audio leg is, which is what puts the two on one timeline when they
     * are merged. `-flush_packets 1` so a fragment reaches the reader as soon
     * as it is cut.
     */
    private suspend fun transcodeVideoLegViaHardwareFfmpeg(
        item: MediaCollection.MediaNode.Item,
        targetVideo: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        videoBitrateKbps: Int,
        leg: FragmentLeg,
        startTimeMs: Long,
        durationMs: Long?,
        timeoutMs: Long
    ): Boolean {
        val input = resolveFfmpegInputPath(item)
        val sourceMetadata = probeSourceMedia(item, input)
        val outputBox = resolveOutputBox(item, resolution)
        val paddedOutputBox = PixelSize(alignUpForEncoder(outputBox.width), alignUpForEncoder(outputBox.height))

        val args = mutableListOf(
            ffmpegPath, "-y", "-hide_banner", "-hwaccel", "mediacodec",
            "-ndk_codec", "1", // applies to the decoder for -i, below -- see the matching flag after -c:v
            // genpts regenerates missing/broken presentation timestamps (camcorder exports and
            // files already through another lossy transcode); analyzeduration/probesize give the
            // demuxer a bigger window on unusual muxing -- cheap insurance, particularly for the
            // containers isUnstreamable already flags as trickier (AVI/WMV/ASF/TS/MPEG).
            "-fflags", "+genpts",
            "-analyzeduration", "20M", "-probesize", "20M"
        )
        if (startTimeMs > 0) args.addAll(listOf("-ss", (startTimeMs / 1000.0).toString()))
        args.addAll(listOf("-i", input.path))
        args.addAll(listOf(
            // -map_chapters -1: this leg is one track, and an ffmpeg mp4 gets a second (text) track for a
            // source's chapters, whose samples ride in the first fragment -- two trafs in one moof, which
            // the merge rejects (the audio leg met this first; see transcodeAudioTrack).
            "-map", "0:v:0", "-map_chapters", "-1", "-an", "-vf", videoScaleFilter(outputBox, deinterlace = isInterlaced(sourceMetadata)), "-c:v", targetVideo.encoderId,
            "-b:v", "${videoBitrateKbps}k", "-bitrate_mode", "vbr",
            "-ndk_codec", "1" // applies to the encoder for -c:v, above -- not a duplicate of the flag before -i
        ))
        args.addAll(encoderTuningArgs(targetVideo, resolution, sourceMetadata.frameRate))
        args.addAll(colorMetadataArgs(sourceMetadata))
        if (durationMs != null) args.addAll(listOf("-t", (durationMs / 1000.0).toString()))
        args.addAll(listOf(
            "-flush_packets", "1",
            "-movflags", "frag_keyframe+empty_moov+default_base_moof",
            "-avoid_negative_ts", "make_zero", "-max_muxing_queue_size", "1024",
            "-f", "mp4", leg.path.absolutePath
        ))

        Log.d(TAG, "HW ffmpeg: Starting the video leg to ${leg.path.absolutePath} (${if (leg.isPipe) "pipe" else "file"}), input=${input.path}, source=${item.resolution}@${sourceMetadata.frameRate ?: "?"}fps, fit=$outputBox, padded=$paddedOutputBox, budget=${timeoutMs}ms (${diagnosticSnapshot()})")
        val result = runProcessCapture(args, timeoutMs = timeoutMs, stdinSource = input.stdinSource)
        val success = result.exitCode == 0 && leg.outputBytes() > 0
        Log.d(TAG, "HW ffmpeg: Video leg finished success=$success, exitCode=${result.exitCode}, timedOut=${result.timedOut}, bytes=${leg.outputBytes()}, ${diagnosticSnapshot()}")
        if (!success) Log.e(TAG, "HW ffmpeg: video leg stderr: ${result.stderr.takeLast(1500)}")
        return success
    }

    /**
     * Stream-copies the merged fragmented file into a normal progressive mp4
     * (`+faststart`): the same file the chunked path used to end with, so
     * everything downstream -- the library scan, serving with Range requests --
     * sees what it always has. Both tracks are copied untouched, so this is
     * also where the audio's priming declaration is checked to have survived.
     */
    private suspend fun remuxToProgressive(assembledFile: File, outputFile: File, itemName: String, expectPrimingDeclared: Boolean): Boolean {
        val args = listOf(
            ffmpegPath, "-y", "-hide_banner", "-v", "warning",
            "-i", assembledFile.absolutePath,
            "-c", "copy", "-movflags", "+faststart",
            "-f", "mp4", outputFile.absolutePath
        )
        val timeoutMs = Budget.forStreamCopy(assembledFile.length())
        val result = runProcessCapture(args, timeoutMs = timeoutMs)
        val ok = result.succeededWriting(outputFile)
        Log.d(TAG, "remuxToProgressive: '$itemName' final remux ${if (ok) "OK" else "FAILED"} (exitCode=${result.exitCode}, budget=${timeoutMs}ms, size=${outputFile.length()})")
        if (!ok) {
            Log.e(TAG, "remuxToProgressive: '$itemName' remux stderr: ${result.stderr}")
            return false
        }
        if (expectPrimingDeclared) {
            val declared = readAudioEditListStartTicks(outputFile)
            if (declared == null || declared <= 0L) {
                Log.w(TAG, "remuxToProgressive: '$itemName' -- the FINAL file's audio has no priming declaration (media_time=$declared): players will play the priming packet and the audio will run about one packet late")
            } else {
                Log.d(TAG, "remuxToProgressive: '$itemName' final file declares the audio priming (media_time=$declared ticks)")
            }
        }
        return true
    }

    /**
     * CHECK, cheap enough to sit in front of the export: whether video and
     * audio start together in the finished file, read from the first few
     * packets of each stream. Nothing that decides whether the file ships may
     * scale with its length -- a film has tens of thousands of frames -- so this
     * never reads more than [START_PROBE_PACKETS] packets a stream.
     *
     * Returns false only for an offset beyond [AV_SYNC_TOLERANCE_MS], a sync
     * error a viewer would see. Without audio there is nothing to be out of
     * step with and nothing is probed.
     */
    private suspend fun verifyAssembledStart(file: File, itemName: String, hasAudio: Boolean): Boolean {
        if (!hasAudio) return true
        val videoStart = probeStreamFramePtsMsSorted(file, "v:0", firstPackets = START_PROBE_PACKETS)?.firstOrNull()
        val audioStart = probeStreamFramePtsMsSorted(file, "a:0", firstPackets = START_PROBE_PACKETS)?.firstOrNull { it >= 0L }
        if (videoStart == null || audioStart == null) {
            Log.w(TAG, "verifyAssembledStart: '$itemName' -- couldn't read the first packet timestamps (video=$videoStart, audio=$audioStart), skipping the start check")
            return true
        }
        val offsetMs = audioStart - videoStart
        Log.d(TAG, "verifyAssembledStart: '$itemName' first audible audio at ${audioStart}ms, first video frame at ${videoStart}ms (audio ${if (offsetMs >= 0) "after" else "before"} video by ${Math.abs(offsetMs)}ms)")
        if (Math.abs(offsetMs) > AV_SYNC_TOLERANCE_MS) {
            Log.e(TAG, "verifyAssembledStart: '$itemName' audio and video start ${Math.abs(offsetMs)}ms apart (limit ${AV_SYNC_TOLERANCE_MS}ms) -- treating as failure")
            return false
        }
        if (Math.abs(offsetMs) > AV_START_OFFSET_WARN_MS) {
            Log.w(TAG, "verifyAssembledStart: '$itemName' audio and video start ${Math.abs(offsetMs)}ms apart -- within tolerance, but more than a frame")
        }
        return true
    }

    /**
     * DIAGNOSTIC, log-only: whether the finished file's video frames tile its
     * timeline without holes or doubles, from every packet timestamp.
     *
     * Whole-file, so its cost grows with the film. That is why it runs only
     * AFTER the file has been exported, where it cannot hold the export up, and
     * why it never decides anything: a variable-frame-rate source legitimately
     * has uneven intervals, and the point is that a held frame is visible in
     * the log rather than only on a screen.
     */
    private suspend fun scanVideoContinuity(file: File, itemName: String) {
        val startedAt = System.currentTimeMillis()
        val vPts = probeStreamFramePtsMsSorted(file, "v:0")
        if (vPts == null || vPts.size < 2) {
            Log.w(TAG, "scanVideoContinuity: '$itemName' -- couldn't read the video packet timestamps, skipping")
            return
        }
        val deltas = LongArray(vPts.size - 1) { vPts[it + 1] - vPts[it] }
        val median = deltas.sortedArray()[deltas.size / 2].coerceAtLeast(1L)
        var held = 0
        var doubled = 0
        val examples = ArrayList<String>()
        for (i in deltas.indices) {
            val d = deltas[i]
            if (d > median * VIDEO_CONTINUITY_GAP_FACTOR + 1) {
                held++
                if (examples.size < 5) examples += "${formatTimestamp(vPts[i])} (+${d}ms)"
            } else if (d * 2 < median) {
                doubled++
            }
        }
        val summary = "${vPts.size} frames, interval median ${median}ms, first at ${vPts.first()}ms, last at ${vPts.last()}ms, scanned in ${System.currentTimeMillis() - startedAt}ms"
        if (held == 0 && doubled == 0) {
            Log.d(TAG, "scanVideoContinuity: '$itemName' video continuous -- $summary")
        } else {
            Log.w(TAG, "scanVideoContinuity: '$itemName' video has $held held-frame gaps and $doubled doubled frames -- $summary; first gaps at ${examples.joinToString()}")
        }
    }


    /**
     * Saving to a file: the whole range in one pass, from the first byte of
     * the source to the last, with nothing cut or joined on the way.
     *
     * Both encoders write fragmented mp4 files that are merged into a
     * temporary fragmented file as they grow (the first portion after
     * [ASSEMBLER_STARTUP_WAIT_MS], then each next portion as it completes);
     * when both are done, one stream-copy remux makes the final file. The
     * checks that follow run on that final file.
     *
     * A failure that happens before anything has been merged moves on to the
     * next plan (the hardware encoder cannot take the source: try the
     * Transformer). Any later failure -- an encoder dying or ending short
     * part-way, a check failing -- fails the transcode.
     */
    private suspend fun transcodeSinglePass(
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
        strategy: String
    ): Boolean {
        val sessionId = UUID.randomUUID().toString().take(8)
        val totalDurationMs = rangeDurationMs ?: (resolveSourceDurationMs(item) - rangeStartMs)
        if (totalDurationMs <= 0L) {
            Log.e(TAG, "transcodeSinglePass: nothing to transcode for '${item.name}' (start=${rangeStartMs}ms, duration=${totalDurationMs}ms)")
            return false
        }
        // Both legs read the source from its start. A content:// source is mirrored (room permitting)
        // once, here, rather than raced by whichever leg gets there first; released when the run is over.
        resolveFfmpegInputPath(item)
        val targetSize = encodeTargetSize(item, resolution)
        val plans = legPlans(strategy, streaming = false)
        Log.d(TAG, "transcodeSinglePass: '${item.name}' -- ${totalDurationMs}ms from ${rangeStartMs}ms, one video encode + one audio encode merged as they are written (session=$sessionId, plans=$plans)")
        try {
            for ((attempt, plan) in plans.withIndex()) {
                currentCoroutineContext().ensureActive()
                if (attempt > 0) Log.w(TAG, "transcodeSinglePass: '${item.name}' -- starting again on [$plan]")
                val assembledFile = File(durableWorkDir, "assembled_${item.id}_${sessionId}_$attempt.mp4")
                assembledFile.delete()
                finalOutputFile.delete()
                try {
                    val run = try {
                        FileOutputStream(assembledFile).buffered().use { sink ->
                            runLegsOnce(
                                item, plan, targetVideoCodec, resolution, videoBitrateKbps,
                                targetAudioCodec, audioBitrateBps, audioTrackIndex,
                                rangeStartMs, rangeDurationMs, totalDurationMs, sessionId, sink
                            )
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "transcodeSinglePass: '${item.name}' -- could not write ${assembledFile.name}: ${e.message}", e)
                        return false
                    }
                    val assembly = run.assembly
                    if (!assembly.ok) {
                        Log.e(TAG, "transcodeSinglePass: '${item.name}' [$plan] failed -- ${assembly.reason} (video reached ${assembly.videoEndMs}ms, audio ${assembly.audioEndMs}ms, of ${totalDurationMs}ms; ${assembly.bytesWritten} bytes merged)")
                        if (assembly.bytesWritten > 0L || run.sinkFailed) return false
                        continue
                    }
                    val hasAudio = assembly.audioEndMs != null
                    val priming = run.audioPrimingTicks ?: 0L
                    Log.d(TAG, "transcodeSinglePass: '${item.name}' [$plan] merged ${assembly.fragmentsWritten} fragments (${assembly.bytesWritten / 1024} KiB): video ${assembly.videoEndMs}ms, audio ${assembly.audioEndMs}ms, start offset ${assembly.startOffsetMs}ms, audio priming ${priming} ticks")

                    if (!remuxToProgressive(assembledFile, finalOutputFile, item.name, expectPrimingDeclared = priming > 0L)) return false
                    assembledFile.delete() // the final file now holds everything; free the space before the checks
                    Log.d(TAG, "transcodeSinglePass: '${item.name}' final file written (${finalOutputFile.length() / 1024} KiB) -- checking it before it is exported")

                    if (!verifyAssembledDuration(finalOutputFile, totalDurationMs, item.name, toleranceMs = FINAL_CHUNK_TOLERANCE_MS)) return false
                    if (hasAudio && !verifyMuxedAvSync(finalOutputFile, item.name, toleranceMs = FINAL_CHUNK_TOLERANCE_MS)) return false
                    if (!verifyAssembledStart(finalOutputFile, item.name, hasAudio)) return false
                    // The Safe Path has already once ignored the requested size; confirm it was honoured.
                    if (plan.videoStrategy == "safe" && targetSize != null && !verifyOutputResolution(finalOutputFile, targetSize, item.name)) {
                        Log.e(TAG, "transcodeSinglePass: '${item.name}' -- the Safe Path ignored the requested output size -- treating as failure rather than shipping the wrong resolution")
                        return false
                    }
                    Log.d(TAG, "transcodeSinglePass: '${item.name}' done -- ${finalOutputFile.length() / 1024} KiB via [$plan]")
                    return true
                } finally {
                    assembledFile.delete()
                }
            }
            return false
        } finally {
            releaseSourceMirror(item)
        }
    }

    /**
     * The presentation timestamp of every packet on the stream selected by
     * [streamSelector] ("v:0", "a:0", ...) in [file], in ms, sorted ascending
     * -- not file order, since for video, B-frame reordering means decode
     * order and display order differ (an encode with B-frames
     * presents its first frame a couple of frame intervals after its decode
     * time). One video frame per packet, so the packet list IS the frame
     * list. With [firstPackets], only that many packets from the start of the
     * file: all a start-of-file check needs, and cheap however long the file is.
     *
     * Reads packets, not decoded frames (`frame=pts_time` makes ffprobe decode
     * the whole stream to report timestamps that were sitting in the
     * container's sample table the whole time).
     *
     * Presentation timeline (edit lists applied): what a demuxer, and so a
     * player, sees. Negative values (an AAC priming packet) are kept negative.
     *
     * Null on any probe failure or an empty/unparseable result, same posture
     * as every other probeXxx function here -- callers decide what "couldn't
     * tell" means for them rather than this throwing.
     */
    private suspend fun probeStreamFramePtsMsSorted(file: File, streamSelector: String, firstPackets: Int? = null): List<Long>? {
        val args = mutableListOf(ffprobePath, "-hide_banner", "-v", "error", "-select_streams", streamSelector)
        // "%+#N": from the start of the file, N packets.
        if (firstPackets != null) args.addAll(listOf("-read_intervals", "%+#$firstPackets"))
        args.addAll(listOf("-show_entries", "packet=pts_time", "-of", "csv=p=0", file.absolutePath))
        val result = runProcessCapture(args, timeoutMs = KEYFRAME_PROBE_TIMEOUT_MS)
        if (result.exitCode != 0) return null
        return parsePtsLinesMs(result.stdout).ifEmpty { null }
    }

    /**
     * ffprobe's `pts_time` column ("0.033333", "-0.021333,", "N/A") to whole ms, sorted.
     *
     * Parsed by hand, in integer arithmetic. The version this replaces ran a
     * regex over the output and `toDoubleOrNull()` on every match -- tens of
     * microseconds a value on a phone, so seconds of pure parsing for the
     * 80,000 frames of one film -- and multiplied the double by 1000, which
     * is off by a millisecond on some values (32.3 * 1000 is 32299.999999999996).
     * The first cost nothing at 30 seconds of video and a great deal at 45 minutes.
     */
    private fun parsePtsLinesMs(text: String): List<Long> {
        val out = ArrayList<Long>(text.length / 9 + 1)
        val n = text.length
        var i = 0
        while (i < n) {
            var negative = false
            if (text[i] == '-') { negative = true; i++ }
            var whole = 0L
            var digits = 0
            while (i < n && text[i] in '0'..'9') { whole = whole * 10 + (text[i] - '0'); i++; digits++ }
            var micros = 0L
            if (i < n && text[i] == '.') {
                i++
                var scale = 100_000L
                while (i < n && text[i] in '0'..'9') { micros += (text[i] - '0') * scale; scale /= 10; i++ }
            }
            if (digits > 0) {
                val ms = (whole * 1_000_000L + micros) / 1000L
                out += if (negative) -ms else ms
            }
            while (i < n && text[i] != '\n') i++ // the rest of the line: trailing commas, "N/A", CR
            i++
        }
        out.sort()
        return out
    }


    /**
     * The video leg through Media3's Transformer: ONE export of the whole range
     * into [leg]'s output as a FRAGMENTED mp4 (Media3's fragmented muxer, a
     * moov up front and a self-contained fragment per couple of seconds), so
     * what has been encoded is readable while the export is still running --
     * the same shape the ffmpeg path writes, and the one the merge depends on.
     * [leg]'s output is an ordinary file when saving and a FIFO when streaming.
     *
     * Whole-range Transformer exports are where the silent truncation was seen
     * (androidx/media#758): an export that completes cleanly having produced a
     * fraction of the range. A fragmented output cannot hide that -- the leg's
     * own coverage is checked against what was asked for as it finishes, and a
     * short leg fails the run rather than being muxed against full-length audio.
     *
     * The fragmented writer has one flaw that matters for sync: it cannot know
     * how long a fragment's last sample lasts, guesses, and -- writing no
     * decode times -- lets every guess accumulate (about 1 ms per 4 s fragment
     * on a 30 fps MKV). [sampleTimes] receives what the muxer is handed, and the
     * leg uses it to re-time itself ([FragmentTimeCorrector]).
     */
    @OptIn(UnstableApi::class)
    private suspend fun transcodeVideoLegViaSafePath(
        item: MediaCollection.MediaNode.Item,
        targetCodec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        leg: FragmentLeg,
        sampleTimes: SampleTimes,
        limitDurationMs: Long? = null,
        offsetMs: Long = 0,
        timeoutMs: Long
    ): Boolean {
        // The leg's own output. Never deleted here: deleting a FIFO would remove the pipe its reader holds.
        val videoOnlyFile = leg.path
        // The requested resolution used to reach the encoder as a bitrate budget
        // only: a 4K source asked for at 1080p was encoded at full 4K with a
        // 1080p bitrate, i.e. 4x the pixels at a quarter of the bits per pixel.
        // A Presentation effect makes the composition actually scale, which also
        // makes the export cheaper (fewer pixels through the encoder) and so less
        // likely to hit the time budget. Null means the scan's resolution
        // string was unparseable -- in that case encode at source size, as before,
        // rather than guessing at a box.
        //
        // encodeTargetSize, not a local derivation: this has to be the identical
        // box the ffmpeg path pads to, or the two encoders would disagree about the
        // output size. Presentation letterboxes by up to one alignment step
        // to reach it, which is the same black bar ffmpeg's `pad` adds -- that
        // equivalence is the point.
        val targetSize = encodeTargetSize(item, resolution)
        if (targetSize == null) {
            Log.w(TAG, "Safe path: source resolution '${item.defaultResolution}' unparseable for '${item.name}' -- encoding at source size, no scaling applied")
        }

        if (!encoderAdvertisesSupport(targetCodec, resolution)) {
            // Definitive in milliseconds via MediaCodecInfo/EncoderUtil -- fail
            // this leg now rather than spending its whole timeout budget
            // starting a Transformer export the device doesn't even advertise
            // being able to run.
            Log.e(TAG, "Safe path: device does not advertise support for $targetCodec @ ${resolution.name} -- failing '${item.name}' without attempting the export")
            return false
        }

        // Visible outside the suspendCancellableCoroutine so the finally below can
        // reach the Transformer: cancellation has to run on the error and timeout
        // paths alike, which invokeOnCancellation alone didn't cover.
        var transformerRef: Transformer? = null
        var exportStarted = false
        var exportSettled = false

        // Pacing applies to file legs only: a piped leg is already held to its
        // reader's pace by the pipe. See SAFE_PATH_FILE_MAX_SPEED.
        val throttle = if (!leg.isPipe && SAFE_PATH_FILE_MAX_SPEED > 0.0) ExportThrottle(SAFE_PATH_FILE_MAX_SPEED) else null
        // Launched on the long-lived scope, not as a child of the export's own
        // coroutineScope, so a slow /proc read can never hold up its teardown;
        // cancelled in the finally below.
        var telemetryJob: Job? = null

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
                        // Fragmented output, in place of the default progressive mp4 (whose moov
                        // only exists once the export has finished, so nothing could be read
                        // before then, and whose memory use grows with the export). Needs
                        // Media3 1.6+: InAppFragmentedMp4Muxer replaced InAppMuxer's
                        // setOutputFragmentedMp4 there. Wrapped so that the timestamp of every
                        // sample it is handed is kept: its writer lets the video timeline drift
                        // (see FragmentTimeCorrector), and those timestamps are what put it right.
                        // The same wrapper paces a file leg's export (see ExportThrottle).
                        .setMuxerFactory(TimingMuxerFactory(InAppFragmentedMp4Muxer.Factory(SAFE_PATH_FRAGMENT_DURATION_MS), sampleTimes, throttle))
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
                        // from the video leg's stall watchdog and the outer withTimeoutOrNull
                        // budget. Both of those already bound a wedged export on
                        // exactly the hardware (Tensor chips) this file documents
                        // hanging elsewhere; TIME_UNSET disables this third,
                        // independently-tuned timeout and relies on those
                        // already-verified bounds instead of racing them -- and when streaming, a viewer who pauses leaves the muxer blocked on a full pipe for as long as the pause lasts, which must not be taken for a stall.
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
                                    val wroteOutput = leg.outputBytes() > 0
                                    Log.w(TAG, "Safe path: Release timeout during cleanup, output present=$wroteOutput (size=${leg.outputBytes()})")
                                    if (continuation.isActive) continuation.resume(wroteOutput)
                                    return
                                }
                                Log.e(TAG, "Safe path: Transformer export failed: ${exportException.message}", exportException)
                                Log.e(TAG, "  > Error Details: ${exportException.cause?.message}")
                                if (continuation.isActive) continuation.resume(false)
                            }
                        }).also { applyMaxFramesInEncoder(it) }.build()
                    transformerRef = transformer
                    try {
                        val mediaItemBuilder = MediaItem.Builder().setUri(item.uri).setMimeType(item.mimeType)
                        if (limitDurationMs != null || offsetMs > 0) {
                            mediaItemBuilder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(offsetMs)
                                // The range's real end; open-ended means to the source's end.
                                .setEndPositionMs(if (limitDurationMs != null) offsetMs + limitDurationMs else C.TIME_END_OF_SOURCE)
                                // Only an offset of zero is known to sit on a keyframe (the start of
                                // the file). Any other start -- a streaming seek -- is an arbitrary
                                // position: Transformer decodes from the keyframe before it and
                                // discards up to the target, which is where it has had boundary bugs
                                // before (androidx/media#829). With no chunk boundary left to verify
                                // a keyframe against, say so only where it is certainly true.
                                .setStartsAtKeyFrame(offsetMs == 0L)
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
                            // the whole point of sharing encodeTargetSize -- either
                            // encoder lands on the same pixel dimensions.
                            val presentation = Presentation.createForWidthAndHeight(
                                targetSize.width, targetSize.height, Presentation.LAYOUT_SCALE_TO_FIT
                            )
                            editedMediaItemBuilder.setEffects(
                                Effects(ImmutableList.of<AudioProcessor>(), ImmutableList.of<Effect>(presentation))
                            )
                        }
                        val editedMediaItem = editedMediaItemBuilder.build()
                        Log.d(TAG, "Safe path: Starting video-only export for '${item.name}' to ${videoOnlyFile.absolutePath} (source=${item.resolution}, target=${targetSize ?: "source size"}, tier=${resolution.name} ${tierBox(resolution)}, budget=${timeoutMs}ms, ${diagnosticSnapshot()})")
                        Log.d(TAG, "Safe path: export pacing for '${item.name}': ${if (throttle != null) "capped at ${throttle.maxSpeed}x realtime" else "unthrottled (${if (leg.isPipe) "piped leg" else "cap disabled"})"}")
                        transformer.start(editedMediaItem, videoOnlyFile.absolutePath)
                        exportStarted = true
                        telemetryJob = launchExportTelemetry(item.name, leg, throttle)

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
                            var stacksDumped = false
                            while (isActive) {
                                val state = transformer.getProgress(progressHolder)
                                val bytes = leg.outputBytes()
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
                                if (stalledForMs >= 6000L && !(leg.isPipe && leg.queuedMs() >= STREAM_LEAD_MS)) { // a full pipe is a pause on purpose, not a stall
                                    val snapshot = withContext(Dispatchers.IO) { diagnosticSnapshot() }
                                    Log.w(TAG, "Safe path: STALL SUSPECTED for '${item.name}' -- no byte growth for ${stalledForMs}ms (state=$state, bytesWritten=$bytes, $snapshot)")
                                }
                                if (stalledForMs >= STALL_DUMP_AFTER_MS && !stacksDumped && !(leg.isPipe && leg.queuedMs() >= STREAM_LEAD_MS)) {
                                    stacksDumped = true
                                    withContext(Dispatchers.IO) { logMediaThreadStacks("'${item.name}': no byte growth for ${stalledForMs}ms, state=$state") }
                                }
                                // No abort here. A wedged export is stopped by launchLeg's own
                                // watchdog (VIDEO_LEG_STALL_ABORT_MS), which also knows that a leg
                                // held back by a full pipe is waiting on purpose; this loop, which
                                // only sees bytes, would take that for a stall.
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
                // cancel(), not cancelAndJoin(): nothing waits on the monitor.
                telemetryJob?.cancel()
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
        if (throttle != null) Log.d(TAG, "Safe path: pacing summary for '${item.name}': ${throttle.summary()}")
        if (videoResult == null) {
            Log.e(TAG, "Safe path: video leg for '${item.name}' exceeded its ${timeoutMs}ms budget (${diagnosticSnapshot()})")
            return false
        }
        if (!videoResult || leg.outputBytes() == 0L) {
            Log.e(TAG, "Safe path: video leg failed for '${item.name}' (${diagnosticSnapshot()})")
            return false
        }
        // A video leg that produced output proves the codec pool is not exhausted.
        consecutiveCodecAllocationFailures.set(0)
        return true
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
     *  succeed, so they are counted and named in the log rather than left to
     *  look like anything about this particular file. */
    private fun looksLikeCodecExhaustion(text: String?): Boolean {
        if (text == null) return false
        return text.contains("Failed to allocate", ignoreCase = true) ||
            text.contains("CodecException", ignoreCase = true) ||
            text.contains("0x80001001", ignoreCase = true)
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

    /**
     * Encodes [item]'s audio track (or [durationS] of it from [startTimeMs])
     * to [outFile] as a *fragmented* mp4, written as it goes.
     *
     * Fragmented so that it can be read while it is still being written --
     * that is the whole point of this function's output format. A
     * progressive mp4 has its index at the end, so nothing can read it until
     * the last packet is out; a fragmented one is a sequence of
     * self-describing pieces, each readable once it is complete. See
     * [FragmentLeg] for how the pieces are consumed.
     *
     * `delay_moov` rather than `empty_moov`, and it matters: `empty_moov`
     * writes the moov before any packet exists, and with no packet to look at
     * the muxer cannot know the encoder delayed its output, so it declares
     * nothing and the priming packet is left sitting at time 0 where it is
     * indistinguishable from audio. `delay_moov` holds the moov until the
     * first fragment is cut, which is what lets the muxer declare that delay
     * in an edit list -- and [parseLegHeader] reads it from there, so
     * nothing downstream has to know how long any particular encoder's
     * priming is.
     *
     * `-flush_packets 1` because, left alone, ffmpeg buffers its output and
     * the file grows in 256KB lumps that bear no relation to fragment
     * boundaries (measured: a throttled encode showed nothing on disk for
     * six seconds, then 256KB at once -- ~16s of audio at 128kbps). With it,
     * each fragment is written out as soon as it is cut, so what a reader can
     * use tracks the encode to within one fragment instead of sixteen
     * seconds, which is first-byte latency on the streaming path. Correctness
     * does not depend on it -- a reader only ever uses complete fragments
     * whichever way the file grows -- it just stops the readable prefix
     * lagging the encode by an arbitrary amount.
     *
     * `-map_chapters -1` because the output must hold exactly ONE track: the
     * merge copies one trak into its header and reads one traf per moof. Mapping
     * a single audio stream does not make the file single-track -- ffmpeg copies
     * the source's chapters into an mp4 by default, as a text track whose
     * samples ride in the first fragment. A source with chapters therefore made
     * that fragment carry two trafs, and the leg was rejected as unreadable
     * even though the encode itself had succeeded.
     */
    private suspend fun transcodeAudioTrack(
        item: MediaCollection.MediaNode.Item,
        outFile: File,
        durationS: Double? = null,
        startTimeMs: Long = 0,
        encoder: String = "aac",
        audioBitrateBps: Int = 128_000,
        audioTrackIndex: Int = 0,
        timeoutMs: Long = 300_000L,
        /** Bytes of output so far, for a leg that is piped (nothing on disk to measure); null means [outFile] is a file. */
        written: (() -> Long)? = null
    ): Boolean {
        val input = resolveFfmpegInputPath(item)
        // No -hwaccel/-ndk_codec here: those are video decode acceleration hints
        // and this call has no video output (-vn, only an audio stream mapped).
        // They were previously copied over from the video encode args, and on
        // this hardware mediacodec hwaccel is known to hang (see
        // useHardwareFfmpegPipeline) --
        // a very plausible explanation for a whole-range audio encode taking
        // minutes when it should take seconds.
        val args = mutableListOf(ffmpegPath, "-y", "-hide_banner")
        if (startTimeMs > 0) args.addAll(listOf("-ss", (startTimeMs / 1000.0).toString()))
        // -map_chapters -1: see the doc above -- left alone, a source with chapters puts a second
        // (text) track in this file and two trafs in its first moof.
        args.addAll(listOf("-i", input.path, "-vn", "-sn", "-map", "0:a:$audioTrackIndex?", "-map_chapters", "-1", "-c:a", encoder, "-b:a", "${audioBitrateBps / 1000}k"))
        if (durationS != null) args.addAll(listOf("-t", durationS.toString()))
        args.addAll(channelArgs(resolveItemChannelLayout(item, audioTrackIndex)))
        args.addAll(listOf(
            "-flush_packets", "1",
            "-movflags", "delay_moov+default_base_moof",
            "-frag_duration", AUDIO_FRAGMENT_DURATION_US.toString(),
            "-f", "mp4", outFile.absolutePath
        ))
        // timeoutMs used to be a flat 300s regardless of what was being asked for
        // -- a 3-minute clip and a 3-hour film shared one budget. The caller
        // derives it from the work (Budget.forAudio of the whole range).
        // Once per leg, so a run that fails on this leg's output can be read against the command that made it:
        // a clean exit logs nothing else about the process.
        Log.d(TAG, "transcodeAudioTrack: '${item.name}' -> ${outFile.name}: ${args.joinToString(" ")}")
        val result = runProcessCapture(args, timeoutMs = timeoutMs, stdinSource = input.stdinSource)
        return if (written != null) result.exitCode == 0 && written() > 0L else result.succeededWriting(outFile)
    }

    private fun currentAppVersionCode(): Long = try { val info = context.packageManager.getPackageInfo(context.packageName, 0); info.longVersionCode } catch (e: PackageManager.NameNotFoundException) { -1L }

    /** Last [diagnosticSnapshot] result and when it was taken. Volatile rather
     *  than locked: a torn read is impossible for a reference, and two threads
     *  both missing the TTL cost one redundant snapshot, not a correctness
     *  problem. */
    @Volatile private var cachedSnapshot: Pair<Long, String>? = null

    /**
     * Where each thread of a Transformer export is, logged at the moment the
     * export has stopped growing -- so the next stall's log says what the
     * pipeline is BLOCKED ON (the GL thread in a buffer swap, the muxer in a
     * write, a codec in a dequeue ...) instead of only that it is blocked.
     *
     * Only Java threads show here, but a Java thread parked inside a native
     * call shows the Java frame that made it, which is the part that names the
     * stage. One log line per thread: a logcat entry is cut off at about 4 KB.
     */
    private fun logMediaThreadStacks(reason: String) {
        try {
            val interesting = Regex("Effect|Transformer|ExoPlayer|Codec|Muxer|Loader|GlThread|VideoFrame", RegexOption.IGNORE_CASE)
            val all = Thread.getAllStackTraces()
            val matching = all.entries.filter { interesting.containsMatchIn(it.key.name) }
            Log.w(TAG, "Safe path: thread dump ($reason) -- ${matching.size} of ${all.size} threads look like the export's")
            for ((thread, frames) in matching.sortedBy { it.key.name }) {
                val sb = StringBuilder("Safe path: thread [${thread.name}] ${thread.state}")
                for (f in frames.take(18)) sb.append("\n        at ").append(f)
                Log.w(TAG, sb.toString())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Safe path: thread dump failed: ${e.message}")
        }
    }

    /**
     * One line every [EXPORT_TELEMETRY_INTERVAL_MS], for as long as a Safe Path
     * export runs: the time series a stall is read against. [diagnosticSnapshot]
     * says what the system looked like at one moment; this says what was moving
     * beforehand -- whether output speed decays gradually (a resource running
     * out) or drops off a cliff (a wedge), and which of free memory, graphics
     * memory, file descriptors, threads or temperature moved with it.
     *
     * Launched on [scope] rather than as a child of the export, and cancelled
     * by it without waiting: reading /proc and Debug.MemoryInfo can take a long
     * time exactly when the system is thrashing, and a monitor must not be able
     * to hold up the export's teardown. Everything it touches is read-only.
     */
    private fun launchExportTelemetry(itemName: String, leg: FragmentLeg, throttle: ExportThrottle?): Job = scope.launch {
        val startNanos = System.nanoTime()
        var lastBytes = leg.outputBytes()
        var lastNanos = startNanos
        // (elapsed ms, media ms) over the last ~10 s. A fragment lands every few
        // seconds of media, so the speed across a single 2 s tick is too coarse
        // to read.
        val recent = ArrayDeque<LongArray>()
        var tick = 0
        while (isActive) {
            val nowNanos = System.nanoTime()
            val elapsedMs = (nowNanos - startNanos) / 1_000_000
            val bytes = leg.outputBytes()
            val mediaMs = leg.coveredEndMs()
            val sinceLastS = (nowNanos - lastNanos) / 1e9
            val rateMBps = if (sinceLastS > 0.0) (bytes - lastBytes) / 1_048_576.0 / sinceLastS else 0.0
            lastBytes = bytes
            lastNanos = nowNanos
            recent.addLast(longArrayOf(elapsedMs, mediaMs))
            while (recent.size > 1 && elapsedMs - recent.first()[0] > 10_000L) recent.removeFirst()
            val windowMs = elapsedMs - recent.first()[0]
            val speedRecent = if (windowMs >= 4_000L) speedText((mediaMs - recent.first()[1]).toDouble() / windowMs) else "n/a"
            val speedAvg = if (elapsedMs > 0L) speedText(mediaMs.toDouble() / elapsedMs) else "n/a"
            val pacing = if (throttle != null) "cap=${throttle.maxSpeed}x slept=${throttle.sleptMs / 1000}s" else "cap=off"
            val detail = try { buildTelemetryDetail(deep = tick++ % EXPORT_TELEMETRY_DEEP_EVERY == 0) } catch (e: Exception) { "detail failed: ${e.message}" }
            Log.d(TAG, "Safe path: telemetry '$itemName' +${elapsedMs / 1000}s | media=${mediaMs}ms out=${bytes / 1_048_576}MB " +
                "rate=${"%.2f".format(Locale.US, rateMBps)}MB/s speed=$speedRecent(10s) avg=$speedAvg $pacing | $detail")
            delay(EXPORT_TELEMETRY_INTERVAL_MS)
        }
    }

    /** The slow half of a telemetry line, run off the main thread: system memory,
     *  this process's memory, and temperature. Units are MB unless marked. Every
     *  field shows "?" when a build won't hand it to an app.
     *
     *  Reading it: `avail`/`amAvail` falling toward `thr` is memory pressure;
     *  `graphics` or `fds` climbing steadily is a leak in the buffer pipeline
     *  (every GraphicBuffer holds a descriptor); `swapUsed` climbing is the
     *  system compressing other apps to make room; `headroom` is 1.0 at the
     *  device's SEVERE throttling threshold; `Cma*`/`Dma*`/`Gpu*` appear only
     *  where the kernel reports them. `CmaFree` near 0 is not itself alarming
     *  (idle contiguous memory is lent out to ordinary allocations) but it is
     *  what a codec has to reclaim from when it needs a contiguous block.
     *
     *  The `fds` section says what the process's descriptors are; with [deep]
     *  it also totals the memory held by the dma-bufs among them, which is
     *  where memory that /proc/meminfo does not account for goes. */
    private fun buildTelemetryDetail(deep: Boolean): String {
        fun mb(kb: Long?): String = if (kb == null) "?" else (kb / 1024).toString()

        val mem = readProcKv("/proc/meminfo")
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val amInfo = ActivityManager.MemoryInfo().also { activityManager?.getMemoryInfo(it) }
        val swapUsedKb = mem["SwapTotal"]?.let { total -> mem["SwapFree"]?.let { free -> total - free } }
        val pools = mem.entries
            .filter { entry -> listOf("Cma", "Dma", "Gpu", "Ion").any { entry.key.startsWith(it) } }
            .sortedBy { it.key }
            .joinToString(" ") { "${it.key}=${mb(it.value)}" }
        val system = "sys free=${mb(mem["MemFree"])} avail=${mb(mem["MemAvailable"])} " +
            "amAvail=${amInfo.availMem / 1_048_576} (thr=${amInfo.threshold / 1_048_576} low=${amInfo.lowMemory}) " +
            "cached=${mb(mem["Cached"])} dirty=${mem["Dirty"] ?: "?"}kB writeback=${mem["Writeback"] ?: "?"}kB " +
            "anon=${mb(mem["AnonPages"])} shmem=${mb(mem["Shmem"])} sunreclaim=${mb(mem["SUnreclaim"])} " +
            "swapUsed=${mb(swapUsedKb)}" + (if (pools.isNotEmpty()) " $pools" else "")

        val debugInfo = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        val stats = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) debugInfo.memoryStats else emptyMap<String, String>()
        fun stat(name: String): String = mb(stats["summary.$name"]?.toLongOrNull())
        val status = readProcKv("/proc/self/status")
        val runtime = Runtime.getRuntime()
        val process = "proc pss=${mb(debugInfo.totalPss.toLong())} graphics=${stat("graphics")} native=${stat("native-heap")} " +
            "(allocated ${Debug.getNativeHeapAllocatedSize() / 1_048_576}) java=${stat("java-heap")} " +
            "(used ${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576}) code=${stat("code")} other=${stat("private-other")} " +
            "rssAnon=${mb(status["RssAnon"])} rssFile=${mb(status["RssFile"])} rssShmem=${mb(status["RssShmem"])} vmSwap=${mb(status["VmSwap"])} " +
            "threads=${status["Threads"] ?: "?"}"
        val descriptors = describeOpenFds(deep)

        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) thermalStatusName(powerManager?.currentThermalStatus) else "n/a"
        val headroom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) powerManager?.getThermalHeadroom(0) else null
        val battTenthsC = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val thermal = "thermal=$thermalStatus headroom=${if (headroom == null || headroom.isNaN()) "n/a" else "%.2f".format(Locale.US, headroom)} " +
            "batt=${if (battTenthsC == null) "?" else "%.1f".format(Locale.US, battTenthsC / 10.0)}C"

        return "$system | $process | $descriptors | $thermal"
    }

    /** `Key:   value [kB]` lines of /proc/meminfo or /proc/self/status, as numbers;
     *  lines whose value is not a plain number are skipped. */
    private fun readProcKv(path: String): Map<String, Long> {
        val out = HashMap<String, Long>()
        try {
            File(path).forEachLine { line ->
                val colon = line.indexOf(':')
                if (colon > 0) {
                    val number = line.substring(colon + 1).trim().substringBefore(' ').toLongOrNull()
                    if (number != null) out[line.substring(0, colon)] = number
                }
            }
        } catch (_: Exception) {
            // Unreadable on this build: the fields that came from it show "?".
        }
        return out
    }

    /**
     * What this process's file descriptors are, by kind -- and, when [deep],
     * how much memory the dma-bufs among them hold. The 2026-10-12 telemetry
     * showed ~4,000 descriptors appearing within six seconds of an export
     * starting and staying there; the count alone cannot say what they are.
     *
     * `fdMax` and `limit` (soft/hard RLIMIT_NOFILE) show whether the count is
     * running into a ceiling: ~4,000 is suspiciously close to 4,096.
     */
    private fun describeOpenFds(deep: Boolean): String {
        val names = File("/proc/self/fd").list() ?: return "fds=?"
        val kinds = HashMap<String, Int>()
        val dmabufFds = ArrayList<String>()
        var highest = -1
        for (name in names) {
            val index = name.toIntOrNull() ?: continue
            if (index > highest) highest = index
            // Closed since the listing (the listing's own descriptor, say), or unreadable.
            val target = try { Os.readlink("/proc/self/fd/$name") } catch (_: Exception) { continue }
            val kind = fdKind(target)
            kinds[kind] = (kinds[kind] ?: 0) + 1
            if (deep && kind == "dmabuf") dmabufFds.add(name)
        }
        val summary = kinds.entries.sortedByDescending { it.value }.take(7).joinToString(" ") { "${it.key}:${it.value}" }
        val out = StringBuilder("fds=${names.size} [$summary] fdMax=$highest limit=${openFileLimit()}")
        if (deep && dmabufFds.isNotEmpty()) out.append(' ').append(dmabufTotals(dmabufFds))
        return out.toString()
    }

    /** The kind of descriptor a /proc/self/fd link target names. */
    private fun fdKind(target: String): String = when {
        target.startsWith("anon_inode:dmabuf") || target.startsWith("/dmabuf") -> "dmabuf"
        target.startsWith("anon_inode:") -> target.substringAfter(':').take(16)   // sync_file, [eventpoll], [eventfd] ...
        target.startsWith("socket:") -> "socket"
        target.startsWith("pipe:") -> "pipe"
        target.startsWith("/memfd:") -> "memfd"
        target.startsWith("/dev/") -> target.take(24)                              // /dev/binder, /dev/ashmem, a GPU node ...
        target.startsWith("/data/") || target.startsWith("/storage/") || target.startsWith("/mnt/") -> "file"
        else -> target.take(24)
    }

    /** Soft/hard limit on open descriptors, from /proc/self/limits. */
    private fun openFileLimit(): String = try {
        File("/proc/self/limits").useLines { lines ->
            lines.firstOrNull { it.startsWith("Max open files") }
                ?.removePrefix("Max open files")
                ?.trim()
                ?.split(Regex("\\s+"))
                ?.let { "${it.getOrNull(0)}/${it.getOrNull(1)}" }
                ?: "?"
        }
    } catch (_: Exception) {
        "?"
    }

    /**
     * Distinct dma-bufs among [fdNames], and their total size, from fdinfo --
     * one file read per descriptor, which is why it is not done on every
     * sample. Several descriptors can point at one buffer, so they are
     * matched by inode: `distinct` is the number of buffers, `total` what they
     * hold, `by` the same split by exporting heap.
     */
    private fun dmabufTotals(fdNames: List<String>, fdinfoDir: String = "/proc/self/fdinfo"): String {
        val seen = HashSet<Long>()
        val byHeap = HashMap<String, Long>()
        var totalBytes = 0L
        var unreadable = 0
        for (name in fdNames) {
            var inode = -1L
            var size = -1L
            var heap = "?"
            try {
                File("$fdinfoDir/$name").forEachLine { line ->
                    when {
                        line.startsWith("ino:") -> inode = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                        line.startsWith("size:") -> size = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                        line.startsWith("exp_name:") -> heap = line.substringAfter(':').trim()
                    }
                }
            } catch (_: Exception) {
                unreadable++
                continue
            }
            if (size < 0) { unreadable++; continue }
            if (inode < 0 || seen.add(inode)) {
                totalBytes += size
                byHeap[heap] = (byHeap[heap] ?: 0L) + size
            }
        }
        val split = byHeap.entries.sortedByDescending { it.value }.take(5).joinToString(" ") { "${it.key}=${it.value / 1_048_576}MB" }
        val distinct = if (seen.isEmpty()) "?" else seen.size.toString()
        return "dmabuf distinct=$distinct total=${totalBytes / 1_048_576}MB by[$split] unreadable=$unreadable"
    }

    /** Bounds the frames Transformer lets into the video encoder -- see
     *  [SAFE_PATH_MAX_FRAMES_IN_ENCODER]. Reflection, because the setter is
     *  experimental and the log has to say whether this Media3 has it. (A
     *  minified build would need a keep rule for it.) */
    private fun applyMaxFramesInEncoder(builder: Transformer.Builder) {
        val limit = SAFE_PATH_MAX_FRAMES_IN_ENCODER
        if (limit <= 0) return
        try {
            builder.javaClass.getMethod("experimentalSetMaxFramesInEncoder", Int::class.javaPrimitiveType).invoke(builder, limit)
            Log.d(TAG, "Safe path: frames in the encoder limited to $limit (experimentalSetMaxFramesInEncoder)")
        } catch (_: NoSuchMethodException) {
            Log.w(TAG, "Safe path: this Media3 has no Transformer.Builder.experimentalSetMaxFramesInEncoder -- frames in the encoder are NOT bounded")
        } catch (e: Exception) {
            Log.w(TAG, "Safe path: could not set the frames-in-encoder limit: ${e.message}")
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun thermalStatusName(status: Int?): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "unknown"
    }

    private fun speedText(mediaSecondsPerSecond: Double): String = "%.1fx".format(Locale.US, mediaSecondsPerSecond)


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
     * an engineering one -- deliberately not made here. transcodeVideoLegViaSafePath's
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
     * Deliberately hard to trip, because a rejection here fails the entire
     * transcode (see transcodeSinglePass). Three allowances:
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
     * Confirms the finished file actually has an audio track and that its
     * duration agrees with the video track's, tightly. This exists because
     * [verifyAssembledDuration] judges the video track alone -- it could not
     * catch a file that merged "successfully" with no audio track at all, nor
     * one where the independently-produced video and audio legs simply
     * drifted apart by less than its own tolerance.
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

    /** Reads one 4-byte-size-or-extended + 4-byte-type ISO-BMFF box header at
     *  [pos], the same layout the other box readers here parse. Returns
     *  (type, boxSize) or null if there isn't room for a full header (or a
     *  full extended-size header) at [pos] -- callers treat that as a
     *  parse failure: stop, don't guess. */
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

    /** media_time of the first entry of the `elst` box at [elstPos], in the
     *  track's ticks; null if it has no entries. Entries start at +16:
     *  segment_duration, then media_time, 4 bytes each in version 0 and 8 in
     *  version 1. -1 is an empty edit (a delay), not a skip. */
    private fun readFirstEditMediaTime(raf: RandomAccessFile, elstPos: Long): Long? {
        raf.seek(elstPos + 8)
        val version = raf.readByte().toInt()
        raf.seek(elstPos + 12)
        if (raf.readIntBE() <= 0) return null
        return if (version == 1) {
            raf.seek(elstPos + 16 + 8); raf.readLongBE()
        } else {
            raf.seek(elstPos + 16 + 4); raf.readIntBE().toLong()
        }
    }

    /** Where the first edit of [file]'s audio track starts in the media, in the
     *  audio track's ticks, or null if it has no edit list or no audio track.
     *  For an AAC track that is how the encoder's priming packet is declared:
     *  playback starts this many ticks in, so a player skips the packet. Reading
     *  it back from a finished file is how this code confirms the declaration
     *  survived a mux or a re-wrap, rather than assuming ffmpeg kept it. */
    private fun readAudioEditListStartTicks(file: File): Long? {
        try {
            RandomAccessFile(file, "r").use { raf ->
                val moov = findTopLevelBox(raf, 0L, raf.length(), "moov") ?: return null
                var pos = moov.first + 8
                while (pos + 8 <= moov.second) {
                    val (type, size) = readBoxHeader(raf, pos) ?: break
                    if (type == "trak") {
                        val trakEnd = pos + size
                        val mdia = findTopLevelBox(raf, pos + 8, trakEnd, "mdia")
                        val hdlr = mdia?.let { findTopLevelBox(raf, it.first + 8, it.second, "hdlr") }
                        if (hdlr != null) {
                            raf.seek(hdlr.first + 16) // handler_type
                            val handler = ByteArray(4)
                            raf.readFully(handler)
                            if (String(handler, Charsets.US_ASCII) == "soun") {
                                val edts = findTopLevelBox(raf, pos + 8, trakEnd, "edts") ?: return null
                                val elst = findTopLevelBox(raf, edts.first + 8, edts.second, "elst") ?: return null
                                return readFirstEditMediaTime(raf, elst.first)
                            }
                        }
                    }
                    if (size <= 0L) break
                    pos += size
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "readAudioEditListStartTicks: couldn't parse ${file.absolutePath}'s moov (${e.message})")
        }
        return null
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
        //
        // Force-kill and close, off this thread: closing the streams of a
        // process that is being killed can block, and this is the one place
        // that must not.
        fun killProcessDetached() {
            CoroutineScope(Dispatchers.Default + SupervisorJob()).launch {
                try {
                    stdinPfdRef.get()?.let { try { it.close() } catch (e: Exception) {} }
                    process.destroyForcibly(); process.inputStream.close(); process.errorStream.close(); process.outputStream.close()
                } catch (e: Exception) {}
            }
        }
        val exitCode: Int? = try {
            withTimeoutOrNull(timeoutMs) {
                runInterruptible(Dispatchers.IO) { process.waitFor() }
            }
        } catch (e: CancellationException) {
            // Cancelled from outside: the caller gave up on this process -- a
            // failed transcode cancelling the audio encode it no longer needs,
            // a streaming client that went away. withTimeoutOrNull only
            // absorbs its *own* timeout; this is the other kind, and it used
            // to unwind straight out of here with the child still running --
            // to completion, writing a file nobody wanted and holding a core
            // the whole time (for the whole-range audio encode, minutes of it).
            // Killing it is what cancelling a process-backed coroutine has to
            // mean.
            killProcessDetached()
            liveProcesses.remove(process)
            detachedIoScope.cancel()
            throw e
        }
        if (exitCode == null) {
            killProcessDetached()
        } else { try { process.inputStream.close(); process.errorStream.close(); process.outputStream.close() } catch (e: Exception) {} }
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


// =========================================================================
// Merging the two encoders' fragmented mp4 output while it is still being
// written. Everything from here to the end of the file belongs to
// TranscoderController's single-pass pipeline (see runLegsOnce and
// transcodeSinglePass above); it is kept at file level, outside the class,
// because none of it needs the controller and it can be read -- and tested --
// on its own.
// =========================================================================

/*
 * The two "legs" of a transcode -- one video encode and one audio encode, each
 * running once over the whole range and each writing a fragmented mp4 -- and
 * the assembler that merges them into the output while they are still being
 * written.
 *
 * Nothing in here knows about Android, ffmpeg or Media3: a leg is "a fragmented
 * mp4 that grows by whole moof+mdat fragments", whoever writes it and wherever
 * it goes. That keeps the whole file testable on a plain JVM against real
 * ffmpeg output.
 *
 * Why this exists. The video used to be encoded in ~30 s chunks, each an
 * independent process with its own timeline, and joined afterwards. Every join
 * was a place where a frame could be held, dropped or doubled (the seam
 * artefacts). The audio had already moved to one encode, sliced into the
 * chunks. Now the video is one encode too, so there is no join to get wrong:
 * the assembler never re-times, re-cuts or re-encodes anything. It copies each
 * leg's fragments, byte for byte, in presentation order, behind one merged
 * moov. The only things it rewrites are the bookkeeping fields that make the
 * two streams into one: track ids, fragment sequence numbers and (when a
 * writer used absolute offsets) the fragment base offset.
 *
 * A leg reaches the assembler one of two ways:
 *
 *  - As a FILE that is tailed while the encoder appends to it (saving to disk).
 *    Fragments are indexed in place, their bytes stay in the file, and the
 *    file is deleted when the run ends.
 *
 *  - Through a PIPE (a FIFO the encoder writes to) that is read as it fills
 *    (streaming). Each fragment is held in memory until the assembler has
 *    written it, then dropped. Nothing accumulates on disk, and -- the point
 *    of it -- when too much is queued the reader simply stops reading, the
 *    pipe fills, and the encoder blocks in its own write call. The encoders
 *    are therefore paced by whoever consumes the stream, which a growing file
 *    can never do.
 */

private const val ASSEMBLER_TAG = "FragmentAssembler"

/** How often a tailed file is re-scanned, and the assembler re-checks the legs. */
internal const val LEG_POLL_MS = 150L

/** How often an idle pipe is re-checked. Short, because a pipe holds only ~64 KB: a slow reader stalls the encoder. */
internal const val PIPE_POLL_MS = 4L

/** Upper bounds on boxes read whole into memory; anything larger is not a header/fragment we understand. */
private const val MAX_HEADER_BOX_BYTES = 4L * 1024 * 1024
private const val MAX_MOOF_BYTES = 32L * 1024 * 1024
private const val MAX_FRAGMENT_BYTES = 512L * 1024 * 1024

// ---------------------------------------------------------------------------
// Byte-level helpers
// ---------------------------------------------------------------------------

private fun ByteArray.u8(o: Int): Int = this[o].toInt() and 0xFF
private fun ByteArray.u32(o: Int): Long =
    (u8(o).toLong() shl 24) or (u8(o + 1).toLong() shl 16) or (u8(o + 2).toLong() shl 8) or u8(o + 3).toLong()
private fun ByteArray.i32(o: Int): Int = u32(o).toInt()
private fun ByteArray.u64(o: Int): Long = (u32(o) shl 32) or u32(o + 4)
private fun ByteArray.fourcc(o: Int): String = String(this, o, 4, Charsets.ISO_8859_1)
private fun ByteArray.put32(o: Int, v: Int) {
    this[o] = (v ushr 24).toByte(); this[o + 1] = (v ushr 16).toByte()
    this[o + 2] = (v ushr 8).toByte(); this[o + 3] = v.toByte()
}
private fun ByteArray.put64(o: Int, v: Long) { put32(o, (v ushr 32).toInt()); put32(o + 4, v.toInt()) }

private class BoxRef(val type: String, val start: Int, val end: Int, val payload: Int)

/** The boxes directly inside [from, to) of this buffer; stops at the first malformed one. */
private fun ByteArray.boxes(from: Int, to: Int): List<BoxRef> {
    val out = ArrayList<BoxRef>()
    var p = from
    while (p + 8 <= to) {
        var size = u32(p)
        val type = fourcc(p + 4)
        var hdr = 8
        if (size == 1L) {
            if (p + 16 > to) break
            size = u64(p + 8); hdr = 16
        } else if (size == 0L) {
            size = (to - p).toLong()
        }
        if (size < hdr || p + size > to) break
        out += BoxRef(type, p, (p + size).toInt(), p + hdr)
        p += size.toInt()
    }
    return out
}

private fun mp4Box(type: String, vararg parts: ByteArray): ByteArray {
    val payload = parts.sumOf { it.size }
    val out = ByteArray(8 + payload)
    out.put32(0, out.size)
    for (i in 0 until 4) out[4 + i] = type[i].code.toByte()
    var o = 8
    for (p in parts) { p.copyInto(out, o); o += p.size }
    return out
}

// ---------------------------------------------------------------------------
// What a leg tells us about itself
// ---------------------------------------------------------------------------

/**
 * One complete moof+mdat pair. Times are in the leg's own track timescale.
 *
 * A fragment either still lies in the leg's file ([data] null, bytes at
 * [byteStart] until [byteEnd]) or, for a pipe leg, carries its own bytes in
 * [data] (the whole moof+mdat). In both cases [byteStart] is where the moof
 * began in the stream the encoder wrote, which is what an absolute
 * base_data_offset has to be moved from.
 */
internal class LegFragment(
    val startTicks: Long,
    val durationTicks: Long,
    val sampleCount: Int,
    /** Size of the moof at the start of the fragment's bytes. */
    val moofSize: Int,
    val byteStart: Long,
    val byteEnd: Long,
    val data: ByteArray?,
    /** Duration of the fragment's last sample as the writer recorded it, in ticks; -1 when the trun carries no per-sample durations. */
    val lastSampleDuration: Long = -1L,
    /** What that duration has to be instead (see [FragmentTimeCorrector]); -1 leaves it as written. */
    val lastDurationOverride: Long = -1L
) {
    val endTicks: Long get() = startTicks + durationTicks
}

/**
 * The parts of a leg's moov the merged header is built from. The boxes are raw
 * copies: the merge never re-encodes a sample description, an edit list or a
 * codec configuration, it only decides which of them go in front of the
 * fragments.
 */
internal class LegHeader(
    val ftyp: ByteArray?,
    val mvhd: ByteArray,
    val trak: ByteArray,
    val trex: ByteArray,
    val trackId: Int,
    /** Where tkhd's track_ID sits inside [trak]. */
    val trackIdOffsetInTrak: Int,
    val timescale: Int,
    /** "vide" or "soun". */
    val handler: String,
    /**
     * Where presentation starts inside the track's own media timeline, per the
     * edit list: 0 for video, the encoder-delay (priming) packet for AAC. A
     * fragment at media time t is presented at t - [editStartTicks].
     */
    val editStartTicks: Long,
    val trexDefaultDuration: Long
)

internal fun presentationMs(ticks: Long, h: LegHeader): Long = (ticks - h.editStartTicks) * 1000L / h.timescale

/**
 * One encoder's output as the assembler sees it: a header, then fragments in
 * order, then an end (finished, or failed with a reason).
 *
 * Fragments queue up here between the producer (which reads the file or pipe)
 * and the assembler (which consumes them); [coveredEndTicks] keeps counting
 * past what has already been consumed, because the assembler needs to know how
 * far the leg has got, not how much of it is still waiting.
 */
internal class FragmentLeg(val kind: Kind, val label: String, val path: File, val isPipe: Boolean) {
    enum class Kind { VIDEO, AUDIO }

    private val lock = Any()
    private val queue = java.util.ArrayDeque<LegFragment>()
    private var queuedTicks = 0L

    @Volatile
    var header: LegHeader? = null
        private set

    /** The encode ended successfully and every fragment it wrote has been queued. */
    @Volatile
    var finished = false
        internal set

    /** Why the leg is unusable; null while it is healthy or finished. */
    @Volatile
    var failure: String? = null
        internal set

    @Volatile
    var coveredEndTicks = 0L
        private set

    @Volatile
    var fragmentsReceived = 0
        private set

    /** Bytes the producer has taken from the encoder's output so far (pipe legs; file legs report the file's length). */
    @Volatile
    var bytesReceived = 0L
        internal set

    @Volatile
    var job: Job? = null

    /** Set by the leg's own encode while it does something that legitimately produces no output, so
     *  [launchLeg]'s stall clock is held for as long as it is set: copying a source nobody has mirrored
     *  yet to the cache, or reading a piped source through to a late start offset. See [resolveInputFor]. */
    @Volatile
    var stallExempt = false

    /** Re-times this leg's fragments from the writer's own sample timestamps; set before the leg starts, for a writer (Media3's) whose durations drift. */
    @Volatile
    var timeCorrector: FragmentTimeCorrector? = null

    /** Where the next fragment starts when its moof carries no tfdt: the end of the last one received, corrected. */
    @Volatile
    var nextStartTicks = 0L
        private set

    val failed: Boolean get() = failure != null

    internal fun acceptHeader(h: LegHeader) {
        header = h
        Log.d(ASSEMBLER_TAG, "$label: moov read -- track ${h.trackId} '${h.handler}', timescale ${h.timescale}, " +
            "presentation starts at media tick ${h.editStartTicks}")
    }

    internal fun offer(received: LegFragment) {
        val f = timeCorrector?.correct(received, header) ?: received
        synchronized(lock) { queue.addLast(f); queuedTicks += f.durationTicks }
        coveredEndTicks = f.endTicks
        nextStartTicks = f.endTicks
        if (fragmentsReceived == 0) {
            Log.d(ASSEMBLER_TAG, "$label: first fragment -- ${f.sampleCount} samples, ${f.durationTicks} ticks, " +
                "starts at tick ${f.startTicks}, ${f.byteEnd - f.byteStart} bytes")
        }
        fragmentsReceived++
    }

    fun peek(): LegFragment? = synchronized(lock) { queue.peekFirst() }

    fun pop() { synchronized(lock) { queue.pollFirst()?.let { queuedTicks -= it.durationTicks } } }

    /** Media time, in ms, currently queued and not yet consumed. */
    fun queuedMs(): Long {
        val h = header ?: return 0L
        return synchronized(lock) { queuedTicks } * 1000L / h.timescale
    }

    /** Presentation time, in ms, at the end of the last fragment received; 0 until there is one. */
    fun coveredEndMs(): Long {
        val h = header ?: return 0L
        if (fragmentsReceived == 0) return 0L
        return presentationMs(coveredEndTicks, h)
    }

    /** Bytes the encoder has produced so far, whichever way they travel. */
    fun outputBytes(): Long = maxOf(bytesReceived, if (isPipe || !path.exists()) 0L else path.length())

    fun cancel() { job?.cancel() }
}

// ---------------------------------------------------------------------------
// Re-timing a video leg whose writer lets the timeline drift
// ---------------------------------------------------------------------------

/**
 * The presentation timestamp (us) of every sample the video writer was
 * handed, in the order it was handed them. Filled by [TimingMuxer] on the
 * writer's thread, read by [FragmentTimeCorrector] on the leg's.
 */
internal class SampleTimes {
    private var data = LongArray(1 shl 14)

    @Volatile
    var size = 0
        private set

    @Synchronized
    fun add(us: Long) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size] = us
        size += 1
    }

    @Synchronized
    operator fun get(i: Int): Long = data[i]
}

/**
 * Puts a video leg back on the timeline its own samples describe.
 *
 * Media3's fragmented muxer writes no `tfdt`, so where a fragment starts is
 * implied by the durations of everything before it -- and it cannot know the
 * duration of a fragment's LAST sample (the next one is not in the fragment),
 * so it gives it the duration of the sample before. With timestamps that are
 * whole milliseconds (an MKV at 30 fps alternates 33 and 34 ms) the guess is
 * wrong by a millisecond at nearly every fragment boundary, and since each
 * fragment starts where the last one claimed to end, the error accumulates:
 * 675 ms over a 45-minute film, about 0.9 s an hour, video running late
 * against an audio leg that is exact.
 *
 * What the muxer was handed is exact, though, and [SampleTimes] kept it. For
 * every fragment but the last, the first sample of the NEXT fragment has
 * reached the muxer by the time this one is written (that is what makes the
 * writer cut it), so the true start of the next fragment is known. This sets
 * the last sample's duration to whatever puts that start exactly on its
 * timestamp, which leaves the cumulative timeline on the source's own frame
 * times however many fragments there are. The rest of the fragment is left as
 * the muxer wrote it.
 *
 * It trusts nothing it cannot confirm. The writer's guess for a fragment's
 * last sample is the duration of the one before it, which the timestamp log
 * can predict; if they disagree the log and the fragments are not the same
 * samples (or the stream has B-frames, whose durations are computed differently),
 * and the correction switches itself off for the rest of the leg, with the
 * reason in the log, rather than rewrite durations it cannot vouch for.
 */
internal class FragmentTimeCorrector(private val times: SampleTimes, private val label: String) {
    private var nextSample = 0
    private var cumulativeTicks = 0L
    private var disabledBecause: String? = null
    private var examined = 0
    private var corrected = 0
    private var adjustedTicks = 0L
    private var timescale = 0

    fun correct(f: LegFragment, h: LegHeader?): LegFragment {
        val next = nextSample + f.sampleCount // index, in the log, of the following fragment's first sample
        nextSample = next
        examined++
        val uncorrectedEnd = cumulativeTicks + f.durationTicks
        if (h == null || disabledBecause != null || f.lastSampleDuration < 0L || f.sampleCount < 2) {
            cumulativeTicks = uncorrectedEnd
            return f
        }
        timescale = h.timescale
        // The final fragment: nothing follows it, so there is nothing to measure it against.
        if (times.size <= next) {
            cumulativeTicks = uncorrectedEnd
            return f
        }
        val scale = h.timescale
        val tLast = times[next - 1]
        val tPrev = times[next - 2]
        val tNext = times[next]
        val guess = ticksOf(tLast, scale) - ticksOf(tPrev, scale)
        val why: String? = when {
            Math.abs(guess - f.lastSampleDuration) > 2L ->
                "fragment $examined's last sample lasts ${f.lastSampleDuration} ticks but the timestamp log says its predecessor did $guess"
            tNext <= tLast -> "timestamps do not increase at fragment $examined"
            else -> null
        }
        if (why != null) return stop(why, f, uncorrectedEnd)
        val target = ticksOf(tNext, scale) - ticksOf(times[0], scale)
        val others = f.durationTicks - f.lastSampleDuration
        val newLast = target - cumulativeTicks - others
        if (newLast <= 0L || Math.abs(newLast - f.lastSampleDuration) > 5L * scale) {
            return stop("fragment $examined would need a last sample of $newLast ticks (written as ${f.lastSampleDuration})", f, uncorrectedEnd)
        }
        cumulativeTicks = target
        corrected++
        adjustedTicks += newLast - f.lastSampleDuration
        return LegFragment(
            f.startTicks, others + newLast, f.sampleCount, f.moofSize, f.byteStart, f.byteEnd, f.data,
            lastSampleDuration = f.lastSampleDuration, lastDurationOverride = newLast
        )
    }

    private fun stop(why: String, f: LegFragment, uncorrectedEnd: Long): LegFragment {
        disabledBecause = why
        cumulativeTicks = uncorrectedEnd
        Log.w(ASSEMBLER_TAG, "$label: video timeline correction switched OFF -- $why. Fragments from here on keep the writer's own durations, so the leg will drift.")
        return f
    }

    private fun ticksOf(us: Long, scale: Int): Long = Math.floorDiv(us * scale + 500_000L, 1_000_000L)

    fun summary(): String {
        val off = disabledBecause
        val ms = if (timescale > 0) adjustedTicks * 1000L / timescale else 0L
        return when {
            off != null -> "timeline correction OFF after $corrected of $examined fragments ($off)"
            corrected == 0 && examined > 1 -> "timeline correction idle: none of $examined fragments could be measured (no sample timestamps recorded?)"
            else -> "timeline re-timed from the sample timestamps: $corrected of $examined fragments adjusted, ${ms}ms in all"
        }
    }
}

/**
 * Hands every sample straight on to [inner] -- and notes the timestamp of each
 * one the writer will keep, on its way past, for [FragmentTimeCorrector].
 * What the writer ignores (an empty sample, or video before the first key
 * frame) is not noted either, so the log and the fragments hold the same samples.
 * With a [throttle], each video sample is also held back until it is due, which
 * is how a file export is capped to a multiple of realtime.
 */
@OptIn(UnstableApi::class)
internal class TimingMuxer(
    private val inner: Muxer,
    private val times: SampleTimes,
    private val throttle: ExportThrottle? = null
) : Muxer {
    private var videoTrackId = -1
    private var sawKeyFrame = false

    override fun addTrack(format: Format): Int {
        val id = inner.addTrack(format)
        if (MimeTypes.isVideo(format.sampleMimeType)) videoTrackId = id
        return id
    }

    override fun writeSampleData(trackId: Int, byteBuffer: ByteBuffer, bufferInfo: BufferInfo) {
        if (trackId == videoTrackId && bufferInfo.size > 0 && byteBuffer.remaining() > 0) {
            if (bufferInfo.flags and C.BUFFER_FLAG_KEY_FRAME != 0) sawKeyFrame = true
            if (sawKeyFrame) times.add(bufferInfo.presentationTimeUs)
            // Blocks the thread the Transformer drains its encoder on, on purpose:
            // see ExportThrottle.
            throttle?.pace(bufferInfo.presentationTimeUs)
        }
        inner.writeSampleData(trackId, byteBuffer, bufferInfo)
    }

    override fun addMetadataEntry(metadataEntry: Metadata.Entry) = inner.addMetadataEntry(metadataEntry)

    override fun close() = inner.close()
}

@OptIn(UnstableApi::class)
internal class TimingMuxerFactory(
    private val inner: Muxer.Factory,
    private val times: SampleTimes,
    private val throttle: ExportThrottle? = null
) : Muxer.Factory by inner {
    override fun create(path: String): Muxer = TimingMuxer(inner.create(path), times, throttle)
    override fun supportsWritingNegativeTimestampsInEditList(): Boolean = inner.supportsWritingNegativeTimestampsInEditList()
}

/**
 * Holds a Transformer export to at most [maxSpeed] seconds of media per second
 * of wall clock, by making [TimingMuxer] wait before it writes any video sample
 * that is ahead of schedule.
 *
 * Waiting in the muxer is a legitimate way to set a pace, not a hack: it stops
 * the encoder's output being drained, which fills the encoder's input, which
 * blocks the GL thread, then the decoder, then the reader -- the chain a full
 * pipe sets off on a streaming leg (hence the muxer-delay watchdog being
 * disabled for the export). So the wait happens on the thread Transformer
 * drains its encoder on, and is meant to.
 *
 * Not a smoothing filter. The schedule is anchored at the first sample, but
 * credit is capped at [MAX_BURST_NANOS]: an export that ran slow for a while
 * (or just resumed after a stall) may catch up by about a second, never by
 * minutes -- otherwise the cap would be spent as a burst, and bursts are
 * exactly the load it is there to remove.
 *
 * Called from one thread only (the muxer's); [sleptMs] is safe to read from any.
 */
internal class ExportThrottle(val maxSpeed: Double) {
    init { require(maxSpeed > 0.0) { "maxSpeed must be positive, was $maxSpeed" } }

    private var firstPtsUs = Long.MIN_VALUE
    private var highestPtsUs = Long.MIN_VALUE
    private var anchorNanos = 0L
    private var firstNanos = 0L

    @Volatile private var lastNanos = 0L
    @Volatile private var pacedMediaUs = 0L
    @Volatile private var sleptNanos = 0L

    /** Total time spent waiting so far. */
    val sleptMs: Long get() = sleptNanos / 1_000_000

    /** Waits, if need be, until the sample presented at [ptsUs] is due.
     *  Each moment of media is paced once, the first time the writer gets past
     *  it: a sample at or behind the highest timestamp seen (a reordering
     *  encoder's) costs nothing. */
    fun pace(ptsUs: Long) {
        val now = System.nanoTime()
        if (firstPtsUs == Long.MIN_VALUE) {
            firstPtsUs = ptsUs
            highestPtsUs = ptsUs
            anchorNanos = now
            firstNanos = now
        } else if (ptsUs > highestPtsUs) {
            highestPtsUs = ptsUs
            pacedMediaUs = ptsUs - firstPtsUs
            val dueNanos = anchorNanos + (pacedMediaUs * 1000.0 / maxSpeed).toLong()
            val behindNanos = now - dueNanos
            if (behindNanos > MAX_BURST_NANOS) {
                // Slower than the cap for a while: forget the credit beyond the allowance.
                anchorNanos += behindNanos - MAX_BURST_NANOS
            } else if (-behindNanos >= MIN_WAIT_NANOS) {
                val deadline = now + min(-behindNanos, MAX_WAIT_NANOS)
                var remaining = deadline - System.nanoTime()
                // parkNanos returns at once on an interrupted thread and leaves the flag
                // set, so a cancelled export is neither held up nor has its interrupt eaten.
                while (remaining > 0L && !Thread.currentThread().isInterrupted) {
                    LockSupport.parkNanos(remaining)
                    remaining = deadline - System.nanoTime()
                }
                sleptNanos += System.nanoTime() - now
            }
        }
        lastNanos = System.nanoTime()
    }

    /** What the pacing did, for the log once the export is over. */
    fun summary(): String {
        val wallS = (lastNanos - firstNanos) / 1e9
        val mediaS = pacedMediaUs / 1e6
        val speed = if (wallS > 0.0) mediaS / wallS else 0.0
        return "cap ${maxSpeed}x; ${"%.0f".format(Locale.US, mediaS)}s of media in ${"%.0f".format(Locale.US, wallS)}s of wall clock " +
            "(${"%.1f".format(Locale.US, speed)}x), ${sleptMs / 1000}s of it spent waiting"
    }

    private companion object {
        /** How far behind schedule the export may fall and still catch up. */
        const val MAX_BURST_NANOS = 1_000_000_000L
        /** Not worth a context switch: a sample this close to due goes straight through. */
        const val MIN_WAIT_NANOS = 2_000_000L
        /** One wait never exceeds this, whatever the clock says. */
        const val MAX_WAIT_NANOS = 1_000_000_000L
    }
}

// ---------------------------------------------------------------------------
// Parsing: moov -> LegHeader, moof -> fragment facts
// ---------------------------------------------------------------------------

/** The handler type ("soun", "vide", "text" ...) of [trak]'s media, or "?" when it cannot be read. */
private fun trakHandlerOf(moov: ByteArray, trak: BoxRef): String {
    val mdia = moov.boxes(trak.payload, trak.end).firstOrNull { it.type == "mdia" } ?: return "?"
    val hdlr = moov.boxes(mdia.payload, mdia.end).firstOrNull { it.type == "hdlr" } ?: return "?"
    return if (hdlr.payload + 12 <= hdlr.end) moov.fourcc(hdlr.payload + 8) else "?"
}

/** [problem], when given, receives the reason a moov could not be used (not every null return has one). */
internal fun parseLegHeader(ftyp: ByteArray?, moov: ByteArray, problem: StringBuilder? = null): LegHeader? {
    val moovHdr = if (moov.u32(0) == 1L) 16 else 8
    val kids = moov.boxes(moovHdr, moov.size)
    val mvhd = kids.firstOrNull { it.type == "mvhd" } ?: return null
    val traks = kids.filter { it.type == "trak" }
    // One track per leg: the merge copies this one trak into its header and reads one traf per moof
    // (see parseMoof), so a second track can never be merged. Said here, naming what it is, instead
    // of letting the first fragment fail with a layout error that does not.
    if (traks.size > 1) {
        problem?.append("${traks.size} tracks (${traks.joinToString { trakHandlerOf(moov, it) }}), expected one")
        return null
    }
    val trak = traks.firstOrNull() ?: return null
    val trakKids = moov.boxes(trak.payload, trak.end)
    val tkhd = trakKids.firstOrNull { it.type == "tkhd" } ?: return null
    val tkhdVersion = moov.u8(tkhd.payload)
    val idOffset = tkhd.payload + 4 + (if (tkhdVersion == 1) 16 else 8)
    if (idOffset + 4 > tkhd.end) return null
    val trackId = moov.i32(idOffset)

    val mdia = trakKids.firstOrNull { it.type == "mdia" } ?: return null
    val mdiaKids = moov.boxes(mdia.payload, mdia.end)
    val mdhd = mdiaKids.firstOrNull { it.type == "mdhd" } ?: return null
    val mdhdVersion = moov.u8(mdhd.payload)
    val timescale = moov.i32(mdhd.payload + 4 + (if (mdhdVersion == 1) 16 else 8))
    if (timescale <= 0) return null
    val hdlr = mdiaKids.firstOrNull { it.type == "hdlr" } ?: return null
    val handler = moov.fourcc(hdlr.payload + 8)

    // First real edit: an empty edit (media_time -1) only delays the start, it does not skip media.
    var editStart = 0L
    trakKids.firstOrNull { it.type == "edts" }?.let { edts ->
        moov.boxes(edts.payload, edts.end).firstOrNull { it.type == "elst" }?.let { elst ->
            val version = moov.u8(elst.payload)
            val count = moov.u32(elst.payload + 4).toInt()
            var p = elst.payload + 8
            for (i in 0 until count) {
                val mediaTime: Long
                if (version == 1) {
                    if (p + 20 > elst.end) break
                    mediaTime = moov.u64(p + 8); p += 20
                } else {
                    if (p + 12 > elst.end) break
                    mediaTime = moov.i32(p + 4).toLong(); p += 12
                }
                if (mediaTime >= 0) { editStart = mediaTime; break }
            }
        }
    }

    val mvex = kids.firstOrNull { it.type == "mvex" } ?: return null
    val trex = moov.boxes(mvex.payload, mvex.end)
        .filter { it.type == "trex" && it.end - it.start >= 32 }
        .let { all -> all.firstOrNull { moov.i32(it.payload + 4) == trackId } ?: all.firstOrNull() } ?: return null

    return LegHeader(
        ftyp = ftyp,
        mvhd = moov.copyOfRange(mvhd.start, mvhd.end),
        trak = moov.copyOfRange(trak.start, trak.end),
        trex = moov.copyOfRange(trex.start, trex.end),
        trackId = trackId,
        trackIdOffsetInTrak = idOffset - trak.start,
        timescale = timescale,
        handler = handler,
        editStartTicks = editStart,
        trexDefaultDuration = moov.u32(trex.payload + 12)
    )
}

/** Where the fields the assembler reads or rewrites sit inside one moof, plus what the fragment contains. */
internal class MoofLayout(
    val sequenceOffset: Int,
    val trackIdOffset: Int,
    val trackId: Int,
    /** -1 when tfhd carries no absolute base_data_offset (the usual default-base-is-moof case). */
    val baseDataOffsetOffset: Int,
    val baseDecodeTime: Long?,
    val durationTicks: Long,
    val sampleCount: Int,
    /** The last sample's duration as written, and where in the moof that field is; -1 when the trun has no per-sample durations. */
    val lastSampleDuration: Long,
    val lastSampleDurationOffset: Int
)

/** [problem], when given, receives the reason a moof could not be understood. */
internal fun parseMoof(moof: ByteArray, trexDefaultDuration: Long, problem: StringBuilder? = null): MoofLayout? {
    fun bad(why: String): MoofLayout? { problem?.append(why); return null }
    val moofHdr = if (moof.u32(0) == 1L) 16 else 8
    val kids = moof.boxes(moofHdr, moof.size)
    val mfhd = kids.firstOrNull { it.type == "mfhd" } ?: return bad("no mfhd")
    val trafs = kids.filter { it.type == "traf" }
    // One track per leg means one traf per moof. A writer that packs several would need the
    // base offsets of the later ones reasoned about; not worth supporting, but never mis-read.
    // (What first tripped this was ffmpeg's chapter text track: see -map_chapters in the ffmpeg legs.)
    if (trafs.size != 1) return bad("${trafs.size} trafs (children: ${kids.joinToString { it.type }})")
    val traf = trafs[0]
    val tkids = moof.boxes(traf.payload, traf.end)
    val tfhd = tkids.firstOrNull { it.type == "tfhd" } ?: return bad("no tfhd (traf children: ${tkids.joinToString { it.type }})")

    val tfFlags = (moof.u32(tfhd.payload) and 0xFFFFFFL).toInt()
    val trackId = moof.i32(tfhd.payload + 4)
    var o = tfhd.payload + 8
    var baseOffset = -1
    if (tfFlags and 0x1 != 0) { baseOffset = o; o += 8 }
    if (tfFlags and 0x2 != 0) o += 4
    var defaultDuration: Long? = null
    if (tfFlags and 0x8 != 0) { defaultDuration = moof.u32(o); o += 4 }

    val baseTime: Long? = tkids.firstOrNull { it.type == "tfdt" }?.let { tfdt ->
        if (moof.u8(tfdt.payload) == 1) moof.u64(tfdt.payload + 4) else moof.u32(tfdt.payload + 4)
    }

    var duration = 0L
    var samples = 0
    var lastDuration = -1L
    var lastDurationOffset = -1
    for (trun in tkids.filter { it.type == "trun" }) {
        val flags = (moof.u32(trun.payload) and 0xFFFFFFL).toInt()
        val count = moof.u32(trun.payload + 4).toInt()
        var p = trun.payload + 8
        if (flags and 0x1 != 0) p += 4
        if (flags and 0x4 != 0) p += 4
        val perSample = 4 * Integer.bitCount(flags and 0xF00)
        if (p.toLong() + count.toLong() * perSample > trun.end) return bad("trun with $count samples overruns its box")
        if (flags and 0x100 != 0) {
            for (i in 0 until count) duration += moof.u32(p + i * perSample)
            if (count > 0) {
                lastDurationOffset = p + (count - 1) * perSample // the duration is the first field of a sample entry
                lastDuration = moof.u32(lastDurationOffset)
            }
        } else {
            duration += count.toLong() * (defaultDuration ?: trexDefaultDuration)
            if (count > 0) { lastDurationOffset = -1; lastDuration = -1L }
        }
        samples += count
    }
    return MoofLayout(mfhd.payload + 4, tfhd.payload + 4, trackId, baseOffset, baseTime, duration, samples, lastDuration, lastDurationOffset)
}

// ---------------------------------------------------------------------------
// Getting fragments out of a leg: tailing a file, or reading a pipe
// ---------------------------------------------------------------------------

/**
 * Walks a leg's FILE as it grows and indexes every *complete* moof+mdat pair.
 * A fragment counts only once its mdat is entirely on disk, so a read never
 * sees half a fragment; the encoder's writes are append-only, which is what
 * lets a byte range, once indexed, be trusted forever.
 */
internal class LegIndexer(private val file: File) {
    private var scanned = 0L
    private var ftyp: ByteArray? = null
    var error: String? = null
        private set

    /** Hands whatever became complete since the last call to [leg]; true when something was added. */
    fun scan(leg: FragmentLeg): Boolean {
        if (error != null || !file.exists()) return false
        val length = file.length()
        if (length <= scanned) return false
        var changed = false
        try {
            RandomAccessFile(file, "r").use { raf ->
                val hdr = ByteArray(16)
                var pos = scanned
                scan@ while (pos + 8 <= length) {
                    raf.seek(pos)
                    raf.readFully(hdr, 0, 8)
                    var size = hdr.u32(0)
                    val type = hdr.fourcc(4)
                    var hdrSize = 8
                    if (size == 1L) {
                        if (pos + 16 > length) break
                        raf.readFully(hdr, 8, 8)
                        size = hdr.u64(8); hdrSize = 16
                    } else if (size == 0L) {
                        break // "to end of file": cannot be judged complete while the file is growing
                    }
                    if (size < hdrSize) { error = "malformed box '$type' (size $size) at $pos"; break }
                    if (pos + size > length) break // still being written
                    when (type) {
                        "ftyp" -> if (size <= MAX_HEADER_BOX_BYTES) ftyp = readBytes(raf, pos, size.toInt())
                        "moov" -> {
                            if (size > MAX_HEADER_BOX_BYTES) { error = "moov of $size bytes"; break@scan }
                            val problem = StringBuilder()
                            val parsed = parseLegHeader(ftyp, readBytes(raf, pos, size.toInt()), problem)
                            if (parsed == null) {
                                error = "unreadable moov at $pos" + (if (problem.isEmpty()) "" else " ($problem)")
                                break@scan
                            }
                            leg.acceptHeader(parsed)
                            changed = true
                        }
                        "moof" -> {
                            val next = pos + size
                            if (next + 8 > length) break@scan
                            raf.seek(next)
                            raf.readFully(hdr, 0, 8)
                            var mSize = hdr.u32(0)
                            val mType = hdr.fourcc(4)
                            var mHdr = 8
                            if (mSize == 1L) {
                                if (next + 16 > length) break@scan
                                raf.readFully(hdr, 8, 8)
                                mSize = hdr.u64(8); mHdr = 16
                            }
                            if (mType != "mdat") { error = "moof at $pos is followed by '$mType', not mdat"; break@scan }
                            if (mSize < mHdr) { error = "malformed mdat at $next"; break@scan }
                            if (next + mSize > length) break@scan // fragment's data not fully written yet
                            val h = leg.header
                            if (h == null) { error = "fragment at $pos precedes the moov"; break@scan }
                            if (size > MAX_MOOF_BYTES) { error = "moof of $size bytes"; break@scan }
                            val problem = StringBuilder()
                            val layout = parseMoof(readBytes(raf, pos, size.toInt()), h.trexDefaultDuration, problem)
                            if (layout == null) { error = "unsupported moof layout at $pos ($problem)"; break@scan }
                            val start = layout.baseDecodeTime ?: leg.nextStartTicks
                            leg.offer(LegFragment(start, layout.durationTicks, layout.sampleCount, size.toInt(), pos, next + mSize, null, layout.lastSampleDuration))
                            changed = true
                            pos = next + mSize
                            continue@scan
                        }
                        // mfra, free, skip, sidx, styp ...: nothing the merge needs.
                    }
                    pos += size
                }
                scanned = pos
            }
        } catch (e: IOException) {
            error = "reading ${file.name} failed: ${e.message}"
        }
        return changed
    }

    private fun readBytes(raf: RandomAccessFile, pos: Long, n: Int): ByteArray {
        val out = ByteArray(n)
        raf.seek(pos)
        raf.readFully(out)
        return out
    }
}

/**
 * Cuts a fragmented mp4 BYTE STREAM -- arriving in whatever sized pieces a
 * pipe hands over -- into the same things [LegIndexer] finds in a file: the
 * header, then whole fragments. Each fragment is returned with its own bytes,
 * since nothing is left on disk to point at.
 */
internal class FragmentStreamParser(private val leg: FragmentLeg) {
    var error: String? = null
        private set

    private var ftyp: ByteArray? = null
    private var streamPos = 0L
    private val head = ByteArray(16)
    private var headLen = 0
    private var cur: ByteArray? = null
    private var curType = ""
    private var curFilled = 0
    private var curMoofSize = 0
    private var curFragmentStart = 0L
    private var skipLeft = 0L
    private var pendingMoof: ByteArray? = null
    private var pendingMoofPos = 0L

    fun feed(buf: ByteArray, offset: Int, length: Int) {
        var off = offset
        var left = length
        while (left > 0 && error == null) {
            if (skipLeft > 0) {
                val n = minOf(skipLeft, left.toLong()).toInt()
                skipLeft -= n; off += n; left -= n; streamPos += n
                continue
            }
            val box = cur
            if (box == null) {
                // A box header: 8 bytes, or 16 when the size field says a 64-bit size follows.
                val want = if (headLen >= 4 && head.u32(0) == 1L) 16 else 8
                val n = minOf(want - headLen, left)
                System.arraycopy(buf, off, head, headLen, n)
                headLen += n; off += n; left -= n; streamPos += n
                val wantNow = if (headLen >= 4 && head.u32(0) == 1L) 16 else 8
                if (headLen < wantNow) continue
                beginBox(wantNow)
                headLen = 0
            } else {
                val n = minOf(box.size - curFilled, left)
                System.arraycopy(buf, off, box, curFilled, n)
                curFilled += n; off += n; left -= n; streamPos += n
                if (curFilled == box.size) { val t = curType; cur = null; completeBox(t, box) }
            }
        }
    }

    private fun beginBox(hdrSize: Int) {
        var size = head.u32(0)
        val type = head.fourcc(4)
        if (size == 1L) size = head.u64(8)
        if (size == 0L) { error = "box '$type' runs to the end of the stream"; return }
        if (size < hdrSize) { error = "malformed box '$type' (size $size)"; return }
        val boxStart = streamPos - hdrSize
        when (type) {
            "ftyp", "moov", "moof" -> {
                val cap = if (type == "moof") MAX_MOOF_BYTES else MAX_HEADER_BOX_BYTES
                if (size > cap) { error = "$type of $size bytes"; return }
                val arr = ByteArray(size.toInt())
                System.arraycopy(head, 0, arr, 0, hdrSize)
                cur = arr; curType = type; curFilled = hdrSize
                if (type == "moof") pendingMoofPos = boxStart
            }
            "mdat" -> {
                val m = pendingMoof
                if (m == null) { skipLeft = size - hdrSize; return } // an mdat nobody described is not ours to forward
                if (m.size + size > MAX_FRAGMENT_BYTES) { error = "fragment of ${m.size + size} bytes"; return }
                val arr = ByteArray((m.size + size).toInt())
                System.arraycopy(m, 0, arr, 0, m.size)
                System.arraycopy(head, 0, arr, m.size, hdrSize)
                cur = arr; curType = "mdat"; curFilled = m.size + hdrSize
                curMoofSize = m.size; curFragmentStart = pendingMoofPos
                pendingMoof = null
            }
            else -> skipLeft = size - hdrSize // mfra, free, skip, sidx ...
        }
    }

    private fun completeBox(type: String, box: ByteArray) {
        when (type) {
            "ftyp" -> ftyp = box
            "moov" -> {
                val problem = StringBuilder()
                val h = parseLegHeader(ftyp, box, problem)
                if (h == null) { error = "unreadable moov" + (if (problem.isEmpty()) "" else " ($problem)"); return }
                leg.acceptHeader(h)
            }
            "moof" -> pendingMoof = box
            "mdat" -> {
                val h = leg.header
                if (h == null) { error = "fragment before the moov"; return }
                val problem = StringBuilder()
                val layout = parseMoof(box.copyOfRange(0, curMoofSize), h.trexDefaultDuration, problem)
                if (layout == null) { error = "unsupported moof layout ($problem)"; return }
                val start = layout.baseDecodeTime ?: leg.nextStartTicks
                leg.offer(LegFragment(start, layout.durationTicks, layout.sampleCount, curMoofSize,
                    curFragmentStart, curFragmentStart + box.size, box, layout.lastSampleDuration))
            }
        }
    }
}

private interface LegSource {
    /** How long an idle source waits before looking again. */
    val pollMs: Long
    /** Moves whatever the encoder has produced since the last call into the leg; true when it did something. */
    fun pull(): Boolean
    /** True while the source is deliberately not reading because the leg has enough queued. */
    fun holdingBack(): Boolean
    /** Something that only grows while the encoder is working, for the stall watchdog. */
    fun growth(): Long
    val error: String?
    fun close()
}

private class FileTailSource(private val leg: FragmentLeg) : LegSource {
    private val indexer = LegIndexer(leg.path)
    override val pollMs = LEG_POLL_MS
    override fun pull(): Boolean = indexer.scan(leg)
    override fun holdingBack(): Boolean = false
    override fun growth(): Long = if (leg.path.exists()) leg.path.length() else 0L
    override val error: String? get() = indexer.error
    override fun close() {}
}

/**
 * Reads a FIFO the encoder is writing to. The FIFO is opened read-WRITE: that
 * never blocks (a plain read-only open waits for a writer, and an encoder that
 * dies before opening its output would leave this waiting for ever), and since
 * this side then holds a writer itself, the pipe never reports end-of-file.
 * The end is decided by the caller instead -- the encode has finished AND
 * nothing is left to read -- which is sound because everything an encoder
 * writes is in the pipe before it exits.
 */
private class PipeSource(private val leg: FragmentLeg, private val maxLeadMs: Long) : LegSource {
    private val raf = RandomAccessFile(leg.path, "rw")
    private val input = FileInputStream(raf.fd)
    private val parser = FragmentStreamParser(leg)
    private val buffer = ByteArray(256 * 1024)
    private var ioError: String? = null
    override val pollMs = PIPE_POLL_MS

    override fun pull(): Boolean {
        var any = false
        // Read while there is room. Not reading is the whole backpressure mechanism.
        while (leg.queuedMs() < maxLeadMs && parser.error == null && ioError == null) {
            val available = try { input.available() } catch (e: IOException) { ioError = "pipe unreadable: ${e.message}"; break }
            if (available <= 0) break
            val n = try { input.read(buffer, 0, minOf(available, buffer.size)) } catch (e: IOException) { ioError = "pipe unreadable: ${e.message}"; break }
            if (n <= 0) break
            leg.bytesReceived += n
            parser.feed(buffer, 0, n)
            any = true
        }
        return any
    }

    override fun holdingBack(): Boolean = leg.queuedMs() >= maxLeadMs
    override fun growth(): Long = leg.bytesReceived
    override val error: String? get() = parser.error ?: ioError
    override fun close() { try { raf.close() } catch (_: IOException) {} }
}

/**
 * Runs [encode] (which writes [leg]'s file or pipe) and moves what it writes
 * into the leg as it appears.
 *
 * The encode's own success is not enough to call the leg good. It also has to
 * have written a readable fragment stream, and -- when [expectedMs] is given --
 * one that actually reaches (within [toleranceMs]) the end of what was asked
 * for. A leg that ends early without error is the failure mode that cost the
 * old whole-file encode its last frames, so it is judged here, by the leg's
 * own fragment count, rather than discovered later in the output.
 *
 * A fragment stream the merge cannot read is known from its first bad box, and
 * the encode is cancelled there instead of being left to finish: a leg whose
 * output is unusable from its first fragment would otherwise cost its whole
 * encode before the run failed -- and, through the other leg, everything that
 * one encoded in the meantime with nothing able to be merged.
 *
 * [stallAbortMs], when set, kills an encode whose output has not grown for
 * that long (time spent deliberately not reading a full pipe does not count,
 * nor does time the encode itself marks with [FragmentLeg.stallExempt]).
 * A fragmented writer grows in whole fragments, about one GOP, so this must be
 * sized well above a GOP of encode time. It exists because without per-chunk
 * budgets a wedged encoder would otherwise only be stopped by the budget for
 * the whole film.
 *
 * For a pipe leg, [maxLeadMs] is how much encoded media may sit queued before
 * the reader stops reading and the encoder is made to wait.
 */
internal fun CoroutineScope.launchLeg(
    leg: FragmentLeg,
    maxLeadMs: Long,
    stallAbortMs: Long?,
    expectedMs: Long?,
    toleranceMs: Long,
    encode: suspend () -> Boolean
): Job {
    val job = launch(Dispatchers.IO) {
        // The source is opened BEFORE the encoder starts: a pipe must have its reader in place
        // when the encoder opens its end of it.
        val source: LegSource = try {
            if (leg.isPipe) PipeSource(leg, maxLeadMs) else FileTailSource(leg)
        } catch (e: Exception) {
            Log.e(ASSEMBLER_TAG, "${leg.label}: could not open ${leg.path.name}: ${e.message}")
            leg.failure = "could not open its output: ${e.message}"
            return@launch
        }
        val encoding = async {
            try { encode() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.e(ASSEMBLER_TAG, "${leg.label}: encode threw ${e.javaClass.simpleName}: ${e.message}", e); false
            }
        }
        var lastGrowth = -1L
        var lastGrowthAt = System.currentTimeMillis()
        var stalled = false
        // The output turned out to be something the merge cannot use (see the doc above).
        var unreadable = false
        try {
            while (true) {
                val done = encoding.isCompleted // sampled BEFORE the pull so the last pull sees everything
                val progressed = source.pull()
                // Only while the encode is still running: one that has already finished is left to the
                // verdict below, so a failed encode is still reported as failed rather than as unreadable.
                if (!done && source.error != null) {
                    Log.e(ASSEMBLER_TAG, "${leg.label}: unreadable output (${source.error}) -- aborting the encode")
                    unreadable = true
                    encoding.cancel()
                    break
                }
                val now = System.currentTimeMillis()
                val growth = source.growth()
                if (growth != lastGrowth || source.holdingBack() || leg.stallExempt) { lastGrowth = growth; lastGrowthAt = now }
                if (done && !progressed && !source.holdingBack()) break
                if (stallAbortMs != null && now - lastGrowthAt >= stallAbortMs) {
                    Log.e(ASSEMBLER_TAG, "${leg.label}: no growth for ${now - lastGrowthAt}ms -- aborting the encode")
                    stalled = true
                    encoding.cancel()
                    break
                }
                if (!progressed) delay(source.pollMs)
            }
            // An encode this loop cancelled itself (stalled, unreadable) is not awaited: awaiting a
            // cancelled job throws, and its verdict is the loop's own.
            val encodeOk = !stalled && (unreadable || encoding.await())
            val h = leg.header
            val verdict: String? = when {
                stalled -> "stalled"
                !encodeOk -> "the encode reported failure"
                source.error != null -> "unreadable output: ${source.error}"
                h == null || leg.fragmentsReceived == 0 -> "no fragments were produced"
                expectedMs != null && leg.coveredEndMs() + toleranceMs < expectedMs ->
                    "truncated: covers ${leg.coveredEndMs()}ms of the expected ${expectedMs}ms"
                else -> null
            }
            if (verdict == null) {
                Log.d(ASSEMBLER_TAG, "${leg.label}: complete -- ${leg.fragmentsReceived} fragments, ${leg.coveredEndMs()}ms" +
                    (leg.timeCorrector?.let { "; ${it.summary()}" } ?: ""))
                leg.finished = true
            } else {
                Log.e(ASSEMBLER_TAG, "${leg.label}: FAILED -- $verdict")
                leg.failure = verdict
            }
        } catch (e: CancellationException) {
            if (!leg.finished && leg.failure == null) leg.failure = "cancelled"
            throw e
        } finally {
            encoding.cancel()
            source.close()
        }
    }
    leg.job = job
    return job
}

// ---------------------------------------------------------------------------
// Merging
// ---------------------------------------------------------------------------

internal class MergedHeader(val bytes: ByteArray, val videoTrackId: Int, val audioTrackId: Int?)

/**
 * ftyp + one moov that declares the video track and (when there is one) the
 * audio track, followed by nothing: every fragment follows separately.
 *
 * Both legs number their track 1, so the audio's id is moved when it collides;
 * the track boxes themselves (codec configuration, edit list, timescale) are
 * the legs' own bytes. mvex keeps only the trex entries -- a leg's mehd would
 * claim the duration of one track as the duration of the file.
 */
internal fun buildMergedHeader(v: LegHeader, a: LegHeader?): MergedHeader {
    val ftyp = v.ftyp ?: mp4Box(
        "ftyp",
        "isom".toByteArray(Charsets.ISO_8859_1), byteArrayOf(0, 0, 2, 0),
        "isomiso6mp41".toByteArray(Charsets.ISO_8859_1)
    )
    val audioId = if (a == null) null else if (a.trackId == v.trackId) v.trackId + 1 else a.trackId
    val mvhd = v.mvhd.copyOf()
    mvhd.put32(mvhd.size - 4, maxOf(v.trackId, audioId ?: 0) + 1) // next_track_ID is mvhd's last field in both versions
    val parts = ArrayList<ByteArray>()
    parts += mvhd
    parts += v.trak
    val trexes = ArrayList<ByteArray>()
    trexes += v.trex
    if (a != null && audioId != null) {
        val trak = a.trak.copyOf()
        trak.put32(a.trackIdOffsetInTrak, audioId)
        val trex = a.trex.copyOf()
        trex.put32(12, audioId)
        parts += trak
        trexes += trex
    }
    parts += mp4Box("mvex", *trexes.toTypedArray())
    val moov = mp4Box("moov", *parts.toTypedArray())
    return MergedHeader(ftyp + moov, v.trackId, audioId)
}

internal class AssemblyResult(
    val ok: Boolean,
    val bytesWritten: Long,
    val fragmentsWritten: Int,
    val videoEndMs: Long,
    val audioEndMs: Long?,
    /** Presentation time of the first video fragment minus that of the first audio fragment (priming excluded); null without audio. */
    val startOffsetMs: Long?,
    val reason: String?
)

/**
 * Merges a video leg and an audio leg into one fragmented mp4 on [sink] as the
 * two encoders produce them.
 *
 * Nothing is written for [startupWaitMs] of wall-clock time, so the encoders
 * get a head start and the first portion is a real run of media rather than a
 * fragment or two (the wait ends early if the legs finish or fail first). After
 * that, each pass takes every complete fragment that both legs have covered so
 * far -- the "horizon" is the earlier of the two legs' coverage -- and writes
 * them in presentation order, then waits for the next. Because a portion never
 * runs past what *both* legs have, the two tracks advance together, and because
 * fragments are whole and in order, a viewer or a later remux sees one
 * continuous stream.
 *
 * A leg that fails caps the horizon at its own end, so what was written is a
 * clean, in-sync prefix; the caller decides what to do with a prefix.
 *
 * Writing to [sink] may block (a renderer reading slowly). That is intended:
 * it stops the assembler consuming, the legs' queues fill, and for pipe legs
 * the encoders are held back in turn.
 */
internal class FragmentAssembler(
    private val video: FragmentLeg,
    private val audio: FragmentLeg?,
    private val label: String,
    private val startupWaitMs: Long
) {
    suspend fun run(sink: OutputStream): AssemblyResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        var videoWritten = 0
        var audioWritten = 0
        var sequence = 1
        var bytes = 0L
        var merged: MergedHeader? = null
        var headerWritten = false
        var lastLogAt = startedAt
        var firstVideoStartMs: Long? = null
        var firstAudioStartMs: Long? = null
        val readers = HashMap<FragmentLeg, RandomAccessFile>()
        val copyBuffer = ByteArray(64 * 1024)

        fun write(b: ByteArray, off: Int, len: Int) { sink.write(b, off, len); bytes += len }

        fun patch(moof: ByteArray, layout: MoofLayout, newTrackId: Int, relocation: Long, lastDurationOverride: Long) {
            moof.put32(layout.sequenceOffset, sequence++)
            if (lastDurationOverride >= 0L && layout.lastSampleDurationOffset >= 0) {
                moof.put32(layout.lastSampleDurationOffset, lastDurationOverride.toInt())
            }
            if (layout.trackId != newTrackId) moof.put32(layout.trackIdOffset, newTrackId)
            if (layout.baseDataOffsetOffset >= 0) {
                // An absolute base follows the bytes to their new position.
                moof.put64(layout.baseDataOffsetOffset, moof.u64(layout.baseDataOffsetOffset) + relocation)
            }
        }

        fun writeFragment(leg: FragmentLeg, h: LegHeader, f: LegFragment, newTrackId: Int) {
            val data = f.data
            if (data != null) {
                val layout = parseMoof(data.copyOfRange(0, f.moofSize), h.trexDefaultDuration)
                    ?: throw IllegalStateException("${leg.label}: a queued fragment stopped parsing")
                patch(data, layout, newTrackId, bytes - f.byteStart, f.lastDurationOverride)
                write(data, 0, data.size)
                return
            }
            val raf = readers.getOrPut(leg) { RandomAccessFile(leg.path, "r") }
            val moof = ByteArray(f.moofSize)
            raf.seek(f.byteStart)
            raf.readFully(moof)
            val layout = parseMoof(moof, h.trexDefaultDuration)
                ?: throw IllegalStateException("${leg.label}: fragment at ${f.byteStart} stopped parsing")
            patch(moof, layout, newTrackId, bytes - f.byteStart, f.lastDurationOverride)
            write(moof, 0, moof.size)
            raf.seek(f.byteStart + f.moofSize)
            var left = f.byteEnd - (f.byteStart + f.moofSize)
            while (left > 0) {
                val n = raf.read(copyBuffer, 0, minOf(copyBuffer.size.toLong(), left).toInt())
                if (n < 0) throw IllegalStateException("${leg.label}: fragment at ${f.byteStart} is shorter on disk than indexed")
                write(copyBuffer, 0, n)
                left -= n
            }
        }

        fun result(ok: Boolean, reason: String?): AssemblyResult {
            val offset = if (firstVideoStartMs != null && firstAudioStartMs != null) firstVideoStartMs!! - firstAudioStartMs!!.coerceAtLeast(0L) else null
            return AssemblyResult(
                ok = ok, bytesWritten = bytes, fragmentsWritten = videoWritten + audioWritten,
                videoEndMs = video.coveredEndMs(), audioEndMs = audio?.coveredEndMs(),
                startOffsetMs = offset, reason = reason
            )
        }

        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                // Flags first, fragments after: a leg sets `finished` only once everything is queued.
                val videoFinished = video.finished
                val audioFinished = audio?.finished ?: true
                val allFinished = videoFinished && audioFinished
                val anyFailed = video.failed || audio?.failed == true
                val waited = (System.nanoTime() - startedAt) / 1_000_000L >= startupWaitMs

                if (waited || allFinished || anyFailed) {
                    val vh = video.header
                    val ah = audio?.header
                    if (merged == null && vh != null && (audio == null || ah != null)) merged = buildMergedHeader(vh, ah)
                    val hdr = merged
                    if (hdr != null && vh != null) {
                        // A leg that is still running caps the horizon at what it has; a failed one at what it
                        // got to; a finished one not at all.
                        var horizon = Long.MAX_VALUE
                        if (!videoFinished) horizon = minOf(horizon, video.coveredEndMs())
                        if (audio != null && !audioFinished) horizon = minOf(horizon, audio.coveredEndMs())

                        var wrote = false
                        while (true) {
                            val vf = video.peek()?.takeIf { presentationMs(it.endTicks, vh) <= horizon }
                            val af = if (audio != null && ah != null) audio.peek()?.takeIf { presentationMs(it.endTicks, ah) <= horizon } else null
                            if (vf == null && af == null) break
                            val takeVideo = when {
                                vf == null -> false
                                af == null -> true
                                else -> presentationMs(vf.startTicks, vh) <= presentationMs(af.startTicks, ah!!)
                            }
                            if (!headerWritten) {
                                write(hdr.bytes, 0, hdr.bytes.size)
                                headerWritten = true
                            }
                            if (takeVideo) {
                                if (firstVideoStartMs == null) firstVideoStartMs = presentationMs(vf!!.startTicks, vh)
                                writeFragment(video, vh, vf!!, hdr.videoTrackId)
                                video.pop(); videoWritten++
                            } else {
                                if (firstAudioStartMs == null) firstAudioStartMs = presentationMs(af!!.startTicks, ah!!)
                                writeFragment(audio!!, ah!!, af!!, hdr.audioTrackId!!)
                                audio.pop(); audioWritten++
                            }
                            wrote = true
                        }
                        if (wrote) sink.flush()
                    }
                }

                val nowNs = System.nanoTime()
                if (headerWritten && (nowNs - lastLogAt) / 1_000_000L >= 10_000L) {
                    lastLogAt = nowNs
                    Log.d(ASSEMBLER_TAG, "$label: assembled ${videoWritten + audioWritten} fragments (video $videoWritten, " +
                        "audio $audioWritten), ${bytes / 1024} KiB; queued video ${video.queuedMs()}ms, audio ${audio?.queuedMs() ?: 0}ms")
                }

                if (anyFailed) {
                    val why = listOfNotNull(video.failure?.let { "video: $it" }, audio?.failure?.let { "audio: $it" }).joinToString("; ")
                    return@withContext result(false, why)
                }
                if (allFinished && video.peek() == null && audio?.peek() == null) {
                    if (!headerWritten) return@withContext result(false, "nothing to write")
                    sink.flush()
                    return@withContext result(true, null)
                }
                delay(PIPE_POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            readers.values.forEach { try { it.close() } catch (_: IOException) {} }
        }
    }
}
