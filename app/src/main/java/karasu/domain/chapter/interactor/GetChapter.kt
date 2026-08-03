package karasu.domain.chapter.interactor

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import karasu.domain.chapter.ChapterRepository
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.merged.interactor.MergedSources
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map

class GetChapter(
    private val chapterRepository: ChapterRepository,
    private val mergedSources: MergedSources,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
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
        return mergeChapters(own, langOf(mangaId), chaptersFromMergedSources(mangaId, filterScanlators))
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
                mergeChapters(own, langOf(mangaId), chaptersFromMergedSources(mangaId, filterScanlators))
            }
        }

    private suspend fun chaptersFromMergedSources(
        mangaId: Long,
        filterScanlators: Boolean,
    ): List<MergedChapters> = mergedSources.await(mangaId).mapNotNull { merge ->
        // Each merged source keeps its own manga row, so its chapters sync and download
        // under a real source of their own. Here we only borrow them for the reading list.
        val childId = getManga.awaitByUrlAndSource(merge.url, merge.source)?.id ?: return@mapNotNull null
        MergedChapters(
            priority = merge.priority,
            lang = sourceManager.getOrStub(merge.source).lang,
            chapters = chapterRepository.getChapters(childId, filterScanlators),
        )
    }

    private suspend fun langOf(mangaId: Long): String =
        getManga.awaitById(mangaId)?.let { sourceManager.getOrStub(it.source).lang }.orEmpty()
}

/** One merged source's contribution to a manga's reading list. */
data class MergedChapters(
    val priority: Int,
    val lang: String,
    val chapters: List<Chapter>,
)

/** The manga's own chapters outrank every merged source. */
private const val OWN_PRIORITY = Int.MIN_VALUE

/**
 * Chapter numbers are Float here but REAL in the database, so two rows for the same chapter
 * can differ in the last bits. Chapters are never a thousandth apart in practice.
 */
internal fun chapterNumberKey(number: Float): Int = (number * 1000f).roundToInt()

private fun Chapter.numberKey(): Int = chapterNumberKey(chapter_number)

/**
 * Folds the [extra] sources' chapter lists into [own], keeping one row per chapter number.
 *
 * The row kept for a number is the one from the highest-priority source that has it, so a manga
 * whose primary covers 1-15 and whose merged source covers 1-20 shows its own 1-15 plus only the
 * new 16-20. The choice is deterministic: the same row keeps being shown, so read state stays
 * attached to it instead of flickering between sources.
 *
 * Sources in different languages collapse into that same single row rather than one row per
 * language — chapter 5 is chapter 5 whoever translated it. The losing languages are not thrown
 * away though: the surviving row lists them in [Chapter.alternates], which is what lets the
 * chapter list show a flag per language and offer the choice when the row is opened.
 */
internal fun mergeChapters(
    own: List<Chapter>,
    ownLang: String,
    extra: List<MergedChapters>,
): List<Chapter> {
    if (extra.isEmpty()) return own

    val ranked = own.map { Ranked(OWN_PRIORITY, ownLang, it) } +
        extra.flatMap { merged ->
            // An unrecognised number has nothing to deduplicate against, so a merged
            // source's unnumbered chapters would pile up as duplicates of everything.
            // Drop them; the manga's own unnumbered chapters are still kept below.
            merged.chapters.filter { it.isRecognizedNumber }
                .map { Ranked(merged.priority, merged.lang, it) }
        }

    val (numbered, unnumbered) = ranked.partition { it.chapter.isRecognizedNumber }

    val merged = numbered
        .groupBy { it.chapter.numberKey() }
        .map { (_, group) ->
            val ordered = group.sortedBy { it.priority }
            // One entry per language: two sources in the same language are the same read, and
            // offering to pick between them would be a choice about nothing.
            val languages = ordered.distinctBy { it.lang }.map { it.chapter }
            ordered.first().chapter.copy().apply {
                // Read state lives on whichever row the user happened to open. OR it across
                // the group so that reordering the sources later — which can change the
                // winning row — never makes finished chapters look unread again.
                read = group.any { it.chapter.read }
                bookmark = group.any { it.chapter.bookmark }
                if (languages.size > 1) alternates = languages
            }
        }

    return (merged + unnumbered.map { it.chapter.copy() })
        .sortedByDescending { it.chapter_number }
        // Source order is meaningless across sources, so rebuild it for the merged list.
        // These are copies, so nothing is written back to the database.
        .onEachIndexed { index, chapter -> chapter.source_order = index }
}

private data class Ranked(val priority: Int, val lang: String, val chapter: Chapter)

/**
 * [this] with the row that stands for [chapterId] replaced by that chapter itself.
 *
 * [mergeChapters] keeps one row per chapter number, so the translations that lost the row exist
 * only inside [Chapter.alternates] — opening one would otherwise not be in the list at all. The
 * row's place in the list is kept, so only the language of that one chapter changes.
 *
 * Returns the list unchanged when [chapterId] is already a row of its own, which is every
 * unmerged manga.
 */
fun List<Chapter>.withAlternate(chapterId: Long): List<Chapter> {
    if (any { it.id == chapterId }) return this
    return map { row ->
        row.alternates.find { it.id == chapterId }
            ?.copy()
            ?.apply { source_order = row.source_order }
            ?: row
    }
}
