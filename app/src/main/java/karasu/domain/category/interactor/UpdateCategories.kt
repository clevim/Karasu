package karasu.domain.category.interactor

import eu.kanade.tachiyomi.data.database.models.Category
import karasu.domain.category.CategoryRepository
import karasu.domain.category.models.CategoryUpdate

class UpdateCategories(
    private val categoryRepository: CategoryRepository,
) {
    suspend fun await(updates: List<CategoryUpdate>) = categoryRepository.updateAll(updates)
    suspend fun awaitOne(update: CategoryUpdate) = categoryRepository.update(update)
}
