package acab.naiveha.upnpkino

object Constants {
    val mimeType = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "wmv" to "video/x-ms-wmv",
        "webm" to "video/webm",
        "mp3" to "audio/mpeg",
        "m4a" to "audio/mpeg",
        "aac" to "audio/aac",
        "flac" to "audio/x-flac",
        "wav" to "audio/x-wav",
        "opus" to "audio/ogg")

    val dlnaProfiles4Images = mapOf(
        "icon_png" to "PNG_TN",
        "large_png" to "PNG_LRG",
        "icon_jpeg" to "JPEG_TN",
        "small_jpeg" to "JPEG_SM",
        "medium_jpeg" to "JPEG_MED")
    val movieExtensions = mimeType.filter { it.value.contains("video/") }.keys
    val musicExtensions = mimeType.filter { it.value.contains("audio/") }.keys
}
