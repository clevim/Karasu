package karasu.domain.manga.interval

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import karasu.domain.manga.interval.ReleaseEstimate.Companion.MAX_INTERVAL
import karasu.domain.manga.interval.ReleaseEstimate.Companion.MIN_POLL
import karasu.domain.manga.interval.ReleaseEstimate.Companion.estimate
import org.junit.jupiter.api.Test

class FetchIntervalTest {

    private val now = 1_700_000_000_000L
    private val day = 24 * 60 * 60 * 1000L
    private val week = 7 * day

    /** [count] releases every seven days, the newest one [lastAgo] ago. */
    private fun weekly(count: Int, lastAgo: Long) =
        (0 until count).map { now - lastAgo - it * week }

    private fun none() = emptyList<Long>()

    @Test
    fun `nothing to go on yields no estimate at all`() {
        estimate(listOf(0L, 0L, 0L), none(), now) shouldBe null
        estimate(none(), none(), now) shouldBe null
    }

    @Test
    fun `two releases are not enough to call it a rhythm`() {
        estimate(listOf(now - day, now - 8 * day), none(), now) shouldBe null
    }

    @Test
    fun `a steady weekly series expects its next chapter a week out`() {
        val estimate = estimate(weekly(count = 10, lastAgo = day), none(), now)!!
        estimate.interval shouldBe week
        estimate.nextRelease shouldBe (now - day) + week
    }

    @Test
    fun `the window narrows as a steady series proves itself`() {
        val young = estimate(weekly(count = 3, lastAgo = day), none(), now)!!
        val proven = estimate(weekly(count = 10, lastAgo = day), none(), now)!!
        // Three releases only say "roughly weekly", so half a cycle either side.
        young.spread shouldBe week / 2
        // Ten identical gaps have never missed the day, so the measured wobble is nothing.
        proven.spread shouldBe 0
        // Scheduling still keeps its floor, which is a policy rather than a reading.
        proven.checkWindow shouldBe week / 5
    }

    @Test
    fun `an erratic series keeps a wide window instead of pretending to know the day`() {
        // Gaps of 2, 30, 3, 25, 4, 28... days: a real rhythm, just not a tidy one.
        val gaps = listOf(2L, 30L, 3L, 25L, 4L, 28L, 2L, 26L)
        val dates = gaps.runningFold(now - day) { date, gap -> date - gap * day }
        val erratic = estimate(dates, none(), now)!!
        val steady = estimate(weekly(count = 10, lastAgo = day), none(), now)!!
        (erratic.spread > steady.spread) shouldBe true
    }

    @Test
    fun `inside its window a manga is polled at its own pace`() {
        val estimate = estimate(weekly(count = 10, lastAgo = week), none(), now)!!
        estimate.isDue(now) shouldBe true
        estimate.nextCheck(now) shouldBe now + estimate.pollInterval()
    }

    @Test
    fun `a tight window is polled harder than a loose one`() {
        val proven = estimate(weekly(count = 10, lastAgo = week), none(), now)!!
        val young = estimate(weekly(count = 3, lastAgo = week), none(), now)!!
        (proven.pollInterval() < young.pollInterval()) shouldBe true
    }

    @Test
    fun `outside its window a manga waits for the window to open`() {
        val estimate = estimate(weekly(count = 10, lastAgo = day), none(), now)!!
        estimate.isDue(now) shouldBe false
        estimate.nextCheck(now) shouldBe estimate.nextRelease - estimate.checkWindow
    }

    @Test
    fun `a series silent for several cycles is stalled and backs off`() {
        val estimate = estimate(weekly(count = 10, lastAgo = 60 * day), none(), now)!!
        estimate.isStalled(now) shouldBe true
        estimate.nextCheck(now) shouldBe now + MAX_INTERVAL
    }

    @Test
    fun `one hiatus does not stretch the estimate the way an average would`() {
        // Weekly, except for a single three-month gap in the middle.
        val dates = weekly(count = 5, lastAgo = day) + weekly(count = 5, lastAgo = 90 * day)
        estimate(dates, none(), now)!!.interval shouldBe week
    }

    @Test
    fun `fetch dates carry the rhythm when the source reports no upload date`() {
        val seenAt = weekly(count = 10, lastAgo = day)
        val estimate = estimate(uploadDates = List(10) { 0L }, fetchDates = seenAt, now = now)!!
        estimate.interval shouldBe week
    }

    @Test
    fun `a bulk import is one event, not a hundred releases`() {
        // Adding a manga writes a fetch date per chapter, milliseconds apart. On its own that
        // says nothing, and it must not be read as a manga releasing every few milliseconds.
        val import = (0 until 100).map { now - 40 * day + it }
        estimate(none(), import, now) shouldBe null

        // Three real releases after the import are what makes it usable.
        val since = listOf(now - day, now - 8 * day, now - 15 * day)
        estimate(none(), import + since, now)!!.interval shouldBe week
    }

    @Test
    fun `upload dates win over fetch dates when the source provides them`() {
        val monthlyUploads = (0 until 10).map { now - day - it * 30 * day }
        val weeklyFetches = weekly(count = 10, lastAgo = day)
        estimate(monthlyUploads, weeklyFetches, now)!!.interval shouldBe 30 * day
    }

    @Test
    fun `future upload dates are ignored rather than trusted`() {
        val dates = listOf(now + 30 * day) + weekly(count = 10, lastAgo = day)
        estimate(dates, none(), now)!!.nextRelease shouldBe (now - day) + week
    }

    /** Weekly, but every release posts a second part a few hours later. */
    private fun batchedWeekly(count: Int, lastAgo: Long) =
        weekly(count, lastAgo).flatMap { listOf(it, it - 3 * 60 * 60 * 1000L) }

    @Test
    fun `chapters posted the same day are one release, not several`() {
        val estimate = estimate(batchedWeekly(count = 6, lastAgo = day), none(), now)!!
        // Counting the second part as its own release halves the median gap and blows the
        // spread up to weeks, which is how a plainly weekly series ends up claiming it is
        // due today give or take a fortnight.
        estimate.interval shouldBe week
        estimate.nextRelease shouldBe (now - day) + week
    }

    @Test
    fun `a single day's worth of chapters is not a rhythm`() {
        val burst = (0 until 10).map { now - 2 * day - it * 2 * 60 * 60 * 1000L }
        estimate(burst, none(), now) shouldBe null
    }

    @Test
    fun `a daily series is never checked more often than the floor`() {
        val daily = (0 until 10).map { now - day - it * day }
        val estimate = estimate(daily, none(), now)!!
        (estimate.nextCheck(now) >= now + MIN_POLL) shouldBe true
    }

    /**
     * The watcher re-reads next_check every few hours and fetches whatever is due, so a check
     * that could land on or before now would be re-fetched on every single run, forever. Nothing
     * else in the system bounds that, which makes this the property that keeps it from becoming
     * a permanent background loop.
     */
    @Test
    fun `every possible estimate schedules its next check strictly in the future`() {
        val shapes = mapOf(
            "steady weekly" to weekly(count = 10, lastAgo = day),
            "weekly, mid window" to weekly(count = 10, lastAgo = week),
            "weekly, overdue" to weekly(count = 10, lastAgo = 9 * day),
            "young" to weekly(count = 3, lastAgo = day),
            "stalled" to weekly(count = 10, lastAgo = 200 * day),
            "daily" to (0 until 10).map { now - day - it * day },
            "monthly" to (0 until 10).map { now - day - it * 30 * day },
            "batched weekly" to batchedWeekly(count = 10, lastAgo = day),
        )
        shapes.forEach { (shape, dates) ->
            val estimate = estimate(dates, none(), now)!!
            val next = estimate.nextCheck(now)
            withClue(shape) {
                (next >= now + MIN_POLL) shouldBe true
                (next <= now + MAX_INTERVAL) shouldBe true
            }
        }
    }
}
