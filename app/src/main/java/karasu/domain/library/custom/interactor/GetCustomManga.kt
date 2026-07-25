package karasu.domain.library.custom.interactor

import karasu.domain.library.custom.CustomMangaRepository

class GetCustomManga(
    private val customMangaRepository: CustomMangaRepository,
) {
    fun subscribeAll() = customMangaRepository.subscribeAll()

    suspend fun getAll() = customMangaRepository.getAll()
}
