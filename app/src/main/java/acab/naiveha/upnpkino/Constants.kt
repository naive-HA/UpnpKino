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
    val movieExtensions = mimeType.filter { it.value.contains("video/") }.keys
    val musicExtensions = mimeType.filter { it.value.contains("audio/") }.keys
}
