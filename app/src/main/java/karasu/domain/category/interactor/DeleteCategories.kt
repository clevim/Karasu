package karasu.domain.category.interactor

import karasu.domain.category.CategoryRepository
import karasu.domain.category.models.CategoryRuleTargets

class DeleteCategories(
    private val categoryRepository: CategoryRepository,
) {
    /**
     * Removes the category, along with any rule that moved manga into it.
     *
     * Rules live on the category they move manga *from*, so deleting a target leaves the rule
     * behind on another category, pointing at an id SQLite is free to hand to the next category
     * created — at which point the rule silently starts filling a category nobody aimed it at.
     */
    suspend fun awaitOne(id: Long) {
        categoryRepository.delete(id)
        categoryRepository.getAll().forEach { category ->
            val categoryId = category.id?.toLong() ?: return@forEach
            val cleaned = CategoryRuleTargets.withoutTarget(category.rule, id)
            if (cleaned != category.rule) {
                categoryRepository.setRule(categoryId, cleaned)
            }
        }
    }
}
