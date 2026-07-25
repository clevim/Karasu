package karasu.domain.category.models

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CategoryRuleTargetsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val rule = json.encodeToString(
        CategoryRule(
            listOf(
                CategoryTransition(
                    target = 7L,
                    conditions = listOf(RuleCondition(RuleField.READ, RuleOperator.GREATER, 0)),
                ),
            ),
        ),
    )

    @Test
    fun `a rule survives a restore that hands its target a different id`() {
        val exported = CategoryRuleTargets.idsToNames(rule, mapOf(7L to "Lendo"))
        // The new database happens to use 42 for the category the old one called 7.
        val imported = CategoryRuleTargets.namesToIds(exported, mapOf("Lendo" to 42L))

        val transitions = json.decodeFromString<CategoryRule>(imported!!).transitions
        assertEquals(1, transitions.size)
        assertEquals(42L, transitions.single().target)
        assertNull(transitions.single().targetName, "the name is only for the trip")
        assertEquals(
            listOf(RuleCondition(RuleField.READ, RuleOperator.GREATER, 0)),
            transitions.single().conditions,
            "conditions come through untouched",
        )
    }

    @Test
    fun `a transition whose category did not come along is dropped`() {
        val exported = CategoryRuleTargets.idsToNames(rule, mapOf(7L to "Lendo"))

        // Restoring into a library without that category: pointing the transition at whatever
        // holds id 7 here would move manga into a category the user never chose.
        assertNull(CategoryRuleTargets.namesToIds(exported, mapOf("Outra" to 42L)))
    }

    @Test
    fun `unreadable rules are dropped instead of crashing the restore`() {
        assertNull(CategoryRuleTargets.namesToIds("not json", mapOf("Lendo" to 1L)))
        assertNull(CategoryRuleTargets.idsToNames(null, emptyMap()))
        assertNull(CategoryRuleTargets.idsToNames("", emptyMap()))
    }

    @Test
    fun `deleting a category takes the transitions aimed at it with it`() {
        // The last transition goes too, so the rule is cleared rather than left empty.
        assertNull(CategoryRuleTargets.withoutTarget(rule, removed = 7L))
    }

    @Test
    fun `only the transitions aimed at the deleted category go`() {
        val twoTargets = json.encodeToString(
            CategoryRule(
                listOf(
                    CategoryTransition(
                        target = 7L,
                        conditions = listOf(RuleCondition(RuleField.READ, RuleOperator.GREATER, 0)),
                    ),
                    CategoryTransition(
                        target = 9L,
                        conditions = listOf(RuleCondition(RuleField.UNREAD, RuleOperator.EQUAL, 0)),
                    ),
                ),
            ),
        )

        val cleaned = CategoryRuleTargets.withoutTarget(twoTargets, removed = 7L)
        val transitions = json.decodeFromString<CategoryRule>(cleaned!!).transitions
        assertEquals(1, transitions.size)
        assertEquals(9L, transitions.single().target)
    }

    @Test
    fun `a rule that never mentioned the deleted category is left exactly as it was`() {
        // Byte for byte: an unrelated delete must not rewrite, and must not discard, other rules.
        assertEquals(rule, CategoryRuleTargets.withoutTarget(rule, removed = 99L))
        assertEquals("not json", CategoryRuleTargets.withoutTarget("not json", removed = 7L))
        assertNull(CategoryRuleTargets.withoutTarget(null, removed = 7L))
    }
}
