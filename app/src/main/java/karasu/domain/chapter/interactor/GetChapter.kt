package karasu.domain.chapter.interactor

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import karasu.domain.chapter.ChapterRepository
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.merged.interactor.MergedSources
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map

class GetChapter(
    private val chapterRepository: ChapterRepository,
    private val mergedSources: MergedSources,
    private val getManga: GetManga,
) {
    /**
     * Chapters belonging to [mangaId] itself, never merged.
     *
     * Syncing must use this. `syncChaptersWithSource` deletes every stored chapter the
     * source didn't return, so handing it a merged list would wipe the other sources'
     * chapters on every library update.
     */
    suspend fun awaitAllRaw(mangaId: Long, filterScanlators: Boolean) =
        chapterRepository.getChapters(mangaId, filterScanlators)

    suspend fun awaitAll(mangaId: Long, filterScanlators: Boolean): List<Chapter> {
        val own = chapterRepository.getChapters(mangaId, filterScanlators)
        if (!mergedSources.hasMerges(mangaId)) return own
        return mergeChapters(own, chaptersFromMergedSources(mangaId, filterScanlators))
    }

    suspend fun awaitAll(manga: Manga, filterScanlators: Boolean? = null) =
        awaitAll(manga.id!!, filterScanlators ?: (manga.filtered_scanlators?.isNotEmpty() == true))

    /**
     * Both callers re-sort with `ChapterSort`, so the merged path can drop the query's own
     * ordering and reuse [awaitAll] — which is also what makes a merged source's chapters
     * eligible to be the next unread one.
     */
    suspend fun awaitUnread(mangaId: Long, filterScanlators: Boolean): List<Chapter> {
        if (!mergedSources.hasMerges(mangaId)) return chapterRepository.getUnread(mangaId, filterScanlators)
        return awaitAll(mangaId, filterScanlators).filterNot { it.read }
    }

    suspend fun awaitById(id: Long) = chapterRepository.getChapterById(id)

    suspend fun awaitAllByUrl(chapterUrl: String, filterScanlators: Boolean) =
        chapterRepository.getChaptersByUrl(chapterUrl, filterScanlators)
    suspend fun awaitByUrl(chapterUrl: String, filterScanlators: Boolean) =
        chapterRepository.getChapterByUrl(chapterUrl, filterScanlators)

    suspend fun awaitAllByUrlAndMangaId(chapterUrl: String, mangaId: Long, filterScanlators: Boolean) =
        chapterRepository.getChaptersByUrlAndMangaId(chapterUrl, mangaId, filterScanlators)
    suspend fun awaitByUrlAndMangaId(chapterUrl: String, mangaId: Long, filterScanlators: Boolean) =
        chapterRepository.getChapterByUrlAndMangaId(chapterUrl, mangaId, filterScanlators)

    fun subscribeAll(mangaId: Long, filterScanlators: Boolean) =
        chapterRepository.getChaptersAsFlow(mangaId, filterScanlators).map { own ->
            if (!mergedSources.hasMerges(mangaId)) {
                own
            } else {
                mergeChapters(own, chaptersFromMergedSources(mangaId, filterScanlators))
            }
        }

    private suspend fun chaptersFromMergedSources(
        mangaId: Long,
        filterScanlators: Boolean,
    ): List<Pair<Int, List<Chapter>>> = mergedSources.await(mangaId).mapNotNull { merge ->
        // Each merged source keeps its own manga row, so its chapters sync and download
        // under a real source of their own. Here we only borrow them for the reading list.
        val childId = getManga.awaitByUrlAndSource(merge.url, merge.source)?.id ?: return@mapNotNull null
        merge.priority to chapterRepository.getChapters(childId, filterScanlators)
    }
}

/** The manga's own chapters outrank every merged source. */
private const val OWN_PRIORITY = Int.MIN_VALUE

/**
 * Chapter numbers are Float here but REAL in the database, so two rows for the same chapter
 * can differ in the last bits. Chapters are never a thousandth apart in practice.
 */
private fun Chapter.numberKey(): Int = (chapter_number * 1000f).roundToInt()

/**
 * Folds the [extra] sources' chapter lists into [own], keeping one row per chapter number.
 *
 * The row kept for a number is the one from the highest-priority source that has it, so a
 * manga whose primary covers 1-15 and whose merged source covers 1-20 shows its own 1-15
 * plus only the new 16-20. The choice is deterministic: the same row keeps being shown, so
 * read state stays attached to it instead of flickering between sources.
 */
internal fun mergeChapters(
    own: List<Chapter>,
    extra: List<Pair<Int, List<Chapter>>>,
): List<Chapter> {
    if (extra.isEmpty()) return own

    val ranked = own.map { OWN_PRIORITY to it } +
        extra.flatMap { (priority, chapters) ->
            // An unrecognised number has nothing to deduplicate against, so a merged
            // source's unnumbered chapters would pile up as duplicates of everything.
            // Drop them; the manga's own unnumbered chapters are still kept below.
            chapters.filter { it.isRecognizedNumber }.map { priority to it }
        }

    val (numbered, unnumbered) = ranked.partition { it.second.isRecognizedNumber }

    val merged = numbered
        .groupBy { it.second.numberKey() }
        .map { (_, group) ->
            group.minBy { it.first }.second.copy().apply {
                // Read state lives on whichever row the user happened to open. OR it across
                // the group so that reordering the sources later — which can change the
                // winning row — never makes finished chapters look unread again.
                read = group.any { it.second.read }
                bookmark = group.any { it.second.bookmark }
            }
        }

    return (merged + unnumbered.map { it.second.copy() })
        .sortedByDescending { it.chapter_number }
        // Source order is meaningless across sources, so rebuild it for the merged list.
        // These are copies, so nothing is written back to the database.
        .onEachIndexed { index, chapter -> chapter.source_order = index }
}
