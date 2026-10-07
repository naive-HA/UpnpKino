package acab.naiveha.upnpkino

import android.util.Log

data class DlnaNodeModel(
    val id: String,
    override val friendlyName: String,
    val location: String,
    override val urlBase: String?,
    override val serviceUrns: Map<String, String>,
    override val controlUrls: Map<String, String>,
    override val eventUrls: Map<String, String>
) : UpnpNodeModel(friendlyName, urlBase, serviceUrns, controlUrls, eventUrls), SelectorItem {
    override val selectionId: String get() = id
    override val displayLabel: String get() = friendlyName
    override val secondaryLabel: String get() = location
    override val iconResId: Int get() = R.drawable.movie_cast
    override val isContainer: Boolean get() = false

    var mediaCollection: Map<String, MediaCollection.MediaNode> = emptyMap()
        private set

    var sinkCapabilities: SinkCapabilities = SinkCapabilities(emptyList(), isWildcard = true)
        private set

    fun setMediaCollection(collection: Map<String, MediaCollection.MediaNode>) {
        Log.d("DlnaNodeModel", "setMediaCollection for $friendlyName: count=${collection.size}")
        mediaCollection = collection
    }

    fun setSinkCapabilities(capabilities: SinkCapabilities) {
        Log.d("DlnaNodeModel", "setSinkCapabilities for $friendlyName: $capabilities")
        sinkCapabilities = capabilities
    }

    /**
     * Parses a raw DLNA comma-separated SinkProtocolInfo response string into SinkCapabilities and updates this node.
     */
    fun fromProtocolInfo(rawSinkString: String): SinkCapabilities {
        if (rawSinkString.isBlank() || rawSinkString.contains("*:*:*") || rawSinkString.contains("video/*")) {
            val caps = SinkCapabilities(emptyList(), isWildcard = true)
            setSinkCapabilities(caps)
            return caps
        }

        val parsedProfiles = rawSinkString.split(",")
            .map { it.trim() }
            .filter { it.startsWith("http-get:") }
            .mapNotNull { parseSingleSink(it) }

        val isWildcardDetected = rawSinkString.contains("*") && parsedProfiles.isEmpty() // TODO reconsider defaulting to *

        val caps = SinkCapabilities(parsedProfiles, isWildcard = isWildcardDetected)
        setSinkCapabilities(caps)
        return caps
    }

    private fun parseSingleSink(sinkString: String): SinkProfile? {
        val parts = sinkString.split(":")
        if (parts.size < 3) return null

        val mimeType = parts[2].lowercase()
        val dlnaFeatures = parts.getOrNull(3)?.uppercase() ?: ""

        // Resolve Container from MIME type
        val container = when {
            mimeType.contains("mp4") -> Constants.Transcoder.Container.MP4
            mimeType.contains("matroska") || mimeType.contains("mkv") || mimeType.contains("webm") -> Constants.Transcoder.Container.MKV
            mimeType.contains("mpegts") || mimeType.contains("transportstream") || mimeType.contains("ts") -> Constants.Transcoder.Container.MPEG_TS
            else -> null
        }

        // Resolve Video Codec from MIME or DLNA.ORG_PN profile name
        val videoCodec = when {
            dlnaFeatures.contains("HEVC") || mimeType.contains("hevc") || mimeType.contains("h265") -> Constants.Transcoder.VideoCodec.HEVC
            dlnaFeatures.contains("AVC") || mimeType.contains("h264") || mimeType.contains("avc") -> Constants.Transcoder.VideoCodec.H264
            dlnaFeatures.contains("AV1") || mimeType.contains("av1") || mimeType.contains("av01") -> Constants.Transcoder.VideoCodec.AV1
            dlnaFeatures.contains("VP9") || mimeType.contains("vp9") || mimeType.contains("vp09") -> Constants.Transcoder.VideoCodec.VP9
            dlnaFeatures.contains("VP8") || mimeType.contains("vp8") -> Constants.Transcoder.VideoCodec.VP8
            mimeType.contains("mpeg4") || mimeType.contains("mp4v") -> Constants.Transcoder.VideoCodec.MPEG4
            mimeType.contains("mpeg2") || mimeType.contains("mpeg-2") -> Constants.Transcoder.VideoCodec.MPEG2
            mimeType.contains("wmv3") || mimeType.contains("wmv") -> Constants.Transcoder.VideoCodec.WMV3
            mimeType.contains("vc1") || mimeType.contains("vc-1") -> Constants.Transcoder.VideoCodec.VC1
            mimeType.contains("mjpeg") || mimeType.contains("jpeg") -> Constants.Transcoder.VideoCodec.MJPEG
            mimeType.contains("prores") -> Constants.Transcoder.VideoCodec.PRORES
            mimeType.contains("dnxhd") -> Constants.Transcoder.VideoCodec.DNXHD
            else -> null
        }

        // Resolve Resolution from DLNA profile string
        val maxResolution = when {
            dlnaFeatures.contains("4K") || dlnaFeatures.contains("2160") || dlnaFeatures.contains("UHD") -> Constants.Transcoder.Resolution.UHD_4K
            dlnaFeatures.contains("1080") || dlnaFeatures.contains("FHD") -> Constants.Transcoder.Resolution.HD_1080
            dlnaFeatures.contains("720") || dlnaFeatures.contains("HD") -> Constants.Transcoder.Resolution.HD_720
            dlnaFeatures.contains("SD") || dlnaFeatures.contains("480") || dlnaFeatures.contains("576") -> Constants.Transcoder.Resolution.SD
            else -> null // Wildcard / unspecified resolution
        }

        // Resolve Audio Codec from MIME or DLNA.ORG_PN profile name
        val audioCodec = when {
            dlnaFeatures.contains("TRUEHD") || mimeType.contains("truehd") -> Constants.Transcoder.AudioCodec.TRUEHD
            dlnaFeatures.contains("MLP") || mimeType.contains("mlp") -> Constants.Transcoder.AudioCodec.MLP
            dlnaFeatures.contains("FLAC") || mimeType.contains("flac") -> Constants.Transcoder.AudioCodec.FLAC
            dlnaFeatures.contains("ALAC") || mimeType.contains("alac") -> Constants.Transcoder.AudioCodec.ALAC
            dlnaFeatures.contains("DCA") || dlnaFeatures.contains("DTS") || mimeType.contains("dts") || mimeType.contains("dca") -> Constants.Transcoder.AudioCodec.DCA
            dlnaFeatures.contains("EAC3") || dlnaFeatures.contains("EC3") || mimeType.contains("eac3") || mimeType.contains("ec-3") -> Constants.Transcoder.AudioCodec.EAC3
            dlnaFeatures.contains("AC3") || mimeType.contains("ac3") -> Constants.Transcoder.AudioCodec.AC3
            dlnaFeatures.contains("AAC") || mimeType.contains("aac") || mimeType.contains("mp4a") -> Constants.Transcoder.AudioCodec.AAC
            dlnaFeatures.contains("OPUS") || mimeType.contains("opus") -> Constants.Transcoder.AudioCodec.OPUS
            dlnaFeatures.contains("VORBIS") || mimeType.contains("vorbis") -> Constants.Transcoder.AudioCodec.VORBIS
            dlnaFeatures.contains("MP3") || mimeType.contains("mp3") || mimeType.contains("mpeg") -> Constants.Transcoder.AudioCodec.MP3
            mimeType.contains("pcm") || mimeType.contains("raw") -> Constants.Transcoder.AudioCodec.PCM_S16LE
            mimeType.contains("wma") -> Constants.Transcoder.AudioCodec.WMAV2
            else -> null
        }

        val maxChannels = when {
            dlnaFeatures.contains("7_1") || dlnaFeatures.contains("8CH") -> 8
            dlnaFeatures.contains("5_1") || dlnaFeatures.contains("6CH") -> 6
            dlnaFeatures.contains("2CH") || dlnaFeatures.contains("STEREO") -> 2
            else -> null
        }

        return SinkProfile(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            maxResolution = maxResolution,
            maxChannels = maxChannels,
            container = container
        )
    }
}

/**
 * Represents a single parsed DLNA sink profile entry from a protocolInfo list.
 */
data class SinkProfile(
    val videoCodec: Constants.Transcoder.VideoCodec?,
    val audioCodec: Constants.Transcoder.AudioCodec?,
    val maxResolution: Constants.Transcoder.Resolution?,
    val maxChannels: Int?,
    val container: Constants.Transcoder.Container?
)

/**
 * Aggregates all advertised sinks from a DLNA device.
 */
data class SinkCapabilities(
    val profiles: List<SinkProfile>,
    val isWildcard: Boolean = false
) {
    fun supportsVideoCodec(codec: Constants.Transcoder.VideoCodec): Boolean =
        isWildcard || profiles.isEmpty() || profiles.any { it.videoCodec == null || it.videoCodec == codec }

    fun supportsResolution(resolution: Constants.Transcoder.Resolution): Boolean =
        isWildcard || profiles.isEmpty() || profiles.any { it.maxResolution == null || it.maxResolution.maxHeight >= resolution.maxHeight }

    fun supportsAudioCodec(codec: Constants.Transcoder.AudioCodec): Boolean =
        isWildcard || profiles.isEmpty() || profiles.any { it.audioCodec == null || it.audioCodec == codec }

    fun supportsChannelCount(channels: Int): Boolean =
        isWildcard || profiles.isEmpty() || profiles.any { it.maxChannels == null || it.maxChannels >= channels }

    fun supportsContainer(container: Constants.Transcoder.Container): Boolean =
        isWildcard || profiles.isEmpty() || profiles.any { it.container == null || it.container == container }

    fun supportsCombination(
        videoCodec: Constants.Transcoder.VideoCodec,
        resolution: Constants.Transcoder.Resolution,
        audioCodec: Constants.Transcoder.AudioCodec,
        audioQuality: Constants.Transcoder.AudioQuality,
        container: Constants.Transcoder.Container
    ): Boolean {
        if (isWildcard || profiles.isEmpty()) return true
        return profiles.any { p ->
            (p.videoCodec == null || p.videoCodec == videoCodec) &&
            (p.maxResolution == null || p.maxResolution.maxHeight >= resolution.maxHeight) &&
            (p.audioCodec == null || p.audioCodec == audioCodec) &&
            (p.maxChannels == null || p.maxChannels >= audioQuality.channelCount) &&
            (p.container == null || p.container == container)
        }
    }
}
