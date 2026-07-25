package karasu.domain.manga.interactor

import karasu.domain.manga.MangaRepository
import karasu.domain.manga.models.MangaUpdate

class UpdateManga (
    private val mangaRepository: MangaRepository,
) {
    suspend fun await(update: MangaUpdate) = mangaRepository.update(update)
    suspend fun awaitAll(updates: List<MangaUpdate>) = mangaRepository.updateAll(updates)
}
