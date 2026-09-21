package eu.kanade.tachiyomi.ui.manga.merge

import android.os.Bundle
import androidx.core.os.bundleOf
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchCardAdapter
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchPresenter

/**
 * Global search that merges the picked result into an existing manga instead of opening it.
 *
 * Global search already inserts a manga row for every result it renders, so by the time a
 * result is clicked the row this merge points at exists. The work itself is handed to the
 * details presenter because this controller's scope dies with the pop below.
 */
class MergeSearchController(
    initialQuery: String? = null,
    excludedSources: Set<Long> = emptySet(),
    aliases: List<String> = emptyList(),
) : GlobalSearchController(
    initialQuery,
    bundle = bundleOf(
        QUERY to initialQuery,
        EXCLUDED to excludedSources.toLongArray(),
        ALIASES to aliases.toTypedArray(),
    ),
) {

    @Suppress("unused")
    constructor(bundle: Bundle) : this(
        bundle.getString(QUERY),
        bundle.getLongArray(EXCLUDED)?.toSet().orEmpty(),
        bundle.getStringArray(ALIASES)?.toList().orEmpty(),
    )

    /**
     * @param excludedSources the series' own source and the ones already merged in: offering
     *   them again would only offer the same chapters twice.
     * @param aliases the other names the series goes by, tried on a source that found nothing
     *   under the library title.
     */
    constructor(manga: Manga, excludedSources: Set<Long>, aliases: List<String>) :
        this(manga.originalTitle, excludedSources, aliases)

    // Runs from the base constructor, before this class's own fields exist: everything it needs
    // has to come from the bundle, which is why the constructor puts it there.
    override fun createPresenter() = GlobalSearchPresenter(
        initialQuery,
        extensionFilter,
        excludedSources = args.getLongArray(EXCLUDED)?.toSet().orEmpty(),
        fallbackQueries = args.getStringArray(ALIASES)?.toList().orEmpty(),
    )

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
        private const val EXCLUDED = "excluded"
        private const val ALIASES = "aliases"
    }
}
