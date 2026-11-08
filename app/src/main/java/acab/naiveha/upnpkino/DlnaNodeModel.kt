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
    var sinkProfiles: SinkCapabilities = SinkCapabilities(emptyList(), isWildcard = true)
        private set

    fun setMediaCollection(collection: Map<String, MediaCollection.MediaNode>) {
        Log.d("DlnaNodeModel", "setMediaCollection for $friendlyName: count=${collection.size}")
        mediaCollection = collection
    }

    fun setSinkProfiles(capabilities: SinkCapabilities) {
        Log.d("DlnaNodeModel", "setSinkCapabilities for $friendlyName: $capabilities")
        sinkProfiles = capabilities
    }

    fun readSinkProfilesFromProtocolInfo(rawSinkString: String): SinkCapabilities {
        if (rawSinkString.isBlank() || rawSinkString.contains("*:*:*") || rawSinkString.contains("video/*")) {
            val capabilities = SinkCapabilities(emptyList(), isWildcard = true)
            setSinkProfiles(capabilities)
            return capabilities
        }
        val parsedProfiles = rawSinkString.split(",")
            .map { it.trim() }
            .filter { it.startsWith("http-get:") }
            .mapNotNull { parseSingleSink(it) }
        val isWildcardDetected = rawSinkString.contains("*") && parsedProfiles.isEmpty() // TODO reconsider defaulting to *
        val capabilities = SinkCapabilities(parsedProfiles, isWildcard = isWildcardDetected)
        setSinkProfiles(capabilities)
        return capabilities
    }

    private fun parseSingleSink(sinkString: String): SinkProfile? {
        val parts = sinkString.split(":")
        if (parts.size < 3) return null
        val mimeType = parts[2].lowercase()
        val dlnaFeatures = parts.getOrNull(3)?.uppercase() ?: ""
        val container = Constants.Transcoder.Container.entries
            .mapNotNull { candidate ->
                candidate.dlnaProfileAliases
                    .filter { alias -> mimeType.contains(alias, ignoreCase = true) }
                    .maxByOrNull { it.length }
                    ?.let { matchedAlias -> candidate to matchedAlias.length }
            }
            .maxByOrNull { it.second }
            ?.first
        val videoCodec = Constants.Transcoder.VideoCodec.entries
            .mapNotNull { candidate ->
                candidate.dlnaProfileAliases
                    .filter { alias -> dlnaFeatures.contains(alias, ignoreCase = true) || mimeType.contains(alias, ignoreCase = true) }
                    .maxByOrNull { it.length }
                    ?.let { matchedAlias -> candidate to matchedAlias.length }
            }
            .maxByOrNull { it.second }
            ?.first
        val maxResolution = Constants.Transcoder.Resolution.entries
            .mapNotNull { candidate ->
                candidate.dlnaProfileAliases
                    .filter { alias -> dlnaFeatures.contains(alias, ignoreCase = true) }
                    .maxByOrNull { it.length }
                    ?.let { matchedAlias -> candidate to matchedAlias.length }
            }
            .maxByOrNull { it.second }
            ?.first
        val audioCodec = Constants.Transcoder.AudioCodec.entries
            .mapNotNull { candidate ->
                candidate.dlnaProfileAliases
                    .filter { alias -> dlnaFeatures.contains(alias, ignoreCase = true) || mimeType.contains(alias, ignoreCase = true) }
                    .maxByOrNull { it.length }
                    ?.let { matchedAlias -> candidate to matchedAlias.length }
            }
            .maxByOrNull { it.second }
            ?.first
        val maxChannels = Constants.Transcoder.AudioQuality.entries
            .mapNotNull { candidate ->
                candidate.dlnaProfileAliases
                    .filter { alias -> dlnaFeatures.contains(alias, ignoreCase = true) }
                    .maxByOrNull { it.length }
                    ?.let { matchedAlias -> candidate.channelCount to matchedAlias.length }
            }
            .maxByOrNull { it.second }
            ?.first
        val videoProfile = videoCodec?.let { codec ->
            codec.profileMapping.entries
                .filter { (name, _) -> dlnaFeatures.contains(name, ignoreCase = true) || mimeType.contains(name, ignoreCase = true) }
                .maxByOrNull { it.key.length }
                ?.value
        }
        return SinkProfile(
            videoCodec = videoCodec,
            audioCodec = audioCodec,
            maxResolution = maxResolution,
            maxChannels = maxChannels,
            container = container,
            videoProfile = videoProfile
        )
    }
}

data class SinkProfile(
    val videoCodec: Constants.Transcoder.VideoCodec?,
    val audioCodec: Constants.Transcoder.AudioCodec?,
    val maxResolution: Constants.Transcoder.Resolution?,
    val maxChannels: Int?,
    val container: Constants.Transcoder.Container?,
    val videoProfile: Int?
)

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

    fun supportsVideoProfile(codec: Constants.Transcoder.VideoCodec, profile: Int?): Boolean {
        if (isWildcard || profiles.isEmpty() || profile == null) return true
        return profiles.any { p ->
            (p.videoCodec == null || p.videoCodec == codec) &&
            (p.videoProfile == null || p.videoProfile >= profile)
        }
    }

    fun getBestVideoProfile(
        videoCodec: Constants.Transcoder.VideoCodec?,
        resolution: Constants.Transcoder.Resolution? = null,
        audioCodec: Constants.Transcoder.AudioCodec? = null,
        audioQuality: Constants.Transcoder.AudioQuality? = null,
        container: Constants.Transcoder.Container? = null
    ): Int? {
        if (isWildcard || profiles.isEmpty()) return null
        return profiles
            .filter { p ->
                (videoCodec == null || p.videoCodec == null || p.videoCodec == videoCodec) &&
                (resolution == null || p.maxResolution == null || p.maxResolution.maxHeight >= resolution.maxHeight) &&
                (audioCodec == null || p.audioCodec == null || p.audioCodec == audioCodec) &&
                (audioQuality == null || p.maxChannels == null || p.maxChannels >= audioQuality.channelCount) &&
                (container == null || p.container == null || p.container == container)
            }
            .mapNotNull { it.videoProfile }
            .maxOrNull()
    }

    fun supportsCandidateCombination(
        videoCodec: Constants.Transcoder.VideoCodec?,
        resolution: Constants.Transcoder.Resolution?,
        audioCodec: Constants.Transcoder.AudioCodec?,
        audioQuality: Constants.Transcoder.AudioQuality?,
        container: Constants.Transcoder.Container?,
        videoProfile: Int? = null
    ): Boolean {
        if (isWildcard || profiles.isEmpty()) return true
        return profiles.any { p ->
            (videoCodec == null || p.videoCodec == null || p.videoCodec == videoCodec) &&
            (resolution == null || p.maxResolution == null || p.maxResolution.maxHeight >= resolution.maxHeight) &&
            (audioCodec == null || p.audioCodec == null || p.audioCodec == audioCodec) &&
            (audioQuality == null || p.maxChannels == null || p.maxChannels >= audioQuality.channelCount) &&
            (container == null || p.container == null || p.container == container) &&
            (videoProfile == null || p.videoProfile == null || p.videoProfile >= videoProfile)
        }
    }
}
