package eu.kanade.tachiyomi.ui.extension

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractHeaderItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R

/**
 * Item that contains the group header.
 *
 * @param name The header name.
 * @param size The number of items in the group.
 * @param collapsed Whether the group is rolled up, so the list shows this header and nothing else
 *   under it. Not part of [equals]: a group is the same group whether open or closed, which is
 *   what lets the adapter keep track of it across a refresh.
 */
data class ExtensionGroupItem(
    val name: String,
    /**
     * What the group *is*, as opposed to what it is called.
     *
     * The updates header is titled "3 updates pending", so keying anything on the title means the
     * group stops being itself the moment one extension updates — a rolled up group would spring
     * back open, and the old title would sit in the preference forever. Language groups have the
     * same problem the day a locale's display name changes.
     */
    val key: String = name,
    val size: Int,
    var canUpdate: Boolean? = null,
    var installedSorting: Int? = null,
    val collapsed: Boolean = false,
) : AbstractHeaderItem<ExtensionGroupHolder>() {

    /**
     * Returns the layout resource of this item.
     */
    override fun getLayoutRes(): Int {
        return R.layout.extension_card_header
    }

    /**
     * Creates a new view holder for this item.
     */
    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): ExtensionGroupHolder {
        return ExtensionGroupHolder(view, adapter)
    }

    /**
     * Binds this item to the given view holder.
     */
    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: ExtensionGroupHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(this)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is ExtensionGroupItem) {
            return key == other.key
        }
        return false
    }

    override fun hashCode(): Int {
        return key.hashCode()
    }
}
