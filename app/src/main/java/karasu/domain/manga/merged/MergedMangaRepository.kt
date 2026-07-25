package karasu.domain.manga.merged

import karasu.domain.manga.models.MergedMangaSource

interface MergedMangaRepository {
    suspend fun getByMangaId(mangaId: Long): List<MergedMangaSource>
    suspend fun getMangaIdsWithMerges(): Set<Long>
    suspend fun insert(mangaId: Long, source: Long, url: String, priority: Int)
    suspend fun updatePriority(mangaId: Long, source: Long, priority: Int)
    suspend fun delete(mangaId: Long, source: Long)
    suspend fun deleteByMangaId(mangaId: Long)
}
