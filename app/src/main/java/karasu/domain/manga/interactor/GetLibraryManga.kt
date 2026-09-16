package karasu.domain.manga.interactor

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.MangaRepository
import karasu.domain.manga.merged.interactor.MergedSources
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class GetLibraryManga(
    private val mangaRepository: MangaRepository,
    private val mergedSources: MergedSources,
    private val getChapter: GetChapter,
) {
    suspend fun await(): List<LibraryManga> = mangaRepository.getLibraryManga().withMergedCounts()

    fun subscribe(): Flow<List<LibraryManga>> =
        mangaRepository.getLibraryMangaAsFlow().map { it.withMergedCounts() }

    /**
     * Recounts the entries whose chapters partly live on a merged source's row.
     *
     * `library_view` counts `chapters.manga_id = mangas._id` only, and a merged source keeps its
     * chapters on its own non-favourite row, so everything built on these counts — the unread
     * badge, the unread/read filters, and the library update's skip restrictions — saw the
     * primary source's chapters alone. A manga whose merged source is the one still releasing
     * therefore never showed an unread chapter and, with "skip entries with unread chapters" or
     * "skip unstarted entries" on, was skipped by the update entirely. Counted from the same
     * merged list the chapter screen shows, so the badge and the list agree.
     */
    private suspend fun List<LibraryManga>.withMergedCounts(): List<LibraryManga> {
        // A manga in several categories is several rows; count it once.
        val counts = distinctBy { it.manga.id }
            .filter { it.manga.id?.let { id -> mergedSources.hasMerges(id) } == true }
            .associate { it.manga.id!! to getChapter.awaitAll(it.manga) }
        if (counts.isEmpty()) return this
        return map { libraryManga ->
            counts[libraryManga.manga.id]?.let { libraryManga.recount(it) } ?: libraryManga
        }
    }
}

/** [this] with every count taken from [chapters] instead of from `library_view`. */
internal fun LibraryManga.recount(chapters: List<Chapter>) = copy(
    totalChapters = chapters.size,
    read = chapters.count { it.read },
    unread = chapters.count { !it.read },
    bookmarkCount = chapters.count { it.bookmark },
    latestUpdate = chapters.maxOfOrNull { it.date_upload } ?: latestUpdate,
    lastFetch = chapters.maxOfOrNull { it.date_fetch } ?: lastFetch,
)
