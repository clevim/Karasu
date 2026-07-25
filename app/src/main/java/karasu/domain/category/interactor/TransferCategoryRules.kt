package karasu.domain.category.interactor

import karasu.domain.category.CategoryRepository
import karasu.domain.category.models.CategoryRuleBundle
import karasu.domain.category.models.CategoryRuleTargets
import karasu.domain.category.models.ExportedCategoryRule
import kotlinx.serialization.json.Json

/**
 * Moves category rules in and out of the app as a standalone file.
 *
 * Both directions go through [CategoryRuleTargets], so a rule travels naming the categories it
 * points at instead of their local ids — the same trick backups use, and the reason a bundle
 * written on one device means the same thing on another.
 */
class TransferCategoryRules(
    private val getCategories: GetCategories,
    private val categoryRepository: CategoryRepository,
) {

    data class ImportResult(
        val applied: Int,
        /** Categories named in the file that this library does not have. */
        val unknownCategories: List<String>,
    )

    suspend fun export(): String {
        val categories = getCategories.await()
        val idToName = categories.mapNotNull { c -> c.id?.let { it.toLong() to c.name } }.toMap()

        val rules = categories.mapNotNull { category ->
            val named = CategoryRuleTargets.idsToNames(category.rule, idToName) ?: return@mapNotNull null
            ExportedCategoryRule(category = category.name, rule = named)
        }

        return json.encodeToString(CategoryRuleBundle(rules = rules))
    }

    /**
     * Applies [raw] to the categories that already exist here.
     *
     * Categories are never created: importing a bundle should not silently reshape a library,
     * and a rule pointing at a category the user does not have is a rule they cannot have
     * meant. Those are reported back instead so the screen can say what was skipped.
     *
     * @throws IllegalArgumentException if the file is not a rule bundle this build understands.
     */
    suspend fun import(raw: String): ImportResult {
        val bundle = try {
            json.decodeFromString<CategoryRuleBundle>(raw)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a category rule file", e)
        }
        require(bundle.version <= CategoryRuleBundle.VERSION) {
            "This rule file was written by a newer version of Karasu"
        }

        val categories = getCategories.await()
        val nameToId = categories.mapNotNull { c -> c.id?.let { c.name to it.toLong() } }.toMap()

        var applied = 0
        val unknown = mutableListOf<String>()

        bundle.rules.forEach { exported ->
            val id = nameToId[exported.category]
            if (id == null) {
                unknown += exported.category
                return@forEach
            }
            // Transitions aimed at a category that did not come along are dropped by namesToIds,
            // which can leave nothing at all — that is a cleared rule, not a failure.
            val rule = CategoryRuleTargets.namesToIds(exported.rule, nameToId)
            categoryRepository.setRule(id, rule)
            applied++
        }

        return ImportResult(applied = applied, unknownCategories = unknown)
    }

    private companion object {
        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}
