package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import karasu.data.DatabaseHandler
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.models.CategoryRuleTargets

class CategoriesBackupRestorer(
    private val getCategories: GetCategories = Injekt.get(),
    private val handler: DatabaseHandler = Injekt.get(),
) {
    suspend fun restoreCategories(backupCategories: List<BackupCategory>, onComplete: () -> Unit) {
        // Get categories from file and from db
        handler.await(true) {
            val categories = backupCategories.map { it.getCategoryImpl() }
            // Iterate over them
            categories.forEach { category ->
                // Used to know if the category is already in the db
                var found = false
                for (dbCategory in getCategories.await()) {
                    // If the category is already in the db, assign the id to the file's category
                    // and do nothing
                    if (category.name == dbCategory.name) {
                        category.id = dbCategory.id
                        found = true
                        break
                    }
                }
                // If the category isn't in the db, remove the id and insert a new category
                // Store the inserted id in the category
                if (!found) {
                    // Let the db assign the id
                    category.id = null
                    categoriesQueries.insert(
                        name = category.name,
                        mangaOrder = category.mangaOrderToString(),
                        sort = category.order.toLong(),
                        flags = category.flags.toLong(),
                        // Resolved below: the categories a rule points at may not exist yet.
                        rule = null,
                    )
                    category.id = categoriesQueries.selectLastInsertedRowId().executeAsOneOrNull()?.toInt()
                }
            }

            // Rules travel with their targets named, so they can only be turned back into ids
            // once every category in the backup has one.
            val nameToId = categories.mapNotNull { category ->
                category.id?.let { category.name to it.toLong() }
            }.toMap()
            categories.forEach { category ->
                val id = category.id?.toLong() ?: return@forEach
                val rule = CategoryRuleTargets.namesToIds(category.rule, nameToId) ?: return@forEach
                categoriesQueries.updateRule(rule = rule, id = id)
            }
        }

        onComplete()
    }
}
