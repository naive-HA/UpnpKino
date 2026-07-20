package acab.naiveha.upnpkino

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri
import java.util.UUID

class Preferences(val context: Context) {

    private val sharedPreferences = context.getSharedPreferences("UPnPStreamerPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val LOCAL_MOVIE_FOLDER_URI = "local_movie_folder_uri"
        private const val LOCAL_MUSIC_FOLDER_URI = "local_music_folder_uri"
        private const val DEVICE_UUID = "device_uuid"
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
        sharedPreferences.edit { putString(LOCAL_MOVIE_FOLDER_URI, null) }
    }
    fun clearLocalMusicFolderUri() {
        sharedPreferences.edit { putString(LOCAL_MUSIC_FOLDER_URI, null) }
    }
    fun saveLocalMovieFolderUri(uri: Uri) {
        sharedPreferences.edit {
            putString(LOCAL_MOVIE_FOLDER_URI, uri.toString())
        }
    }
    fun getLocalMovieFolderUri(): Uri? {
        return sharedPreferences.getString(LOCAL_MOVIE_FOLDER_URI, null)?.toUri()
    }
    fun saveLocalMusicFolderUri(uri: Uri) {
        sharedPreferences.edit {
            putString(LOCAL_MUSIC_FOLDER_URI, uri.toString())
        }
    }
    fun getLocalMusicFolderUri(): Uri? {
        return sharedPreferences.getString(LOCAL_MUSIC_FOLDER_URI, null)?.toUri()
    }
}
