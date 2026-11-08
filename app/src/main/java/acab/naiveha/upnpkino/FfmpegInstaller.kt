package acab.naiveha.upnpkino

import android.content.Context
import android.util.Log
import java.io.File

object FfmpegInstaller {

    private const val TAG = "FfmpegInstaller"
    fun ffmpegBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")

    fun ffprobeBinary(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, "libffprobe.so")

    @Deprecated("Use ffmpegBinary instead", ReplaceWith("ffmpegBinary(context)"))
    fun binaryFile(context: Context): File = ffmpegBinary(context)

    fun install(context: Context) {
        // Cleanup old installation if it exists (legacy versions copied to filesDir)
        listOf("ffmpeg", "ffprobe").forEach { name ->
            val oldDest = File(context.filesDir, name)
            if (oldDest.exists()) {
                Log.d(TAG, "Cleaning up legacy $name binary from ${oldDest.absolutePath}")
                oldDest.delete()
            }
        }

        val ffmpeg = ffmpegBinary(context)
        val ffprobe = ffprobeBinary(context)

        listOf(ffmpeg, ffprobe).forEach { binary ->
            if (!binary.exists()) {
                val libs = File(context.applicationInfo.nativeLibraryDir).list()?.joinToString() ?: "null"
                Log.e(TAG, "Binary not found at ${binary.absolutePath}. Available libs: $libs")
            } else {
                Log.d(TAG, "Binary found at ${binary.absolutePath} (${binary.length()} bytes)")
            }
        }
    }
}
