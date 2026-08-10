package karasu.domain.manga.interval

import eu.kanade.tachiyomi.data.database.models.MangaImpl
import io.kotest.matchers.shouldBe
import java.time.Instant
import java.time.ZoneId
import org.junit.jupiter.api.Test

class ReleaseCalendarTest {

    private val zone = ZoneId.of("UTC")

    // Midday, so a test that shifts by hours does not accidentally cross a date boundary.
    private val now = Instant.parse("2026-03-10T12:00:00Z").toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L

    private var nextId = 1L

    private fun release(nextRelease: Long, interval: Long = 7 * day, spread: Long = day) =
        ScheduledRelease(
            manga = MangaImpl(id = nextId++, url = "/manga/$nextId"),
            estimate = ReleaseEstimate(nextRelease, interval, spread),
        )

    private fun schedule(vararg upcoming: ScheduledRelease) =
        ReleaseSchedule(upcoming = upcoming.toList(), stalled = emptyList(), unknown = emptyList())

    @Test
    fun `a release lands on the day it is expected`() {
        val inThreeDays = release(now + 3 * day)
        val calendar = schedule(inThreeDays).calendar(dayCount = 7, now = now, zone = zone)

        calendar.days[3].releases shouldBe listOf(inThreeDays)
        calendar.days.filterIndexed { i, _ -> i != 3 }.flatMap { it.releases } shouldBe emptyList()
    }

    @Test
    fun `an overdue release sits on today, not on the day it missed`() {
        // The chapter has not arrived, so it is still coming. A card parked on last Tuesday is a
        // date nobody wants to read, and this is the normal state of an entry mid-window.
        val late = release(now - 4 * day)
        val calendar = schedule(late).calendar(dayCount = 7, now = now, zone = zone)

        calendar.days.first().releases shouldBe listOf(late)
        calendar.later shouldBe emptyList()
    }

    @Test
    fun `every day of the span is present even with nothing on it`() {
        val calendar = schedule().calendar(dayCount = 14, now = now, zone = zone)

        calendar.days.size shouldBe 14
        calendar.days.all { it.releases.isEmpty() } shouldBe true
        calendar.days.first().date shouldBe
            Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    }

    @Test
    fun `anything past the last day is collected under later, soonest first`() {
        val soon = release(now + 2 * day)
        val justPast = release(now + 8 * day)
        val farOff = release(now + 40 * day)

        val calendar = schedule(farOff, justPast, soon).calendar(dayCount = 7, now = now, zone = zone)

        calendar.days[2].releases shouldBe listOf(soon)
        calendar.later shouldBe listOf(justPast, farOff)
    }

    @Test
    fun `several releases on the same day share it`() {
        val first = release(now + day)
        val second = release(now + day + 6 * 60 * 60 * 1000)

        val calendar = schedule(first, second).calendar(dayCount = 7, now = now, zone = zone)

        calendar.days[1].releases shouldBe listOf(first, second)
    }
}
