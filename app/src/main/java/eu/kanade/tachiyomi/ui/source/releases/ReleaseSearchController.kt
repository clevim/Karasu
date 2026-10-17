package eu.kanade.tachiyomi.ui.source.releases

import android.os.Bundle
import androidx.core.os.bundleOf
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchItem
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchPresenter
import eu.kanade.tachiyomi.util.view.snack
import karasu.i18n.MR

/**
 * The app's global search for a series from the "Releases" window: its first name, then each of
 * the others on a source that found nothing. Says so plainly when no source has it.
 */
class ReleaseSearchController(bundle: Bundle) : GlobalSearchController(bundle.getString(QUERY), bundle = bundle) {

    constructor(query: String, aliases: List<String>) : this(bundleOf(QUERY to query, ALIASES to aliases.toTypedArray()))

    private var told = false

    // Runs from the base constructor: only the bundle exists yet.
    override fun createPresenter() = GlobalSearchPresenter(
        initialQuery,
        extensionFilter,
        fallbackQueries = args.getStringArray(ALIASES)?.toList().orEmpty(),
    )

    override fun setItems(searchResult: List<GlobalSearchItem>) {
        super.setItems(searchResult)
        val done = searchResult.isNotEmpty() && searchResult.all { it.results != null }
        if (done && !told && searchResult.all { it.results.isNullOrEmpty() }) {
            told = true
            view?.snack(MR.strings.releases_not_in_sources)
        }
    }

    private companion object {
        const val QUERY = "query"
        const val ALIASES = "aliases"
    }
}
