package karasu.domain.manga.interval

import io.kotest.matchers.shouldBe
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Test

class ReleaseLabelTest {

    private val zone = ZoneId.of("UTC")

    // A Tuesday, midday.
    private val now = Instant.parse("2026-03-10T12:00:00Z").toEpochMilli()
    private val day = 24 * 60 * 60 * 1000L
    private val week = 7 * day

    private fun estimate(nextRelease: Long, interval: Long = week, spread: Long = 0) =
        ReleaseEstimate(nextRelease, interval, spread)

    private fun label(estimate: ReleaseEstimate) = estimate.releaseLabel(now, zone)

    @Test
    fun `expected today reads as today`() {
        label(estimate(now + 3 * 60 * 60 * 1000)) shouldBe ReleaseLabel.Today
    }

    @Test
    fun `overdue but not stalled also reads as today, because it is still coming`() {
        label(estimate(now - 2 * day)) shouldBe ReleaseLabel.Today
    }

    @Test
    fun `expected tomorrow reads as tomorrow`() {
        label(estimate(now + day)) shouldBe ReleaseLabel.Tomorrow
    }

    @Test
    fun `a proven weekly series names its weekday`() {
        // Next Friday, and it has never missed a Friday.
        val friday = estimate(now + 3 * day, interval = week, spread = 0)
        label(friday) shouldBe ReleaseLabel.EveryWeekOn(DayOfWeek.FRIDAY)
    }

    @Test
    fun `a wobbly weekly series names the edges of its window instead of a day`() {
        val friday = estimate(now + 3 * day, interval = week, spread = day)
        label(friday) shouldBe ReleaseLabel.Between(DayOfWeek.THURSDAY, DayOfWeek.SATURDAY)
    }

    @Test
    fun `a weekly series that wanders across most of the week gets a date`() {
        val friday = estimate(now + 3 * day, interval = week, spread = 2 * day)
        label(friday) shouldBe ReleaseLabel.OnDate(LocalDate.of(2026, 3, 13))
    }

    @Test
    fun `an unproven weekly series falls back to a date`() {
        // Half a cycle either side: naming a weekday would be a coin flip across most of a week.
        val friday = estimate(now + 3 * day, interval = week, spread = week / 2)
        label(friday) shouldBe ReleaseLabel.OnDate(LocalDate.of(2026, 3, 13))
    }

    @Test
    fun `a monthly series gets a date, since no weekday repeats for it`() {
        val monthly = estimate(now + 20 * day, interval = 30 * day, spread = day)
        label(monthly) shouldBe ReleaseLabel.OnDate(LocalDate.of(2026, 3, 30))
    }

    @Test
    fun `a stalled series claims nothing at all`() {
        // Three whole cycles overdue: the date it missed is not worth printing.
        label(estimate(now - 30 * day, interval = week)) shouldBe null
    }
}
