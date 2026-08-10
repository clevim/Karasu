package karasu.domain.manga.interval

import kotlin.math.abs

/**
 * What the app has worked out about when a manga releases.
 *
 * The estimate narrows on its own. The first few chapters only say "roughly weekly", so [spread]
 * stays wide and the manga is checked often; as releases pile up and prove the rhythm is steady,
 * [spread] shrinks and the checks concentrate around the day it actually posts. A series that
 * keeps missing its window is not squeezed further — it is [isStalled], which reads as "the
 * source seems to have stopped posting this" and backs the checks off instead of hammering a
 * dead entry.
 *
 * @param nextRelease when the next chapter is expected.
 * @param interval the typical gap between releases. A measurement, deliberately unclamped: a
 *  monthly series rounded down to a fortnight would show the wrong date wherever it is displayed.
 * @param spread how far either side of [nextRelease] the release realistically lands.
 */
data class ReleaseEstimate(
    val nextRelease: Long,
    val interval: Long,
    val spread: Long,
) {
    /**
     * The window used for scheduling, which is never tighter than [TIGHTEST_SPREAD_FRACTION] of
     * a cycle however steady the series looks.
     *
     * Deliberately not baked into [spread]: sources post at their own hour and a window of zero
     * would have the app arrive exactly late every time, but that is a statement about when to
     * *look*, not about what is known. Flattening the two would make every proven weekly series
     * claim a day and a half of uncertainty it does not have.
     */
    val checkWindow: Long get() = maxOf(spread, interval / TIGHTEST_SPREAD_FRACTION)

    /** The stretch around [nextRelease] where the chapter could show up any moment. */
    fun isDue(now: Long) = now >= nextRelease - checkWindow

    /**
     * Where the release is shown, as opposed to where it is expected.
     *
     * A missed window keeps the entry on today, because for the first days the chapter really is
     * about to land. Past [grace] the miss is accepted as a miss and the date rolls on by
     * whole cycles until it is in the future again — a monthly series that skipped its month is
     * next plausible a month later, not "today" for the four weeks until it counts as stalled.
     * The estimate itself is not touched: the next chapter to arrive rewrites it from the real
     * dates, and until then scheduling keeps working off the window it actually measured.
     */
    fun expectedRelease(now: Long, grace: Long = MISS_GRACE): Long {
        val late = now - nextRelease
        if (late <= grace || interval <= 0) return nextRelease
        return nextRelease + interval * (late / interval + 1)
    }

    /**
     * Overdue by several whole cycles: either dropped by the author or pulled by the source, and
     * in both cases checking it as often as a running series buys nothing. A chapter arriving
     * clears this by itself, since the estimate is rewritten then.
     */
    fun isStalled(now: Long) = now - nextRelease > interval * STALL_CYCLES

    /**
     * Inside the window the manga is polled at [pollInterval]; outside it, the app waits for the
     * window to open, but never longer than [MAX_INTERVAL] — a quarterly series, or simply a
     * wrong guess, still gets looked at within a fortnight.
     */
    fun nextCheck(now: Long): Long = when {
        isStalled(now) -> now + MAX_INTERVAL
        isDue(now) -> now + pollInterval()
        else -> minOf(nextRelease - checkWindow, now + MAX_INTERVAL)
    }

    /** How often to ask while the window is open. Tighter window, tighter polling. */
    fun pollInterval(): Long = (checkWindow / CHECKS_PER_WINDOW).coerceIn(MIN_POLL, days(1))

    companion object {
        /** Never poll a manga faster than this, however sure the estimate is. */
        val MIN_POLL = hours(2)

        /** Never let a manga go longer than this unchecked, however hopeless it looks. */
        val MAX_INTERVAL = days(14)

        /**
         * Roughly how many times a manga is asked while its window is open.
         *
         * Spending a fixed budget per window rather than a fixed cadence is what makes the
         * narrowing pay off: a series that has proven it posts on Thursdays has a window of
         * hours, so those checks land on Thursday; an unproven one has a window of days and the
         * same handful of checks spread thin across it. Either way the window costs about the
         * same, and the app leans on the days a chapter is actually likely.
         */
        private const val CHECKS_PER_WINDOW = 4

        /** Enough to see the rhythm, few enough that a year-old schedule change is forgotten. */
        private const val SAMPLE_SIZE = 10

        /** Below this many releases the rhythm is a guess, so the window stays deliberately wide. */
        private const val CONFIDENT_SAMPLE = 5

        /** Missed windows before a series counts as stalled rather than merely late. */
        private const val STALL_CYCLES = 3

        /**
         * How long a missed release is still read as "any moment now" before the date rolls on.
         * The default only applies where there is no user preference to hand — the calendar and
         * the details header pass the configured one.
         */
        val MISS_GRACE = days(3)

        /** The preference, which is in whole days, as the milliseconds [expectedRelease] wants. */
        fun graceOf(dayCount: Int) = days(dayCount.toLong())

        /**
         * The window never closes tighter than this, because sources post at their own hour.
         *
         * Public because sitting *at* the floor is the only signal of "as certain as this gets",
         * which is what decides whether a weekday can be named out loud.
         */
        const val TIGHTEST_SPREAD_FRACTION = 5

        /**
         * Two chapters landing in the same import are not two releases. Anything closer than
         * this is treated as one event, which is what makes fetch dates usable at all: adding a
         * manga writes hundreds of them milliseconds apart.
         *
         * A day rather than an hour, because a source posting 172 and 172.2 the same afternoon is
         * one release too. Counting those separately drags the median gap towards zero and hands
         * back "expected today, give or take eleven days" for a series that plainly posts weekly.
         * A genuinely daily series survives this: same hour each day is a full 24h apart.
         */
        private val SAME_EVENT_WINDOW = days(1)

        private fun hours(n: Long) = n * 60 * 60 * 1000
        private fun days(n: Long) = hours(24 * n)

        /**
         * What the release rhythm looks like, or null when there is nothing to go on.
         *
         * [uploadDates] is what the source says, and is used whenever it is there. [fetchDates]
         * is when this app first saw each chapter, which is the fallback for the many sources
         * that report no upload date at all: less precise, since it is bounded by how often the
         * library updates, but it is the same rhythm seen through a coarser lens. It only starts
         * saying anything once a few chapters have arrived *after* the manga was added — before
         * that every fetch date is the bulk import, which [SAME_EVENT_WINDOW] collapses.
         */
        fun estimate(uploadDates: List<Long>, fetchDates: List<Long>, now: Long): ReleaseEstimate? =
            estimateFrom(uploadDates, now) ?: estimateFrom(fetchDates, now)

        private fun estimateFrom(dates: List<Long>, now: Long): ReleaseEstimate? {
            val events = collapse(dates.filter { it in 1..now }.sortedDescending())
            if (events.size < 3) return null

            val gaps = events.zipWithNext { newer, older -> newer - older }
            if (gaps.isEmpty()) return null
            val interval = median(gaps)

            // How far off the typical release lands from the typical gap. Steady series drive
            // this towards zero as samples accumulate, which is what narrows the window; an
            // erratic one keeps it wide instead of pretending to know the day.
            //
            // Stored raw, like the interval: this is what the app has measured. The floor that
            // keeps scheduling sane lives in [checkWindow], where it cannot corrupt the reading.
            val spread = if (events.size < CONFIDENT_SAMPLE) {
                // Too few releases to trust the shape of anything, so cover half a cycle.
                interval / 2
            } else {
                median(gaps.map { abs(it - interval) })
            }.coerceAtMost(interval)

            return ReleaseEstimate(
                nextRelease = events.first() + interval,
                interval = interval,
                spread = spread,
            )
        }

        /** Newest first, dropping anything that lands inside the previous event's window. */
        private fun collapse(datesNewestFirst: List<Long>): List<Long> {
            val kept = mutableListOf<Long>()
            for (date in datesNewestFirst) {
                if (kept.isEmpty() || kept.last() - date >= SAME_EVENT_WINDOW) kept.add(date)
                if (kept.size == SAMPLE_SIZE) break
            }
            return kept
        }

        private fun median(values: List<Long>): Long = values.sorted()[values.size / 2]
    }
}
