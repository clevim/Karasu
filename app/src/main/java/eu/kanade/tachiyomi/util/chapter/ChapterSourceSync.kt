package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.source.model.memoToString
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.online.HttpSource
import java.util.*
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import karasu.data.DatabaseHandler
import karasu.domain.chapter.interactor.DeleteChapter
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.InsertChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.chapter.models.ChapterUpdate
import karasu.domain.chapter.services.ChapterRecognition
import karasu.domain.library.LibraryPreferences
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.manga.models.MangaUpdate

/**
 * Helper method for syncing the list of chapters from the source with the ones from the database.
 *
 * @param db the database.
 * @param rawSourceChapters a list of chapters from the source.
 * @param manga the manga of the chapters.
 * @param source the source of the chapters.
 * @return a pair of new insertions and deletions.
 */
suspend fun syncChaptersWithSource(
    rawSourceChapters: List<SChapter>,
    manga: Manga,
    source: Source,
    deleteChapter: DeleteChapter = Injekt.get(),
    getChapter: GetChapter = Injekt.get(),
    insertChapter: InsertChapter = Injekt.get(),
    updateChapter: UpdateChapter = Injekt.get(),
    updateManga: UpdateManga = Injekt.get(),
    handler: DatabaseHandler = Injekt.get(),
    libraryPreferences: LibraryPreferences = Injekt.get(),
): Pair<List<Chapter>, List<Chapter>> {
    if (rawSourceChapters.isEmpty()) {
        throw Exception("No chapters found")
    }

    val downloadManager: DownloadManager by injectLazy()
    // Chapters from db. Deliberately unmerged: everything missing from [rawSourceChapters]
    // is deleted below, so a merged list would delete the other sources' chapters here.
    val dbChapters = getChapter.awaitAllRaw(manga.id!!, false)

    val sourceChapters = rawSourceChapters
        .distinctBy { it.url }
        .mapIndexed { i, sChapter ->
            Chapter.create().apply {
                copyFrom(sChapter)
                name = with(ChapterSanitizer) { sChapter.name.sanitize(manga.title) }
                manga_id = manga.id
                source_order = i
            }
        }

    // Chapters from the source not in db.
    val toAdd = mutableListOf<Chapter>()

    // Chapters whose metadata have changed.
    val toChange = mutableListOf<ChapterUpdate>()

    // Chapters where only the source's own memo moved. Kept apart from [toChange] because a memo
    // is not news: a source that rotates the id it keeps there hands back a new one on every
    // refresh, and counting that as a changed chapter list would rewrite every row's source
    // order and float the manga to the top of Updates each time it was refreshed.
    val memoOnly = mutableListOf<ChapterUpdate>()

    // Grouped once and reused: the duplicate rows below and the per-chapter lookup in the loop
    // are both "the stored rows for this url", and scanning the list for each of a few thousand
    // chapters is what made a sync of a long series quadratic.
    val dbChaptersByUrl = dbChapters.groupBy { it.url }
    val duplicates = dbChaptersByUrl.values
        .filter { it.size > 1 }
        .flatMap { chapters -> chapters.drop(1) }
    val sourceUrls = sourceChapters.mapTo(HashSet()) { it.url }
    val notInSource = dbChapters.filterNot { it.url in sourceUrls }
    val toDelete = duplicates + notInSource

    val managedUrls = mutableSetOf<String>()

    // The chapters whose stored order no longer matches the source's, collected while they are
    // found rather than looked up again afterwards.
    val reorderedUrls = mutableSetOf<String>()

    for (sourceChapter in sourceChapters) {
        val chapter = sourceChapter

        if (chapter.url in managedUrls) continue

        if (source is HttpSource) {
            source.prepareNewChapter(chapter, manga)
        }
        chapter.chapter_number = ChapterRecognition.parseChapterNumber(chapter.name, manga.title, chapter.chapter_number)

        val dbChapter = dbChaptersByUrl[chapter.url]?.first()

        // Add the chapter if not in db already, or update if the metadata changed.
        if (dbChapter == null) {
            toAdd.add(chapter)
            reorderedUrls.add(chapter.url)
        } else {
            if (!shouldUpdateDbChapter(dbChapter, chapter) && dbChapter.memo != chapter.memo) {
                memoOnly.add(ChapterUpdate(dbChapter.id!!, memo = chapter.memo.memoToString()))
            }
            if (shouldUpdateDbChapter(dbChapter, chapter)) {
                if ((dbChapter.name != chapter.name || dbChapter.scanlator != chapter.scanlator) &&
                    downloadManager.isChapterDownloaded(dbChapter, manga)
                ) {
                    downloadManager.renameChapter(source, manga, dbChapter, chapter)
                }
                val update = ChapterUpdate(
                    dbChapter.id!!,
                    scanlator = chapter.scanlator,
                    name = chapter.name,
                    dateUpload = chapter.date_upload,
                    chapterNumber = chapter.chapter_number.toDouble(),
                    sourceOrder = chapter.source_order.toLong(),
                    memo = chapter.memo.memoToString(),
                )
                toChange.add(update)
                reorderedUrls.add(chapter.url)
            }
        }

        managedUrls.add(chapter.url)
    }

    // Return if there's nothing to add, delete or change, avoid unnecessary db transactions.
    if (toAdd.isEmpty() && toDelete.isEmpty() && toChange.isEmpty()) {
        // A rotated memo still has to reach the database, or the source can never fetch the
        // chapter again — but on its own it is not a chapter list that changed, so it is written
        // without touching `last_update` or any row's source order.
        if (memoOnly.isNotEmpty()) updateChapter.awaitAll(memoOnly)
        // TODO: Predict when the next chapter gonna release
        return Pair(emptyList(), emptyList())
    }

    val changedOrDuplicateReadUrls = mutableSetOf<String>()

    val deletedChapterNumbers = TreeSet<Float>()
    val deletedReadChapterNumbers = TreeSet<Float>()
    val deletedBookmarkedChapterNumbers = TreeSet<Float>()

    val readChapterNumbers = dbChapters
        .asSequence()
        .filter { it.read && it.isRecognizedNumber }
        .map { it.chapter_number }
        .toSet()

    toDelete.forEach {
        if (it.read) deletedReadChapterNumbers.add(it.chapter_number)
        if (it.bookmark) deletedBookmarkedChapterNumbers.add(it.chapter_number)
        deletedChapterNumbers.add(it.chapter_number)
    }

    val now = Date().time

    // When a chapter comes back under a new url, it keeps the fetch date it originally had so it
    // does not resurface in Updates. Grouped up front: the alternative is rescanning every
    // deleted row for each added one.
    val oldestDeletedFetch = toDelete
        .groupBy { it.chapter_number }
        .mapValues { (_, chapters) -> chapters.minOf { it.date_fetch } }

    val markDuplicateAsRead = libraryPreferences.markDuplicateReadChapterAsRead().get()
        .contains(LibraryPreferences.MARK_DUPLICATE_READ_CHAPTER_READ_NEW)

    // Date fetch is set in such a way that the upper ones will have bigger value than the lower ones
    // Sources MUST return the chapters from most to less recent, which is common.
    var itemCount = toAdd.size
    var updatedToAdd = toAdd.map { toAddItem ->
        val chapter: Chapter = toAddItem.copy()

        chapter.date_fetch = now + itemCount--

        if (chapter.chapter_number in readChapterNumbers && markDuplicateAsRead) {
            changedOrDuplicateReadUrls.add(chapter.url)
            chapter.read = true
        }

        if (!chapter.isRecognizedNumber || chapter.chapter_number !in deletedChapterNumbers) return@map chapter

        chapter.read = chapter.chapter_number in deletedReadChapterNumbers
        chapter.bookmark = chapter.chapter_number in deletedBookmarkedChapterNumbers

        // Try to use the fetch date it originally had to not pollute 'Updates' tab
        oldestDeletedFetch[chapter.chapter_number]?.let { chapter.date_fetch = it }

        changedOrDuplicateReadUrls.add(chapter.url)

        chapter
    }

    handler.await(inTransaction = true) {
        if (toDelete.isNotEmpty()) {
            val idsToDelete = toDelete.mapNotNull { it.id }
            deleteChapter.awaitAllById(idsToDelete)
        }

        if (updatedToAdd.isNotEmpty()) {
            updatedToAdd = insertChapter.awaitBulk(updatedToAdd)
        }

        if (toChange.isNotEmpty() || memoOnly.isNotEmpty()) {
            updateChapter.awaitAll(toChange + memoOnly)
        }

        // Fix order in source. Only the chapters that moved: the rest already hold the order
        // this would write, and a manga with two thousand chapters is two thousand no-op
        // UPDATEs inside the transaction on every sync that changed anything at all.
        sourceChapters.forEach { chapter ->
            if (chapter.manga_id == null || chapter.url !in reorderedUrls) return@forEach
            chaptersQueries.fixSourceOrder(
                url = chapter.url,
                mangaId = chapter.manga_id!!,
                sourceOrder = chapter.source_order.toLong(),
            )
        }

        // TODO: Predict when the next chapter gonna release

        // Set this manga as updated since chapters were changed
        // Note that last_update actually represents last time the chapter list changed at all
        // Those changes already checked beforehand, so we can proceed to updating the manga
        manga.last_update = Date().time
        updateManga.await(MangaUpdate(manga.id!!, lastUpdate = manga.last_update))
    }

    val filteredScanlators = ChapterUtil.getScanlators(manga.filtered_scanlators).toHashSet()

    return Pair(
        updatedToAdd.filterNot {
            it.url in changedOrDuplicateReadUrls || it.scanlator in filteredScanlators
        },
        toDelete.filterNot { it.url in changedOrDuplicateReadUrls },
    )
}

// checks if the chapter in db needs updated
internal fun shouldUpdateDbChapter(dbChapter: Chapter, sourceChapter: Chapter): Boolean {
    return dbChapter.scanlator != sourceChapter.scanlator ||
        dbChapter.name != sourceChapter.name ||
        dbChapter.date_upload != sourceChapter.date_upload ||
        dbChapter.chapter_number != sourceChapter.chapter_number ||
        dbChapter.source_order != sourceChapter.source_order
}
