package acab.naiveha.upnpkino

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import acab.naiveha.upnpkino.databinding.FragmentSelectorBinding

class SelectorFragment : DialogFragment() {

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_SOURCE_ID = "source_id"
        private const val ARG_SELECTED_ITEM_ID = "selected_item_id"

        fun newInstance(title: String, anyObject: Any, selectedItemId: String? = null): SelectorFragment {
            val fragment = SelectorFragment()
            val args = Bundle()
            args.putString(ARG_TITLE, title)
            args.putString(ARG_SOURCE_ID, UpnpRepository.selector.registerSource(anyObject))
            args.putString(ARG_SELECTED_ITEM_ID, selectedItemId)
            fragment.arguments = args
            return fragment
        }
    }

    private var _binding: FragmentSelectorBinding? = null
    private val binding get() = _binding!!
    private var selectorObject: SelectorFragmentObject? = null
    private var currentContainerId: String = "0"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sourceId = arguments?.getString(ARG_SOURCE_ID) ?: ""
        val source = UpnpRepository.selector.getSource(sourceId)
        selectorObject = source?.castToSelectorFragmentObject()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.selectorTitle.text = arguments?.getString(ARG_TITLE)
        binding.buttonCancel.setOnClickListener { dismiss() }
        binding.recyclerItems.layoutManager = LinearLayoutManager(context)

        val selectedItemId = arguments?.getString(ARG_SELECTED_ITEM_ID)
        val provider = selectorObject
        if (selectedItemId != null && provider != null) {
            val selectedItem = provider.getItem(selectedItemId)
            if (selectedItem != null) {
                if (selectedItem.isContainer) {
                    currentContainerId = selectedItemId
                } else {
                    currentContainerId = selectedItem.parentId ?: "0"
                }
            } else {
                currentContainerId = "0"
            }
        } else {
            currentContainerId = "0"
        }

        setupList(currentContainerId)
    }

    private fun setupList(containerId: String) {
        currentContainerId = containerId
        val provider = selectorObject ?: return
        val items = mutableListOf<SelectorItem>()

        val root = provider.getItem("0")
        if (root != null && root.isContainer) {
            val container = provider.getItem(containerId) ?: return
            if (containerId != "0") {
                binding.selectorTitle.text = container.displayLabel
                items.add(object : SelectorItem {
                    override val selectionId: String = container.parentId ?: "0"
                    override val displayLabel: String = ".."
                    override val secondaryLabel: String = "Parent directory"
                    override val iconResId: Int = R.drawable.ic_folder
                    override val isContainer: Boolean = true
                })
            }
            
            container.children.forEach { childId ->
                provider.getItem(childId)?.let { items.add(it) }
            }
        } else {
            items.addAll(provider.getAllItems())
        }

        binding.recyclerItems.adapter = SelectionAdapter(items) { item ->
            if (item.isContainer) {
                setupList(item.selectionId)
            } else {
                provider.onItemSelected(item.selectionId)
                dismiss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            val width = (resources.displayMetrics.widthPixels * 0.8).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.8).toInt()
            setLayout(width, height)
            setBackgroundDrawableResource(android.R.color.transparent)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class SelectionAdapter(
        private val items: List<SelectorItem>,
        private val onItemClick: (SelectorItem) -> Unit
    ) : RecyclerView.Adapter<SelectionAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.item_icon)
            val title: TextView = view.findViewById(R.id.item_title)
            val subtitle: TextView = view.findViewById(R.id.item_subtitle)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_selector, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = items[position]
            holder.title.text = item.displayLabel
            holder.subtitle.text = item.secondaryLabel
            holder.icon.setImageResource(item.iconResId)
            holder.itemView.setOnClickListener { onItemClick(item) }
        }

        override fun getItemCount() = items.size
    }
}
