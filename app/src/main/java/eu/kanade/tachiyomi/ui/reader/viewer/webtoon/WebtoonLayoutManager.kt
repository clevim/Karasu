@file:Suppress("PackageDirectoryMismatch")

package androidx.recyclerview.widget

import androidx.recyclerview.widget.RecyclerView.NO_POSITION
import eu.kanade.tachiyomi.ui.reader.ReaderActivity

/**
 * Layout manager used by the webtoon viewer. Item prefetch is disabled because the extra layout
 * space feature is used which allows setting the image even if the holder is not visible,
 * avoiding (in most cases) black views when they are visible.
 *
 * This layout manager uses the same package name as the support library in order to use a package
 * protected method.
 */
class WebtoonLayoutManager(activity: ReaderActivity, private val extraLayoutSpace: Int) : LinearLayoutManager(activity) {

    init {
        isItemPrefetchEnabled = false
    }

    /**
     * Returns the custom extra layout space.
     */
    @Deprecated("Deprecated in Java")
    override fun getExtraLayoutSpace(state: RecyclerView.State): Int {
        return extraLayoutSpace
    }

    /**
     * Returns the position of the last item whose end side is visible on screen, or of the item
     * spanning the whole screen when one image is taller than it.
     *
     * Without the second case a page taller than the screen was never "current": its end was
     * off screen, so the page before it was reported instead, and that is what got saved as the
     * reading position. Ported from Mihon (mihonapp/mihon#562).
     */
    fun findLastEndVisibleItemPosition(): Int {
        ensureLayoutState()
        val callback = if (mOrientation == HORIZONTAL) mHorizontalBoundCheck else mVerticalBoundCheck
        val start = callback.mCallback.parentStart
        val end = callback.mCallback.parentEnd
        for (i in childCount - 1 downTo 0) {
            val child = getChildAt(i)!!
            val childStart = callback.mCallback.getChildStart(child)
            val childEnd = callback.mCallback.getChildEnd(child)
            if (childEnd <= end || childStart < start) {
                return getPosition(child)
            }
        }
        return NO_POSITION
    }
}
