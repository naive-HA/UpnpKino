package acab.naiveha.upnpkino

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

class Preferences(context: Context) {

    private val sharedPreferences = context.getSharedPreferences("UPnPStreamerPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val LOCAL_MOVIE_FOLDER_URI = "local_movie_folder_uri"
        private const val LOCAL_MUSIC_FOLDER_URI = "local_music_folder_uri"
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
