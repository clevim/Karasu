package eu.kanade.tachiyomi.data.migration

import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

/**
 * The day-and-hour slot the pass is scheduled at.
 *
 * Worth its own test because a wrong answer here is invisible: the job simply fires on the wrong
 * night, and nothing reports it.
 */
class AutoMigrateScheduleTest {

    // A Wednesday, mid-afternoon.
    private val now = ZonedDateTime.of(2026, 8, 12, 15, 30, 0, 0, SAO_PAULO)

    private fun hoursUntil(day: Int, hour: Int, from: ZonedDateTime = now) =
        Duration.ofMillis(AutoMigrateJob.millisUntil(day, hour, from)).toHours()

    @Test
    fun `daily picks the next occurrence of the hour`() {
        // 21:00 is still ahead today.
        hoursUntil(AutoMigrateJob.DAILY, 21) shouldBe 5L
        // 03:00 has passed, so it is tomorrow's.
        hoursUntil(AutoMigrateJob.DAILY, 3) shouldBe 11L
    }

    @Test
    fun `a weekday waits for that weekday`() {
        // Wednesday is 3; today's 21:00 has not passed, so it is tonight.
        hoursUntil(day = 3, hour = 21) shouldBe 5L
        // Friday is 5: 03:00 has passed today, so the walk starts at Thursday 03:00 and lands on
        // Friday 03:00 — a day and a half out, not two days.
        hoursUntil(day = 5, hour = 3) shouldBe 35L
        // Tuesday was yesterday, so it is nearly a full week away.
        hoursUntil(day = 2, hour = 21) shouldBe 6 * 24 + 5L
    }

    @Test
    fun `the current hour counts as passed, so rescheduling on fire books the next slot`() {
        val onTheHour = ZonedDateTime.of(2026, 8, 12, 21, 0, 0, 0, SAO_PAULO)
        hoursUntil(AutoMigrateJob.DAILY, 21, onTheHour) shouldBe 24L
        hoursUntil(day = 3, hour = 21, from = onTheHour) shouldBe 7 * 24L
    }

    @Test
    fun `a week containing a clock change is still a week`() {
        // Sao Paulo has no DST any more; London still does, and 2026-10-25 is when it ends.
        val beforeChange = ZonedDateTime.of(2026, 10, 20, 15, 30, 0, 0, ZoneId.of("Europe/London"))
        // Tuesday 15:30 to the following Tuesday 03:00 is 155.5 wall-clock hours, and the night
        // the clocks go back adds a real hour on top: 156, not the 155 a fixed 7 x 24 would give.
        hoursUntil(day = 2, hour = 3, from = beforeChange) shouldBe 156L
    }

    @Test
    fun `pending review survives a round trip and ignores junk`() {
        AutoMigrateJob.parsePending("12:99,7:8") shouldBe mapOf(12L to 99L, 7L to 8L)
        AutoMigrateJob.parsePending("") shouldBe emptyMap()
        AutoMigrateJob.parsePending("12,x:y,3:4:5,9:10") shouldBe mapOf(9L to 10L)
    }

    private companion object {
        val SAO_PAULO: ZoneId = ZoneId.of("America/Sao_Paulo")
    }
}
