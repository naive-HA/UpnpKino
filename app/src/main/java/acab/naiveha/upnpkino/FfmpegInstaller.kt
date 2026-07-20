package acab.naiveha.upnpkino

import android.content.Context
import android.util.Log
import java.io.File
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat

object FfmpegInstaller {

    private const val TAG = "FfmpegInstaller"
    private const val PREFS_NAME = "ffmpeg_prefs"
    private const val KEY_INSTALLED_VERSION = "installed_version"
    fun binaryFile(context: Context): File =
        File(context.filesDir, "ffmpeg")
    fun install(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentVersion = currentVersionCode(context)
        val installedVersion = prefs.getLong(KEY_INSTALLED_VERSION, -1L)
        val dest = binaryFile(context)
        if (dest.exists() && installedVersion == currentVersion) {
            Log.d(TAG, "FFmpeg already installed (version $currentVersion)")
            return
        }
        val source = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!source.exists()) {
            val libs = File(context.applicationInfo.nativeLibraryDir).list()?.joinToString() ?: "null"
            Log.e(TAG, "FFmpeg source not found at ${source.absolutePath}. Available libs: $libs")
        }
        Log.d(TAG, "Installing FFmpeg from ${source.absolutePath} → ${dest.absolutePath}")
        try {
            source.inputStream().use { input ->
                dest.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 65_536)
                }
            }
            dest.setExecutable(true, false)
            prefs.edit { putLong(KEY_INSTALLED_VERSION, currentVersion) }
            Log.d(TAG, "FFmpeg installed successfully (${dest.length()} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg installation failed: ${e.message}", e)
            dest.delete()
        }
    }
    private fun currentVersionCode(context: Context): Long {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(packageInfo)
    }
}
