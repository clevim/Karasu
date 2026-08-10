package eu.kanade.tachiyomi.ui.manga.merge

import android.os.Bundle
import androidx.core.os.bundleOf
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchCardAdapter
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController

/**
 * Global search that merges the picked result into an existing manga instead of opening it.
 *
 * Global search already inserts a manga row for every result it renders, so by the time a
 * result is clicked the row this merge points at exists. The work itself is handed to the
 * details presenter because this controller's scope dies with the pop below.
 */
class MergeSearchController(
    initialQuery: String? = null,
) : GlobalSearchController(
    initialQuery,
    bundle = bundleOf(QUERY to initialQuery),
) {

    @Suppress("unused")
    constructor(bundle: Bundle) : this(bundle.getString(QUERY))

    constructor(manga: Manga) : this(manga.originalTitle)

    override fun onMangaClick(manga: Manga) {
        val target = targetController as? MangaDetailsController ?: return
        target.presenter.addMergedSource(manga.source, manga.url)
        router.popCurrentController()
    }

    override fun onMangaLongClick(position: Int, adapter: GlobalSearchCardAdapter) {
        // Long press keeps the normal "open this manga" behaviour.
        val manga = adapter.getItem(position)?.manga ?: return
        super.onMangaClick(manga)
    }

    companion object {
        const val QUERY = "query"
    }
}
