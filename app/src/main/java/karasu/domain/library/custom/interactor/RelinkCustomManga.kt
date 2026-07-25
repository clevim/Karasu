package karasu.domain.library.custom.interactor

import karasu.domain.library.custom.CustomMangaRepository

class RelinkCustomManga(
    private val customMangaRepository: CustomMangaRepository,
) {
    suspend fun await(oldId: Long, newId: Long) = customMangaRepository.relinkCustomManga(oldId, newId)
}
