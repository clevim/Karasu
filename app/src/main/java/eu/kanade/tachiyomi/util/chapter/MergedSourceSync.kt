package eu.kanade.tachiyomi.util.chapter

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.failures.isEntryGone
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.InsertManga
import karasu.domain.manga.merged.interactor.MergedSourceHealth
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.manga.models.MergedMangaSource
import kotlinx.coroutines.CancellationException

/**
 * Refreshes the chapter lists of a manga's merged sources.
 *
 * Each merged source keeps its own manga row, and those rows are not favourites, so the
 * library update — which walks favourites only — never reaches them. Without this the
 * merged sources would be frozen at whatever they had when they were added, which defeats
 * the point of merging: the newest chapters usually come from the source that has more.
 */
class MergedSourceSync(
    private val sourceManager: SourceManager,
    private val mergedSources: MergedSources,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val health: MergedSourceHealth,
    private val insertManga: InsertManga,
) {
    /**
     * @return the chapters the merged sources gained that the merged list actually shows, so
     * callers can notify and download them like the manga's own new chapters.
     */
    suspend fun await(mangaId: Long): List<Chapter> {
        if (!mergedSources.hasMerges(mangaId)) return emptyList()

        val added = mutableListOf<Chapter>()
        mergedSources.await(mangaId).filter { it.updatesEnabled }.forEach { merge ->
            val source = sourceManager.get(merge.source) ?: return@forEach
            val child = childRow(mangaId, merge) ?: return@forEach
            try {
                // Syncs against the child's own row, so it only ever deletes its own
                // chapters — the primary's list is untouched.
                added += syncChaptersWithSource(source.getChapterList(child), child, source).first
                health.recordAlive(mangaId, merge.source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // One dead source must not fail the manga's whole update.
                Logger.w(e) { "Failed to refresh merged source ${merge.source} for manga $mangaId" }
                // This walk is the only thing that regularly asks these sources anything, so it is
                // also the only place that can tell a merge pointing at a pulled entry from one
                // whose source is merely having a bad day.
                if (isEntryGone(e)) health.recordGone(mangaId, merge.source)
            }
        }
        if (added.isEmpty()) return emptyList()

        // A merged source gaining a chapter the manga already has changes nothing the user
        // can see, so only the rows that survive the merge count as new.
        val shown = getChapter.awaitAll(mangaId, false).mapNotNull { it.id }.toSet()
        return added.filter { it.id in shown }
    }

    /**
     * The manga row this merge's chapters live on, created when it has gone missing.
     *
     * That row is not a favourite, so "clear database" and anything else that prunes entries
     * outside the library can take it — and nothing used to put it back. The merge stayed in the
     * table, silently contributed no chapters, and only removing and re-adding it helped. A bare
     * row is enough to sync onto, which is what the backup restore already relies on.
     */
    private suspend fun childRow(mangaId: Long, merge: MergedMangaSource): Manga? {
        getManga.awaitByUrlAndSource(merge.url, merge.source)?.let { return it }
        val title = getManga.awaitById(mangaId)?.title ?: return null
        insertManga.await(Manga.create(merge.url, title, merge.source))
        return getManga.awaitByUrlAndSource(merge.url, merge.source)
    }
}
