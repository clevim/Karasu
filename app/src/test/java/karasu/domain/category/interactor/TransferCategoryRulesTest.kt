package karasu.domain.category.interactor

import eu.kanade.tachiyomi.data.database.models.Category
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import karasu.domain.category.CategoryRepository
import karasu.domain.category.models.CategoryRule
import karasu.domain.category.models.CategoryRuleBundle
import karasu.domain.category.models.CategoryTransition
import karasu.domain.category.models.CategoryUpdate
import karasu.domain.category.models.RuleCondition
import karasu.domain.category.models.RuleField
import karasu.domain.category.models.RuleOperator
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A rule file has to survive landing in a library that numbers its categories differently —
 * which is the normal case, since the whole point is sharing them between devices and people.
 */
class TransferCategoryRulesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun category(id: Int, name: String, rule: String? = null) =
        Category.create(name).also {
            it.id = id
            it.rule = rule
        }

    private fun ruleTo(target: Long) = json.encodeToString(
        CategoryRule(
            listOf(
                CategoryTransition(
                    target = target,
                    conditions = listOf(RuleCondition(RuleField.READ, RuleOperator.GREATER, 0)),
                ),
            ),
        ),
    )

    private class FakeRepository(var categories: List<Category>) : CategoryRepository {
        val written = mutableMapOf<Long, String?>()
        override suspend fun getAll() = categories
        override suspend fun getAllByMangaId(mangaId: Long) = emptyList<Category>()
        override fun getAllAsFlow(): Flow<List<Category>> = throw UnsupportedOperationException()
        override suspend fun insert(category: Category): Long? = null
        override suspend fun insertBulk(categories: List<Category>) = Unit
        override suspend fun update(update: CategoryUpdate) = true
        override suspend fun updateAll(updates: List<CategoryUpdate>) = true
        override suspend fun setRule(id: Long, rule: String?) { written[id] = rule }
        override suspend fun delete(id: Long) = Unit
    }

    private fun transfer(categories: List<Category>): Pair<TransferCategoryRules, FakeRepository> {
        val repo = FakeRepository(categories)
        return TransferCategoryRules(GetCategories(repo), repo) to repo
    }

    @Test
    fun `a rule exported on one device points at the right category on another`() {
        // Here "Lendo" is 1 and it sends things to "Finalizados", which is 2.
        val (exporter, _) = transfer(
            listOf(
                category(1, "Lendo", ruleTo(2L)),
                category(2, "Finalizados"),
            ),
        )
        val bundle = runBlocking { exporter.export() }

        // There, the same two categories exist but with the ids swapped around.
        val (importer, repo) = transfer(
            listOf(
                category(7, "Lendo"),
                category(9, "Finalizados"),
            ),
        )
        val result = runBlocking { importer.import(bundle) }

        assertEquals(1, result.applied)
        assertTrue(result.unknownCategories.isEmpty())

        val written = repo.written.getValue(7L)!!
        val transitions = json.decodeFromString<CategoryRule>(written).transitions
        assertEquals(9L, transitions.single().target, "the target must follow the name, not the id")
    }

    @Test
    fun `categories the importing library does not have are reported, not created`() {
        val (exporter, _) = transfer(
            listOf(
                category(1, "Lendo", ruleTo(2L)),
                category(2, "Finalizados"),
            ),
        )
        val bundle = runBlocking { exporter.export() }

        val (importer, repo) = transfer(listOf(category(3, "Outra")))
        val result = runBlocking { importer.import(bundle) }

        assertEquals(0, result.applied)
        assertEquals(listOf("Lendo"), result.unknownCategories)
        assertTrue(repo.written.isEmpty(), "nothing may be written for a category that is absent")
    }

    @Test
    fun `a transition whose target did not come along is dropped rather than aimed anywhere`() {
        val (exporter, _) = transfer(
            listOf(
                category(1, "Lendo", ruleTo(2L)),
                category(2, "Finalizados"),
            ),
        )
        val bundle = runBlocking { exporter.export() }

        // "Lendo" exists here, its target does not.
        val (importer, repo) = transfer(listOf(category(7, "Lendo")))
        runBlocking { importer.import(bundle) }

        assertNull(repo.written.getValue(7L), "an unaimable rule is cleared, not left dangling")
    }

    @Test
    fun `categories without rules are left out of the file entirely`() {
        val (exporter, _) = transfer(listOf(category(1, "Lendo"), category(2, "Finalizados")))

        val bundle = runBlocking { exporter.export() }

        assertTrue(
            json.decodeFromString<CategoryRuleBundle>(bundle).rules.isEmpty(),
            "expected an empty rule list, got: $bundle",
        )
    }

    @Test
    fun `a file that is not a rule bundle is rejected instead of wiping rules`() {
        val (importer, repo) = transfer(listOf(category(1, "Lendo", ruleTo(2L))))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { importer.import("this is not json") }
        }
        assertTrue(repo.written.isEmpty())
    }
}
