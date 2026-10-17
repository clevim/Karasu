package karasu.domain.download

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import karasu.domain.chapter.interactor.GetChapter

/**
 * Keeps the next [DownloadPreferences.newChaptersAhead] unread chapters of an entry downloaded,
 * in place of "download new chapters" queuing every chapter a source releases.
 *
 * Downloading every new chapter stockpiles a series the reader has stopped following; this keeps
 * a small buffer ahead of where they are instead. Called again when a chapter is finished, which
 * is what moves the buffer forward one chapter at a time.
 */
class DownloadNextUnread(
    private val getChapter: GetChapter,
    private val downloadManager: DownloadManager,
    private val downloadPreferences: DownloadPreferences,
) {
    /** Whether new chapters are limited at all; 0 is the old "every new chapter". */
    fun isLimited() = downloadPreferences.newChaptersAhead().get() > 0

    /**
     * @param justRead a chapter finished this moment, whose read flag is not in the database yet.
     * @param toFrontOfQueue for the reader, which needs these sooner than anything else queued.
     * @return whether any of the next chapters still has to be downloaded.
     */
    suspend fun await(manga: Manga, justRead: Long? = null, autoStart: Boolean = true, toFrontOfQueue: Boolean = false): Boolean {
        val ahead = downloadPreferences.newChaptersAhead().get()
        if (ahead <= 0) return false
        val chapters = getChapter.awaitAll(manga).sortedWith(ChapterSort(manga).sortComparator(true))
        // Only what is on disk or being downloaded right now is in hand. One merely queued — at
        // the back of a long queue, or failed — is handed over again: with [toFrontOfQueue] the
        // downloader moves it forward and retries it, and without it it is left where it is.
        val missing = nextUnreadToDownload(chapters, ahead, justRead) {
            downloadManager.isChapterDownloaded(it, manga) ||
                downloadManager.getChapterDownloadOrNull(it)?.status == Download.State.DOWNLOADING
        }
        if (missing.isNotEmpty()) downloadManager.downloadChapters(manga, missing, autoStart, toFrontOfQueue)
        return missing.isNotEmpty()
    }
}

/**
 * The first [ahead] chapters after the last one read that are not in hand yet.
 *
 * After the last read rather than the first unread: a chapter skipped long ago is not the next
 * one, and downloading it would spend the buffer on something the reader is not going to open.
 *
 * @param chapters every chapter of the entry in reading order, first to read first.
 * @param have whether a chapter is already downloaded or queued.
 */
internal fun nextUnreadToDownload(chapters: List<Chapter>, ahead: Int, justRead: Long? = null, have: (Chapter) -> Boolean): List<Chapter> {
    val start = chapters.indexOfLast { it.read || it.id == justRead } + 1
    return chapters.drop(start).take(ahead).filterNot(have)
}

/**
 * The read chapters to delete once [currentId] is finished, keeping the [keep] most recently read
 * in reading order: [currentId] itself and the read ones just before it.
 *
 * Every older read chapter goes, not only the one that just fell out of the window. Deleting that
 * single chapter left behind whatever was read in an earlier session, or while "skip read" kept
 * read chapters out of the reader's list, and none of those ever came round again.
 *
 * Chapters after [currentId] are left alone: re-reading an old chapter is no reason to delete the
 * ones the reader got to after it.
 *
 * @param chapters every chapter of the entry in reading order, first to read first.
 */
internal fun readChaptersToRemove(chapters: List<Chapter>, currentId: Long, keep: Int): List<Chapter> {
    val current = chapters.indexOfFirst { it.id == currentId }
    if (current < 0 || keep < 0) return emptyList()
    return chapters.take(current + 1).filter { it.read || it.id == currentId }.dropLast(keep)
}
