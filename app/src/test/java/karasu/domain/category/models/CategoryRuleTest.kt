package karasu.domain.category.models

import kotlin.time.Duration.Companion.days
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The rules under test are the ones the feature was designed around: a backlog that releases
 * a manga once it is started, and a "reading" state that hands it off to finished, forgotten
 * or missing-source.
 */
class CategoryRuleTest {

    private val now = 1_700_000_000_000L
    private fun daysAgo(days: Int) = now - days.days.inWholeMilliseconds

    private val lendo = 2L
    private val finalizados = 3L
    private val esquecido = 4L
    private val missingSource = 5L

    private val backlog = CategoryRule(
        listOf(
            CategoryTransition(
                target = lendo,
                conditions = listOf(RuleCondition(RuleField.READ, RuleOperator.GREATER, 0)),
            ),
        ),
    )

    private val reading = CategoryRule(
        listOf(
            CategoryTransition(
                target = missingSource,
                conditions = listOf(RuleCondition(RuleField.SOURCE_MISSING, RuleOperator.EQUAL, 1)),
            ),
            CategoryTransition(
                target = finalizados,
                conditions = listOf(
                    RuleCondition(RuleField.UNREAD, RuleOperator.EQUAL, 0),
                    RuleCondition(RuleField.STATUS, RuleOperator.EQUAL, COMPLETED.toLong()),
                ),
            ),
            CategoryTransition(
                target = esquecido,
                conditions = listOf(
                    RuleCondition(RuleField.STATUS, RuleOperator.EQUAL, ONGOING.toLong()),
                    RuleCondition(RuleField.UNREAD, RuleOperator.GREATER, 20),
                    RuleCondition(RuleField.DAYS_SINCE_READ, RuleOperator.GREATER, 90),
                ),
            ),
        ),
    )

    @Test
    fun `backlog releases a manga only once it has been started`() {
        val untouched = RuleInput(read = 0, unread = 30)
        assertNull(backlog.evaluate(untouched, now))

        val started = RuleInput(read = 1, unread = 29, lastRead = daysAgo(1))
        assertEquals(lendo, backlog.evaluate(started, now))
    }

    @Test
    fun `a manga never opened is never called forgotten`() {
        // The whole point of hanging rules off the category you are in: this manga looks
        // exactly like a forgotten one on every count except that it was never read, and its
        // stored timestamp of 0 would otherwise read as "last read in 1970".
        val neverRead = RuleInput(
            read = 0,
            unread = 300,
            lastRead = 0,
            status = ONGOING,
        )

        assertNull(reading.evaluate(neverRead, now))
    }

    @Test
    fun `forgotten needs all three conditions at once`() {
        val forgotten = RuleInput(
            read = 5,
            unread = 25,
            lastRead = daysAgo(120),
            status = ONGOING,
        )
        assertEquals(esquecido, reading.evaluate(forgotten, now))

        // Read recently: still active.
        assertNull(reading.evaluate(forgotten.copy(lastRead = daysAgo(10)), now))
        // Only a couple of chapters behind: not a pile.
        assertNull(reading.evaluate(forgotten.copy(unread = 3), now))
        // Finished publishing: it is a backlog, not a series that ran away.
        assertNull(reading.evaluate(forgotten.copy(status = COMPLETED, unread = 25), now))
    }

    @Test
    fun `caught up on a completed series goes to finished`() {
        val done = RuleInput(read = 40, unread = 0, lastRead = daysAgo(2), status = COMPLETED)
        assertEquals(finalizados, reading.evaluate(done, now))
    }

    @Test
    fun `being caught up on an ongoing series is not being finished`() {
        val upToDate = RuleInput(read = 40, unread = 0, lastRead = daysAgo(2), status = ONGOING)
        assertNull(reading.evaluate(upToDate, now))
    }

    @Test
    fun `order of transitions is their priority`() {
        // Matches both missing-source and forgotten; missing-source is listed first.
        val both = RuleInput(
            read = 5,
            unread = 25,
            lastRead = daysAgo(120),
            status = ONGOING,
            sourceMissing = true,
        )
        assertEquals(missingSource, reading.evaluate(both, now))
    }

    @Test
    fun `a transition with no conditions never fires`() {
        // Otherwise an unfinished rule left in the editor would sweep the whole category.
        val empty = CategoryRule(listOf(CategoryTransition(target = lendo)))
        assertNull(empty.evaluate(RuleInput(read = 10, unread = 10), now))
    }

    @Test
    fun `days since update ignores a manga with no known release date`() {
        val rule = CategoryRule(
            listOf(
                CategoryTransition(
                    target = esquecido,
                    conditions = listOf(
                        RuleCondition(RuleField.DAYS_SINCE_UPDATE, RuleOperator.LESS, 30),
                    ),
                ),
            ),
        )
        assertNull(rule.evaluate(RuleInput(latestUpdate = 0), now))
        assertEquals(esquecido, rule.evaluate(RuleInput(latestUpdate = daysAgo(3)), now))
    }

    @Test
    fun `never read is available as its own condition`() {
        val rule = CategoryRule(
            listOf(
                CategoryTransition(
                    target = backlogId,
                    conditions = listOf(RuleCondition(RuleField.NEVER_READ, RuleOperator.EQUAL, 1)),
                ),
            ),
        )
        assertEquals(backlogId, rule.evaluate(RuleInput(lastRead = 0), now))
        assertNull(rule.evaluate(RuleInput(lastRead = daysAgo(400)), now))
    }

    private companion object {
        const val backlogId = 1L

        // SManga status constants.
        const val ONGOING = 1
        const val COMPLETED = 2
    }
}
