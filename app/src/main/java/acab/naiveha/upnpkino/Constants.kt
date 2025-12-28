package acab.naiveha.upnpkino

object Constants {
    val videoExtensions = setOf("mp4", "mkv", "avi", "mov", "wmv", "webm")
    val mimeType = mapOf(
        "mp4" to "video/mp4",
        "mkv" to "video/matroska",
        "avi" to "video/x-msvideo",
        "mov" to "video/quicktime",
        "wmv" to "video/x-ms-wmv",
        "webm" to "video/webm")
}
