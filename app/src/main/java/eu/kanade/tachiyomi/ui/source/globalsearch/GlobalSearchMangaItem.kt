package eu.kanade.tachiyomi.ui.source.globalsearch

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.domain.manga.models.Manga
import karasu.domain.manga.models.cover
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// FIXME: Migrate to compose
class GlobalSearchMangaItem(
    initialManga: Manga,
    private val mangaFlow: Flow<Manga?>,
) : AbstractFlexibleItem<GlobalSearchMangaHolder>() {

    val mangaId: Long? = initialManga.id
    var manga: Manga = initialManga
        private set
    private val scope = MainScope()
    private var job: Job? = null

    override fun getLayoutRes(): Int {
        return R.layout.source_global_search_controller_card_item
    }

    override fun createViewHolder(view: View, adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>): GlobalSearchMangaHolder {
        return GlobalSearchMangaHolder(view, adapter as GlobalSearchCardAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: GlobalSearchMangaHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        // Always draw what we already have. The flow's first emission is a database round trip
        // away, and waiting for it left recycled cards showing the previous result.
        holder.bind(manga)
        job?.cancel()
        job = scope.launch {
            mangaFlow.collectLatest { updated ->
                if (updated == null || updated.rendered() == manga.rendered()) return@collectLatest
                manga = updated
                holder.bind(manga)
            }
        }
    }

    /**
     * Everything the card draws, and nothing else.
     *
     * [mangaFlow] re-runs its query on every write to the `mangas` table — a library update fires
     * one per entry — so without this every visible result rebound, and rebinding disposes the
     * cover and loads it again. That is the flashing: a list of results blinking in step with a
     * background job that changed none of them.
     */
    private fun Manga.rendered() = title to cover()

    override fun unbindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>?,
        holder: GlobalSearchMangaHolder?,
        position: Int
    ) {
        job?.cancel()
        job = null
    }

    override fun equals(other: Any?): Boolean {
        if (other is GlobalSearchMangaItem) {
            return mangaId == other.mangaId
        }
        return false
    }

    override fun hashCode(): Int {
        return mangaId?.toInt() ?: 0
    }
}
