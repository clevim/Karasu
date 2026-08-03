package karasu.domain.manga.merged.interactor

import eu.kanade.tachiyomi.source.SourceManager
import java.util.concurrent.ConcurrentHashMap
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.chapterNumberKey
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.models.MergedMangaSource

/** What is wrong with a merge, if anything. */
enum class MergeHealth {
    OK,

    /** The extension the merge points at isn't installed any more. */
    SOURCE_MISSING,

    /** The source no longer has the entry this merge was made against. */
    ENTRY_GONE,

    /** Linked, but its chapters were never pulled in, so it contributes nothing yet. */
    NEVER_SYNCED,

    /** Has chapters, but they don't look like this manga's — most likely the wrong search result. */
    LIKELY_WRONG_MANGA,
}

/**
 * Whether a manga's merges still point at something real.
 *
 * A merge is made once, from a search result, and then never looked at again — but the thing it
 * points at keeps moving. Extensions get uninstalled, entries get pulled, and the easiest mistake
 * of all is picking the wrong result in the first place, which looks identical to a working merge
 * until you notice the chapter numbers make no sense. Nothing surfaced any of that; the merge just
 * quietly stopped being worth anything.
 *
 * Note what is deliberately *not* a problem here: a merge that adds no new chapters. Before the
 * page-list fallback existed that meant a pointless merge, but a source that only duplicates what
 * you already have is now exactly what gets read when the primary breaks.
 */
class MergedSourceHealth(
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
    private val mergedSources: MergedSources,
) {

    /**
     * Merges whose source answered that the entry is gone, keyed by (manga, source).
     *
     * Recorded by `MergedSourceSync`, because that is the only thing that talks to these sources
     * on a schedule — checking here would mean a request per merge every time the dialog opens.
     * In memory: it is a cache of something the next sync re-establishes either way.
     */
    private val gone = ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    fun recordGone(mangaId: Long, sourceId: Long) {
        gone.add(mangaId to sourceId)
    }

    fun recordAlive(mangaId: Long, sourceId: Long) {
        gone.remove(mangaId to sourceId)
    }

    /** Health of every merge on [mangaId], keyed by source id. */
    suspend fun await(mangaId: Long): Map<Long, MergeHealth> {
        val merges = mergedSources.await(mangaId)
        if (merges.isEmpty()) return emptyMap()
        val ownNumbers = numbersOf(mangaId)
        return merges.associate { it.source to health(mangaId, it, ownNumbers) }
    }

    private suspend fun health(mangaId: Long, merge: MergedMangaSource, ownNumbers: List<Float>): MergeHealth {
        if (sourceManager.get(merge.source) == null) return MergeHealth.SOURCE_MISSING
        if (mangaId to merge.source in gone) return MergeHealth.ENTRY_GONE

        // No row means nothing was ever stored under this merge, or it was deleted from under it.
        val childId = getManga.awaitByUrlAndSource(merge.url, merge.source)?.id
            ?: return MergeHealth.ENTRY_GONE

        val theirNumbers = numbersOf(childId)
        return when {
            theirNumbers.isEmpty() -> MergeHealth.NEVER_SYNCED
            looksLikeWrongManga(ownNumbers, theirNumbers) -> MergeHealth.LIKELY_WRONG_MANGA
            else -> MergeHealth.OK
        }
    }

    /** Raw, so a merged manga's numbers are its own rather than the whole merged list's. */
    private suspend fun numbersOf(mangaId: Long): List<Float> =
        getChapter.awaitAllRaw(mangaId, false)
            .filter { it.isRecognizedNumber }
            .map { it.chapter_number }
}

/**
 * Whether [theirs] looks like a different manga than [own].
 *
 * Two sources for the same manga that both cover chapter 12 will both call it 12 — that is the
 * whole assumption the merge is built on, and the fallback with it. So chapter ranges that overlap
 * without sharing a single number mean the numbering isn't the same numbering, which in practice
 * means the wrong entry was picked.
 *
 * Ranges that don't overlap prove nothing and are left alone: a source that only carries the newer
 * chapters is the most useful kind of merge there is.
 */
internal fun looksLikeWrongManga(own: List<Float>, theirs: List<Float>): Boolean {
    if (own.isEmpty() || theirs.isEmpty()) return false
    if (theirs.max() < own.min() || theirs.min() > own.max()) return false
    val ownKeys = own.mapTo(HashSet()) { chapterNumberKey(it) }
    return theirs.none { chapterNumberKey(it) in ownKeys }
}
