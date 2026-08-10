package eu.kanade.tachiyomi.data.library

import eu.kanade.tachiyomi.data.library.ReleaseDigestJob.Companion.millisUntilHour
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * When the daily digest fires.
 *
 * The case worth a test is the clock change: a digest scheduled as a flat 24 hour period drifts
 * an hour twice a year and never drifts back, so the arithmetic has to happen on local time.
 */
class ReleaseDigestJobTest {

    private val ny = ZoneId.of("America/New_York")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ny)

    @Test
    fun `an hour still ahead today fires today`() {
        val now = at(2024, 6, 1, 7, 30)

        assertEquals(90.minutes.inWholeMilliseconds, millisUntilHour(9, now))
    }

    @Test
    fun `an hour already past waits for tomorrow`() {
        val now = at(2024, 6, 1, 10, 0)

        assertEquals(23.hours.inWholeMilliseconds, millisUntilHour(9, now))
    }

    @Test
    fun `exactly on the hour waits a full day rather than firing twice`() {
        val now = at(2024, 6, 1, 9, 0)

        assertEquals(24.hours.inWholeMilliseconds, millisUntilHour(9, now))
    }

    @Test
    fun `the day the clocks go forward is 23 hours long, not 24`() {
        // 2024-03-10, 02:00 EST becomes 03:00 EDT.
        val now = at(2024, 3, 9, 9, 30)

        // 22h30m of real time, because the local hour it is waiting for arrives an hour early.
        assertEquals(
            22.hours.inWholeMilliseconds + 30.minutes.inWholeMilliseconds,
            millisUntilHour(9, now),
        )
    }

    @Test
    fun `the day the clocks go back is 25 hours long`() {
        // 2024-11-03, 02:00 EDT becomes 01:00 EST.
        val now = at(2024, 11, 2, 9, 30)

        assertEquals(
            24.hours.inWholeMilliseconds + 30.minutes.inWholeMilliseconds,
            millisUntilHour(9, now),
        )
    }

    @Test
    fun `midnight is a real hour and not a disabled digest`() {
        val now = at(2024, 6, 1, 22, 0)

        assertEquals(2.hours.inWholeMilliseconds, millisUntilHour(0, now))
    }
}
