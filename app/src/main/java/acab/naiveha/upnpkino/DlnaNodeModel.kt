package acab.naiveha.upnpkino

import android.util.Log

data class DlnaNodeModel(
    val id: String,
    val friendlyName: String,
    val location: String,
    val urlBase: String?,
    val controlUrls: Map<String, String>,
    val eventUrls: Map<String, String>
) : SelectorItem {
    override val selectionId: String get() = id
    override val displayLabel: String get() = friendlyName
    override val secondaryLabel: String get() = location
    override val iconResId: Int get() = R.drawable.movie_cast
    override val isContainer: Boolean get() = false

    var mediaCollection: Map<String, Configuration.MediaNode> = emptyMap()
        private set

    fun setMediaCollection(collection: Map<String, Configuration.MediaNode>) {
        Log.d("DlnaNodeModel", "setMediaCollection for $friendlyName: count=${collection.size}")
        mediaCollection = collection
    }
}
