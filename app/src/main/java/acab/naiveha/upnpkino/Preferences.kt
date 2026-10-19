package acab.naiveha.upnpkino

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.UUID

class Preferences(val context: Context) {

    private val sharedPreferences = context.getSharedPreferences("UPnPStreamerPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "Preferences"
        private const val LOCAL_MOVIE_FOLDER_URI = "local_movie_folder_uri"
        private const val LOCAL_MUSIC_FOLDER_URI = "local_music_folder_uri"
        private const val DEVICE_UUID = "device_uuid"
        private const val TRANSCODER_CAPABILITIES = "transcoder_capabilities"
    }

    fun saveTranscoderCapabilities(json: String) {
        sharedPreferences.edit { putString(TRANSCODER_CAPABILITIES, json) }
    }
    fun getTranscoderCapabilities(): String? {
        return sharedPreferences.getString(TRANSCODER_CAPABILITIES, null)
    }

    fun getDeviceUuid(): String {
        var uuid = sharedPreferences.getString(DEVICE_UUID, null)
        if (uuid == null) {
            uuid = UUID.randomUUID().toString()
            sharedPreferences.edit { putString(DEVICE_UUID, uuid) }
        }
        return uuid
    }

    fun clearLocalMovieFolderUri() {
        val previous = getLocalMovieFolderUri()
        sharedPreferences.edit { putString(LOCAL_MOVIE_FOLDER_URI, null) }
        releaseGrantIfUnused(previous)
    }
    fun clearLocalMusicFolderUri() {
        val previous = getLocalMusicFolderUri()
        sharedPreferences.edit { putString(LOCAL_MUSIC_FOLDER_URI, null) }
        releaseGrantIfUnused(previous)
    }
    fun saveLocalMovieFolderUri(uri: Uri) {
        val previous = getLocalMovieFolderUri()
        sharedPreferences.edit {
            putString(LOCAL_MOVIE_FOLDER_URI, uri.toString())
        }
        if (previous != uri) releaseGrantIfUnused(previous)
    }
    fun getLocalMovieFolderUri(): Uri? {
        return sharedPreferences.getString(LOCAL_MOVIE_FOLDER_URI, null)?.toUri()
    }
    fun saveLocalMusicFolderUri(uri: Uri) {
        val previous = getLocalMusicFolderUri()
        sharedPreferences.edit {
            putString(LOCAL_MUSIC_FOLDER_URI, uri.toString())
        }
        if (previous != uri) releaseGrantIfUnused(previous)
    }
    fun getLocalMusicFolderUri(): Uri? {
        return sharedPreferences.getString(LOCAL_MUSIC_FOLDER_URI, null)?.toUri()
    }
    private fun releaseGrantIfUnused(uri: Uri?) {
        if (uri == null || uri == getLocalMovieFolderUri() || uri == getLocalMusicFolderUri()) return
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the grant for $uri: ${e.message}")
        }
    }
}
