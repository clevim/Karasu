package karasu.domain.track.interactor

import karasu.domain.track.TrackRepository

class GetTrack(
    private val trackRepository: TrackRepository,
) {
    suspend fun awaitAllByMangaId(mangaId: Long?) = mangaId?.let { trackRepository.getAllByMangaId(it) } ?: listOf()

    /** Every track in one read, for passes that look at the whole library. */
    suspend fun awaitAll() = trackRepository.getAll()
}
