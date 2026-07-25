package karasu.data.manga.merged

import karasu.data.DatabaseHandler
import karasu.domain.manga.merged.MergedMangaRepository
import karasu.domain.manga.models.MergedMangaSource

class MergedMangaRepositoryImpl(private val handler: DatabaseHandler) : MergedMangaRepository {

    override suspend fun getByMangaId(mangaId: Long): List<MergedMangaSource> =
        handler.awaitList { merged_mangaQueries.findByMangaId(mangaId, ::mapMergedMangaSource) }

    override suspend fun getMangaIdsWithMerges(): Set<Long> =
        handler.awaitList { merged_mangaQueries.findMangaIdsWithMerges() }.toSet()

    override suspend fun insert(mangaId: Long, source: Long, url: String, priority: Int) {
        handler.await { merged_mangaQueries.insert(mangaId, source, url, priority.toLong()) }
    }

    override suspend fun updatePriority(mangaId: Long, source: Long, priority: Int) {
        handler.await { merged_mangaQueries.updatePriority(priority.toLong(), mangaId, source) }
    }

    override suspend fun delete(mangaId: Long, source: Long) {
        handler.await { merged_mangaQueries.deleteByMangaIdAndSource(mangaId, source) }
    }

    override suspend fun deleteByMangaId(mangaId: Long) {
        handler.await { merged_mangaQueries.deleteByMangaId(mangaId) }
    }

    private fun mapMergedMangaSource(
        id: Long,
        mangaId: Long,
        source: Long,
        url: String,
        priority: Long,
    ): MergedMangaSource = MergedMangaSource(id, mangaId, source, url, priority.toInt())
}
