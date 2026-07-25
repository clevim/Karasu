package eu.kanade.tachiyomi.ui.manga.merge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import java.util.Collections
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlin.math.roundToInt
import android.R as AR

/**
 * Lists the sources merged into a manga: grab the handle to reorder, tap the bin to remove.
 *
 * Order is the priority used to pick which source's row wins for a chapter both of them
 * have, so it is applied as soon as an item is dropped rather than on dismiss. Removal is its
 * own explicit button so a tap meant to grab a row can't delete a source by accident.
 */
fun Activity.showMergedSourcesDialog(
    sources: List<Pair<Long, String>>,
    onRemove: (Long) -> Unit,
    onReorder: (List<Long>) -> Unit,
    onAdd: () -> Unit,
) {
    val dialog = materialAlertDialog()
        .setTitle(MR.strings.merged_sources.getString(this))
        .setPositiveButton(MR.strings.add.getString(this)) { _, _ -> onAdd() }
        .setNegativeButton(AR.string.cancel, null)

    if (sources.isEmpty()) {
        dialog.setMessage(MR.strings.no_merged_sources.getString(this)).show()
        return
    }

    val adapter = MergedSourcesAdapter(sources.toMutableList(), onRemove, onReorder)
    val recycler = RecyclerView(this).apply {
        layoutManager = LinearLayoutManager(this@showMergedSourcesDialog)
        this.adapter = adapter
    }
    adapter.touchHelper = ItemTouchHelper(adapter.dragCallback).also { it.attachToRecyclerView(recycler) }

    dialog
        .setMessage(MR.strings.merged_sources_hint.getString(this))
        .setView(recycler)
        .show()
}

private class MergedSourcesAdapter(
    private val sources: MutableList<Pair<Long, String>>,
    private val onRemove: (Long) -> Unit,
    private val onReorder: (List<Long>) -> Unit,
) : RecyclerView.Adapter<MergedSourcesAdapter.Holder>() {

    lateinit var touchHelper: ItemTouchHelper

    class Holder(
        root: LinearLayout,
        val name: TextView,
        val delete: ImageView,
        val handle: ImageView,
    ) : RecyclerView.ViewHolder(root)

    override fun getItemCount() = sources.size

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).roundToInt()
        val iconTint = ColorStateList.valueOf(context.getResourceColor(AR.attr.textColorSecondary))

        val delete = ImageView(context).apply {
            setImageResource(R.drawable.ic_delete_24dp)
            imageTintList = iconTint
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = MR.strings.remove.getString(context)
        }
        val name = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setPadding(dp(4), 0, dp(4), 0)
            textSize = 16f
            setTextColor(context.getResourceColor(AR.attr.textColorPrimary))
        }
        val handle = ImageView(context).apply {
            setImageResource(R.drawable.ic_drag_handle_24dp)
            imageTintList = iconTint
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT,
                RecyclerView.LayoutParams.WRAP_CONTENT,
            )
            addView(delete)
            addView(name)
            addView(handle)
        }

        val holder = Holder(root, name, delete, handle)
        delete.setOnClickListener {
            val index = holder.bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return@setOnClickListener
            onRemove(sources.removeAt(index).first)
            notifyItemRemoved(index)
        }
        // Drag starts the moment the handle is touched, so reordering never waits on a long press.
        handle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) touchHelper.startDrag(holder)
            false
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.name.text = sources[position].second
    }

    val dragCallback = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0,
    ) {
        // The handle is the only way to start a drag; a long press on the row does nothing.
        override fun isLongPressDragEnabled() = false

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            Collections.swap(sources, from, to)
            notifyItemMoved(from, to)
            return true
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            super.clearView(recyclerView, viewHolder)
            // Persist once the drag ends, not on every position swap it passes through.
            onReorder(sources.map { it.first })
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    }
}
