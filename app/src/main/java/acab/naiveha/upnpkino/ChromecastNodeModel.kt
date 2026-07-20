package acab.naiveha.upnpkino

import java.net.InetAddress

data class ChromecastNodeModel(
    val name:         String,
    val friendlyName: String,
    val host:         InetAddress,
    val port:         Int,
) : SelectorItem {
    override val selectionId:    String  get() = name
    override val displayLabel:   String  get() = friendlyName
    override val secondaryLabel: String  get() = "${host.hostAddress}:$port"
    override val iconResId:      Int     get() = R.drawable.chromecast
    override val isContainer:    Boolean get() = false

    var mediaCollection: Map<String, Configuration.MediaNode> = emptyMap()
        private set

    fun setMediaCollection(collection: Map<String, Configuration.MediaNode>) {
        val filtered = collection.toMutableMap()
        val idsToRemove = filtered.filter { (_, node) ->
            node is Configuration.MediaNode.Item && false //node.chromecastTranscoding
        }.keys.toSet()

        for (id in idsToRemove) {
            filtered.remove(id)
        }

        for ((id, node) in filtered) {
            if (node is Configuration.MediaNode.Container) {
                val newChildren = node.children.filter { it !in idsToRemove }
                if (newChildren.size != node.children.size) {
                    filtered[id] = node.copy(children = newChildren)
                }
            }
        }

        var modified: Boolean
        do {
            modified = false
            val nodesToRemove = mutableListOf<String>()
            for ((id, node) in filtered) {
                if (node is Configuration.MediaNode.Container && node.children.isEmpty() && id != "0" && id != "1") {
                    nodesToRemove.add(id)
                    modified = true
                }
            }
            for (idToRemove in nodesToRemove) {
                filtered.remove(idToRemove)
                for ((id, node) in filtered) {
                    if (node is Configuration.MediaNode.Container && node.children.contains(idToRemove)) {
                        val newChildren = node.children.toMutableList()
                        newChildren.remove(idToRemove)
                        filtered[id] = node.copy(children = newChildren)
                    }
                }
            }
        } while (modified)
        
        android.util.Log.d("ChromecastNodeModel", "setMediaCollection for $friendlyName: count=${filtered.size} (original collection size was ${collection.size})")
        mediaCollection = filtered.toMap()
    }
}
