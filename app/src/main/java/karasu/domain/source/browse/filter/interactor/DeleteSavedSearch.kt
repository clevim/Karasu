package karasu.domain.source.browse.filter.interactor

import karasu.domain.source.browse.filter.SavedSearchRepository

class DeleteSavedSearch(
    private val repository: SavedSearchRepository,
) {
    suspend fun await(searchId: Long) = repository.deleteById(searchId)
}
