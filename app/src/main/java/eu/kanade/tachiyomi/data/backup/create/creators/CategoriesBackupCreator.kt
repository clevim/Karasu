package eu.kanade.tachiyomi.data.backup.create.creators

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.models.CategoryRuleTargets

class CategoriesBackupCreator(
    private val getCategories: GetCategories = Injekt.get(),
) {
    /**
     * Backup the categories of library
     *
     * @return list of [BackupCategory] to be backed up
     */
    suspend operator fun invoke(): List<BackupCategory> {
        val categories = getCategories.await()
        val idToName = categories.mapNotNull { category ->
            category.id?.let { it.toLong() to category.name }
        }.toMap()
        return categories.map { category ->
            BackupCategory.copyFrom(category).apply {
                rule = CategoryRuleTargets.idsToNames(category.rule, idToName)
            }
        }
    }
}
