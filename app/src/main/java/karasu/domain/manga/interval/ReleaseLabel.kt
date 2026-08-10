package karasu.domain.manga.interval

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * How to say, in one short phrase, when a manga is next expected.
 *
 * Kept as a shape rather than a string so the wording stays in the resource files, and so the
 * decision of *which* phrasing is honest can be tested without a Context.
 */
sealed interface ReleaseLabel {
    /** Expected today. Also what an overdue entry gets: it has not arrived, so it is still coming. */
    data object Today : ReleaseLabel

    data object Tomorrow : ReleaseLabel

    /** Weekly and proven: the weekday is the whole answer. "Every Wednesday". */
    data class EveryWeekOn(val day: DayOfWeek) : ReleaseLabel

    /** Weekly but wobbly: name the edges of the window rather than a day it may well miss. */
    data class Between(val from: DayOfWeek, val to: DayOfWeek) : ReleaseLabel

    /** Anything that is not weekly, or too uncertain for a weekday to mean anything. */
    data class OnDate(val date: LocalDate) : ReleaseLabel
}

private const val DAY = 24 * 60 * 60 * 1000L

/** Weeks are the only rhythm worth naming a weekday for, and only if the gap really is one. */
private val WEEKLY = (6.5 * DAY).toLong()..(7.5 * DAY).toLong()

/** Past this many days either side, a weekday name says nothing a date would not say better. */
private const val MAX_NAMED_SPREAD_DAYS = 1

/** Below this, the series has never really missed its day, so the day is the answer. */
private const val SAME_DAY = DAY / 2

/**
 * @return null when there is nothing worth claiming — a stalled series has no meaningful next
 *  date, and printing one that passed months ago would be worse than printing nothing.
 */
fun ReleaseEstimate.releaseLabel(
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
): ReleaseLabel? {
    if (isStalled(now)) return null

    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val predicted = Instant.ofEpochMilli(nextRelease).atZone(zone).toLocalDate()
    // Overdue but not stalled means the window is open right now.
    val date = if (predicted.isBefore(today)) today else predicted

    return when {
        date == today -> ReleaseLabel.Today
        date == today.plusDays(1) -> ReleaseLabel.Tomorrow
        interval !in WEEKLY -> ReleaseLabel.OnDate(date)
        // It has never really missed its day, so the day is the whole answer.
        spread < SAME_DAY -> ReleaseLabel.EveryWeekOn(date.dayOfWeek)
        else -> {
            val days = (spread.toDouble() / DAY).roundToLong()
            if (days > MAX_NAMED_SPREAD_DAYS) {
                ReleaseLabel.OnDate(date)
            } else {
                ReleaseLabel.Between(
                    from = date.minusDays(days).dayOfWeek,
                    to = date.plusDays(days).dayOfWeek,
                )
            }
        }
    }
}
