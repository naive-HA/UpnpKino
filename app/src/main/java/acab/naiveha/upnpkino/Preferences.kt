package acab.naiveha.upnpkino

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

class Preferences(context: Context) {

    private val sharedPreferences = context.getSharedPreferences("UPnPStreamerPrefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_FOLDER_URI = "folder_uri"
    }

    fun saveFolderUri(uri: Uri) {
        sharedPreferences.edit {
            putString(KEY_FOLDER_URI, uri.toString())
        }
    }

    fun getFolderUri(): Uri? {
        return sharedPreferences.getString(KEY_FOLDER_URI, null)?.toUri()
    }
}
