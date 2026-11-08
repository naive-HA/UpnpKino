package acab.naiveha.upnpkino

interface SelectorItem {
    val selectionId: String
    val displayLabel: String
    val secondaryLabel: String
    val iconResId: Int
    val isContainer: Boolean
    val parentId: String? get() = null
    val children: List<String> get() = emptyList()
}

interface SelectorFragmentObject {
    fun getItem(id: String): SelectorItem?
    fun getAllItems(): Collection<SelectorItem>
    fun onItemSelected(id: String)
}

interface SelectorProvider {
    fun castToSelectorFragmentObject(): SelectorFragmentObject
}

fun Any.castToSelectorFragmentObject(): SelectorFragmentObject {
    return (this as SelectorProvider).castToSelectorFragmentObject()
}

class SelectorMap<T : SelectorItem>(
    val items: Map<String, T>,
    private val selectionHandler: (String) -> Unit
) : SelectorProvider {
    override fun castToSelectorFragmentObject() = object : SelectorFragmentObject {
        override fun getItem(id: String) = items[id]
        override fun getAllItems() = items.values
        override fun onItemSelected(id: String) = selectionHandler(id)
    }
}
