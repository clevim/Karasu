package karasu.domain.manga.failures

import karasu.domain.manga.failures.models.MangaUpdateFailure

interface MangaUpdateFailureRepository {
    /** Manga id to how many updates in a row have failed. Absent means healthy. */
    suspend fun getAll(): Map<Long, Int>

    /** The same rows with the error text and timestamp, for showing rather than counting. */
    suspend fun getAllDetailed(): List<MangaUpdateFailure>
    suspend fun record(mangaId: Long, message: String?, at: Long)
    suspend fun clear(mangaId: Long)
}
