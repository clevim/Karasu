package eu.kanade.tachiyomi.ui.source.browse

import android.view.View
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import coil3.dispose
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.databinding.MangaListItemBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.view.setCards
import karasu.domain.manga.models.cover
import karasu.util.coil.loadManga

/**
 * Class used to hold the displayed data of a manga in the catalogue, like the cover or the title.
 * All the elements from the layout file "item_catalogue_list" are available in this class.
 *
 * @param view the inflated view for this holder.
 * @param adapter the adapter handling this holder.
 * @constructor creates a new catalogue holder.
 */
class BrowseSourceListHolder(
    private val view: View,
    adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    showOutline: Boolean,
) :
    BrowseSourceHolder(view, adapter) {

    private val binding = MangaListItemBinding.bind(view)

    init {
        setCards(showOutline, binding.card, binding.unreadDownloadBadge.badgeView)
    }

    /**
     * Method called from [CatalogueAdapter.onBindViewHolder]. It updates the data for this
     * holder with the given manga.
     *
     * @param manga the manga to bind.
     */
    private var isFavorite = false
    private var hasNoChapters = false

    /** Cover dimming has two independent reasons, and either can arrive after the other. */
    private fun applyCoverAlpha() {
        binding.coverThumbnail.alpha = when {
            isFavorite -> 0.34f
            hasNoChapters -> 0.4f
            else -> 1.0f
        }
    }

    override fun onSetValues(manga: Manga) {
        binding.title.text = manga.title
        binding.inLibraryBadge.badge.isVisible = manga.favorite

        setImage(manga)
    }

    override fun setImage(manga: Manga) {
        // Update the cover.
        isFavorite = manga.favorite
        if (manga.thumbnail_url == null) {
            binding.coverThumbnail.dispose()
        } else {
            manga.id ?: return
            binding.coverThumbnail.loadManga(manga.cover())
            applyCoverAlpha()
        }
    }

    override fun setChapterCount(count: Int?) {
        hasNoChapters = count == 0
        binding.unreadDownloadBadge.badgeView.setChapters(count)
        applyCoverAlpha()
    }
}
