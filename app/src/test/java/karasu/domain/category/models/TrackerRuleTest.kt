package karasu.domain.category.models

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The tracker conditions, checked at the evaluator rather than through a real tracking service.
 *
 * The point being defended here is that "untracked" is not a value: it must not accidentally
 * satisfy a status or a score comparison, or every manga nobody tracks would get swept up by the
 * first tracker rule the user writes.
 */
class TrackerRuleTest {

    private val now = 1_700_000_000_000L
    private val done = 9L

    private fun ruleOn(condition: RuleCondition) = CategoryRule(
        listOf(CategoryTransition(target = done, conditions = listOf(condition))),
    )

    @Test
    fun `completed on a tracker moves the manga`() {
        val rule = ruleOn(
            RuleCondition(RuleField.TRACKER_STATUS, RuleOperator.EQUAL, TrackerStatus.COMPLETED.id),
        )
        val input = RuleInput(trackerStatus = TrackerStatus.COMPLETED)

        assertTrue(rule.evaluate(input, now) == done)
    }

    @Test
    fun `an untracked manga matches no tracker status`() {
        val rule = ruleOn(
            RuleCondition(RuleField.TRACKER_STATUS, RuleOperator.EQUAL, TrackerStatus.COMPLETED.id),
        )

        assertNull(rule.evaluate(RuleInput(trackerStatus = null), now))
    }

    @Test
    fun `a different tracker status does not match`() {
        val rule = ruleOn(
            RuleCondition(RuleField.TRACKER_STATUS, RuleOperator.EQUAL, TrackerStatus.COMPLETED.id),
        )

        assertNull(rule.evaluate(RuleInput(trackerStatus = TrackerStatus.READING), now))
    }

    @Test
    fun `score compares on the normalised ten point scale`() {
        val rule = ruleOn(RuleCondition(RuleField.TRACKER_SCORE, RuleOperator.GREATER, 7))

        assertTrue(rule.evaluate(RuleInput(trackerScore = 9), now) == done)
        assertNull(rule.evaluate(RuleInput(trackerScore = 6), now))
    }

    @Test
    fun `an unscored manga is not treated as a zero`() {
        // "score less than 5" must not sweep in everything nobody has rated.
        val rule = ruleOn(RuleCondition(RuleField.TRACKER_SCORE, RuleOperator.LESS, 5))

        assertNull(rule.evaluate(RuleInput(trackerScore = null), now))
    }

    @Test
    fun `tracked is a yes or no about having any tracker at all`() {
        val tracked = ruleOn(RuleCondition(RuleField.TRACKED, RuleOperator.EQUAL, 1))
        val untracked = ruleOn(RuleCondition(RuleField.TRACKED, RuleOperator.EQUAL, 0))

        assertTrue(tracked.evaluate(RuleInput(trackerStatus = TrackerStatus.READING), now) == done)
        assertNull(tracked.evaluate(RuleInput(trackerStatus = null), now))
        assertTrue(untracked.evaluate(RuleInput(trackerStatus = null), now) == done)
    }

    @Test
    fun `status ids are the persisted format and must not drift`() {
        // A rule saved before a reorder of the enum has to keep meaning the same thing.
        assertEqualsId(0L, TrackerStatus.READING)
        assertEqualsId(1L, TrackerStatus.COMPLETED)
        assertEqualsId(2L, TrackerStatus.PLANNING)
        assertEqualsId(3L, TrackerStatus.OTHER)
    }

    private fun assertEqualsId(expected: Long, status: TrackerStatus) =
        assertFalse(expected != status.id, "${status.name} must keep id $expected")
}
