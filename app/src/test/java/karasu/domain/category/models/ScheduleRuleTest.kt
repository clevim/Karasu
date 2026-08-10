package karasu.domain.category.models

import kotlin.time.Duration.Companion.days
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The release-schedule conditions, checked at the evaluator rather than through the database.
 *
 * Two things are being defended. "No estimate" must not read as zero days away, or every entry
 * the app has not learned a rhythm for yet would satisfy the first schedule rule written. And an
 * overdue entry must count as negative days rather than clamping at zero, because "due today or
 * late" is the condition anyone actually wants to write.
 */
class ScheduleRuleTest {

    private val now = 1_700_000_000_000L
    private val soon = 12L

    private fun ruleOn(condition: RuleCondition) = CategoryRule(
        listOf(CategoryTransition(target = soon, conditions = listOf(condition))),
    )

    private fun inDays(n: Long) = now + n.days.inWholeMilliseconds

    @Test
    fun `an entry expected inside the window moves`() {
        val rule = ruleOn(RuleCondition(RuleField.DAYS_UNTIL_RELEASE, RuleOperator.LESS, 3))

        assertTrue(rule.evaluate(RuleInput(nextRelease = inDays(2)), now) == soon)
    }

    @Test
    fun `an entry expected later stays put`() {
        val rule = ruleOn(RuleCondition(RuleField.DAYS_UNTIL_RELEASE, RuleOperator.LESS, 3))

        assertNull(rule.evaluate(RuleInput(nextRelease = inDays(9)), now))
    }

    @Test
    fun `overdue counts as negative days, so it is caught by less than one`() {
        val rule = ruleOn(RuleCondition(RuleField.DAYS_UNTIL_RELEASE, RuleOperator.LESS, 1))

        assertTrue(rule.evaluate(RuleInput(nextRelease = inDays(-4)), now) == soon)
    }

    @Test
    fun `no estimate matches no number of days, in either direction`() {
        val due = ruleOn(RuleCondition(RuleField.DAYS_UNTIL_RELEASE, RuleOperator.LESS, 1))
        val far = ruleOn(RuleCondition(RuleField.DAYS_UNTIL_RELEASE, RuleOperator.GREATER, 1))

        assertNull(due.evaluate(RuleInput(nextRelease = null), now))
        assertNull(far.evaluate(RuleInput(nextRelease = null), now))
    }

    @Test
    fun `stalled is a yes-no field`() {
        val stalled = ruleOn(RuleCondition(RuleField.RELEASE_STALLED, RuleOperator.EQUAL, 1))

        assertTrue(stalled.evaluate(RuleInput(releaseStalled = true), now) == soon)
        assertNull(stalled.evaluate(RuleInput(releaseStalled = false), now))
    }

    @Test
    fun `no estimate is not the same as running`() {
        // The trap: "not stalled" must not sweep in everything the app knows nothing about.
        val running = ruleOn(RuleCondition(RuleField.RELEASE_STALLED, RuleOperator.EQUAL, 0))

        assertNull(running.evaluate(RuleInput(releaseStalled = null), now))
    }
}
