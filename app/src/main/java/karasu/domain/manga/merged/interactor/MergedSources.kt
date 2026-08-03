package karasu.domain.manga.merged.interactor

import karasu.domain.manga.merged.MergedMangaRepository
import karasu.domain.manga.models.MergedMangaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ponytail: one multi-method interactor instead of the usual Get/Insert/Delete trio,
// same as GetChapter. Split it if the read and write paths ever diverge.
class MergedSources(private val repository: MergedMangaRepository) {

    // ponytail: caches only *which* manga are merged, not the merges themselves. Chapter
    // reads run per manga across the whole library, and almost no manga is merged, so this
    // turns the common case into a set lookup. Any write drops the set wholesale; make it
    // incremental only if merges ever become high-churn.
    private val mutex = Mutex()
    private var mergedMangaIds: Set<Long>? = null

    /** Cheap enough to call on every chapter read. */
    suspend fun hasMerges(mangaId: Long): Boolean = cachedIds().contains(mangaId)

    /** Extra sources for [mangaId], already ordered by priority. */
    suspend fun await(mangaId: Long): List<MergedMangaSource> = repository.getByMangaId(mangaId)

    suspend fun add(mangaId: Long, source: Long, url: String, priority: Int) {
        repository.insert(mangaId, source, url, priority)
        invalidate()
    }

    /**
     * Links [source] to [mangaId] behind every merge it already has, and says whether it did.
     *
     * Callers follow a true with a sync, since a brand new merge has no chapters stored yet.
     * Re-adding a source, or merging a manga with itself, is a no-op rather than an error: both
     * arrive from a user tapping something twice, not from a bug worth surfacing.
     */
    suspend fun addAtEnd(mangaId: Long, source: Long, url: String, ownSource: Long? = null): Boolean {
        if (source == ownSource) return false
        val existing = await(mangaId)
        if (existing.any { it.source == source }) return false
        add(mangaId, source, url, (existing.maxOfOrNull { it.priority } ?: 0) + 1)
        return true
    }

    suspend fun reorder(mangaId: Long, source: Long, priority: Int) =
        repository.updatePriority(mangaId, source, priority)

    suspend fun remove(mangaId: Long, source: Long) {
        repository.delete(mangaId, source)
        invalidate()
    }

    suspend fun clear(mangaId: Long) {
        repository.deleteByMangaId(mangaId)
        invalidate()
    }

    private suspend fun cachedIds(): Set<Long> = mutex.withLock {
        mergedMangaIds ?: repository.getMangaIdsWithMerges().also { mergedMangaIds = it }
    }

    private suspend fun invalidate() = mutex.withLock { mergedMangaIds = null }
}
