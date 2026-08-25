package acab.naiveha.upnpkino

import android.util.Log

data class DlnaNodeModel(
    val id: String,
    override val friendlyName: String,
    val location: String,
    override val urlBase: String?,
    override val serviceUrns: Map<String, String>,
    override val controlUrls: Map<String, String>,
    override val eventUrls: Map<String, String>
) : UpnpNodeModel(friendlyName, urlBase, serviceUrns, controlUrls, eventUrls), SelectorItem {
    override val selectionId: String get() = id
    override val displayLabel: String get() = friendlyName
    override val secondaryLabel: String get() = location
    override val iconResId: Int get() = R.drawable.movie_cast
    override val isContainer: Boolean get() = false

    var mediaCollection: Map<String, MediaCollection.MediaNode> = emptyMap()
        private set

    fun setMediaCollection(collection: Map<String, MediaCollection.MediaNode>) {
        Log.d("DlnaNodeModel", "setMediaCollection for $friendlyName: count=${collection.size}")
        mediaCollection = collection
    }
}
