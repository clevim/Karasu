package karasu.domain.download

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.history.interactor.GetHistory
import karasu.domain.manga.interactor.GetLibraryManga

/**
 * Deletes downloaded chapters read more than [PreferencesHelper.removeReadOlderThanDays] ago.
 *
 * "Remove after read" deletes the moment a chapter is finished, which is wrong for anyone who
 * rereads or reads offline on a trip; never removing fills the phone. Age is the middle: what was
 * read a month ago is not coming back soon. The same exclusions as "remove after read" apply —
 * excluded categories, and bookmarked chapters unless deleting those is allowed — because the
 * deletion goes through [DownloadManager.deleteChapters], which already enforces them.
 *
 * A chapter marked read by hand has no history and so no date: it is left alone, since there is
 * no telling whether it was read yesterday.
 */
class CleanOldDownloads(
    private val getLibraryManga: GetLibraryManga,
    private val getChapter: GetChapter,
    private val getHistory: GetHistory,
    private val downloadManager: DownloadManager,
    private val sourceManager: SourceManager,
    private val preferences: PreferencesHelper,
) {
    /** @return how many chapters were handed over for deletion. */
    suspend fun await(now: Long = System.currentTimeMillis()): Int {
        val days = preferences.removeReadOlderThanDays().get()
        if (days <= 0) return 0
        val cutoff = now - days * DAY_MS
        val excluded = preferences.removeExcludeCategories().get().mapNotNull { it.toIntOrNull() }.toSet()
        var removed = 0
        getLibraryManga.await()
            .filter { it.category !in excluded }
            .distinctBy { it.manga.id }
            .forEach { entry ->
                val manga = entry.manga
                val id = manga.id ?: return@forEach
                if (downloadManager.getDownloadCount(manga) == 0) return@forEach
                val source = sourceManager.get(manga.source) ?: return@forEach
                val readAt = getHistory.awaitAllByMangaId(id).associate { it.chapter_id to it.last_read }
                val old = readLongAgo(getChapter.awaitAll(id, false), readAt, cutoff)
                    .filter { downloadManager.isChapterDownloaded(it, manga) }
                if (old.isEmpty()) return@forEach
                downloadManager.deleteChapters(old, manga, source)
                removed += old.size
            }
        if (removed > 0) Logger.i { "Removed $removed chapters read more than $days days ago" }
        return removed
    }

    companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

/** The read chapters whose last read is known and before [cutoff]. */
internal fun readLongAgo(chapters: List<Chapter>, readAt: Map<Long, Long>, cutoff: Long): List<Chapter> =
    chapters.filter { chapter ->
        val at = chapter.id?.let(readAt::get) ?: return@filter false
        chapter.read && at in 1 until cutoff
    }
