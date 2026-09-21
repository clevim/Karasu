package karasu.domain.recommendation

import karasu.data.DatabaseHandler
import karasu.domain.manga.interactor.GetManga

/**
 * Throws the last build away before a new one: the list, and the manga rows the window created
 * for it. Browsing inserts a row per card shown, so a build leaves a few hundred rows behind;
 * kept, they are what makes a stale card reappear looking current. Rows that were added to the
 * library, or read, are not the window's to remove and stay.
 */
class DiscardRecommendations(
    private val store: RecommendationStore,
    private val getManga: GetManga,
    private val handler: DatabaseHandler,
) {
    suspend fun await() {
        val old = store.read()
        store.clear()
        if (old == null) return
        val ids = old.items
            .filterNot { it.fromLibrary }
            .mapNotNull { runCatching { getManga.awaitByUrlAndSource(it.url, it.sourceId) }.getOrNull() }
            .filterNot { it.favorite }
            .mapNotNull { it.id }
        if (ids.isEmpty()) return
        runCatching {
            handler.await { mangasQueries.deleteNotInLibraryAndNotReadByIds(ids) }
        }
    }
}
