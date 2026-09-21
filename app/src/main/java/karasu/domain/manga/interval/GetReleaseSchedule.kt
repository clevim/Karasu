package karasu.domain.manga.interval

import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.SManga
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import karasu.domain.recommendation.TrackerExtrasStore
import karasu.domain.manga.interactor.GetLibraryManga

/**
 * The library arranged by when each entry is expected to release.
 *
 * @param upcoming entries with a usable estimate, soonest first.
 * @param stalled entries whose expected chapter is several cycles overdue. Kept apart rather
 *  than shown on a date months in the past, which would be a date nobody wants to read.
 * @param unknown entries the app cannot place yet: too few chapters, or a source that reports no
 *  upload dates and has not been watched long enough to learn the rhythm from fetch times. These
 *  are shown rather than hidden, because a calendar missing half the library with no explanation
 *  reads as broken.
 * @param arrived entries that got a chapter today. The moment one lands its estimate moves on
 *  to the next cycle and it would vanish from today — exactly when the reader wants to see it.
 * @param onHiatus entries the source, or a tracker, says are paused. Not "stalled": that would
 *  read as "the source seems to have stopped", when what happened is the author said so.
 */
data class ReleaseSchedule(
    val upcoming: List<ScheduledRelease>,
    val stalled: List<ScheduledRelease>,
    val unknown: List<Manga>,
    val arrived: List<Manga> = emptyList(),
    val onHiatus: List<Manga> = emptyList(),
)

/**
 * @param guessed the estimate is not the entry's own rhythm but its source's typical one,
 *  stood in until the entry has released enough times to speak for itself.
 */
data class ScheduledRelease(
    val manga: Manga,
    val estimate: ReleaseEstimate,
    val guessed: Boolean = false,
)

/** One day of the calendar. Days with nothing expected are kept so the week reads as a week. */
data class ReleaseDay(
    val date: LocalDate,
    val releases: List<ScheduledRelease>,
)

/**
 * @param days the days the calendar shows, starting today.
 * @param later everything expected after the last of [days], soonest first.
 */
data class ReleaseCalendar(
    val days: List<ReleaseDay>,
    val later: List<ScheduledRelease>,
)

/**
 * Lays [ReleaseSchedule.upcoming] out over the next [dayCount] days.
 *
 * Anything already overdue lands on today rather than on the day it was due: the chapter has
 * not arrived, so it is still coming, and a card sitting on last Tuesday is a date nobody wants
 * to read. Entries whose window is open are in exactly that state most of the time. Once the
 * miss is old enough that this stops being true, [ReleaseEstimate.expectedRelease] has already
 * moved the entry on to its next plausible cycle.
 */
fun ReleaseSchedule.calendar(
    dayCount: Int,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    grace: Long = ReleaseEstimate.MISS_GRACE,
): ReleaseCalendar {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val lastDay = today.plusDays(dayCount - 1L)

    val byDate = upcoming.groupBy { release ->
        val date = Instant.ofEpochMilli(release.estimate.expectedRelease(now, grace)).atZone(zone).toLocalDate()
        if (date.isBefore(today)) today else date
    }

    return ReleaseCalendar(
        days = (0 until dayCount).map { offset ->
            val date = today.plusDays(offset.toLong())
            ReleaseDay(date, byDate[date].orEmpty())
        },
        later = byDate.filterKeys { it.isAfter(lastDay) }
            .values
            .flatten()
            .sortedBy { it.estimate.expectedRelease(now, grace) },
    )
}

/**
 * One month, every day, for the grid view. Days before today are empty: what was expected then
 * either arrived or is on today already.
 */
data class ReleaseMonth(val month: YearMonth, val days: List<ReleaseDay>)

fun ReleaseSchedule.month(
    month: YearMonth,
    now: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    grace: Long = ReleaseEstimate.MISS_GRACE,
): ReleaseMonth {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val byDate = upcoming.groupBy { release ->
        val date = Instant.ofEpochMilli(release.estimate.expectedRelease(now, grace)).atZone(zone).toLocalDate()
        if (date.isBefore(today)) today else date
    }
    return ReleaseMonth(
        month = month,
        days = (1..month.lengthOfMonth()).map { day ->
            val date = month.atDay(day)
            ReleaseDay(date, byDate[date].orEmpty())
        },
    )
}

/** Statuses under which no further chapter is coming. Hiatus is not one: it may end. */
private val FINISHED = setOf(SManga.COMPLETED, SManga.CANCELLED, SManga.LICENSED)

class GetReleaseSchedule(
    private val getLibraryManga: GetLibraryManga,
    private val fetchInterval: FetchInterval,
    private val extras: TrackerExtrasStore,
) {
    /**
     * @param categories which categories to include. Empty means the whole library, matching how
     *  the same selection is read everywhere else.
     * @param onlyCaughtUp leave out entries with more than [CAUGHT_UP_MAX_UNREAD] unread: a new
     *  chapter on a pile of thirty is not an event the reader is waiting for.
     */
    suspend fun await(
        categories: Set<Int> = emptySet(),
        onlyCaughtUp: Boolean = false,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ReleaseSchedule {
        val estimates = fetchInterval.awaitAll()
        val entries = getLibraryManga.await()
            .filter { categories.isEmpty() || it.category in categories }
            .distinctBy { it.manga.id }
            .filter { !onlyCaughtUp || it.unread <= CAUGHT_UP_MAX_UNREAD }
            // Nothing is expected of a finished series, so it belongs on no date and in no
            // bucket: left in, every completed entry would sit under "stalled" forever. A tracker
            // that says finished outranks a source that never updated its status.
            .filter { effectiveStatus(it.manga) !in FINISHED }

        // The typical gap on each source, for entries too new to have one of their own.
        val sourceInterval = entries
            .mapNotNull { entry -> entry.manga.id?.let { estimates[it] }?.let { entry.manga.source to it.interval } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, gaps) -> gaps.sorted()[gaps.size / 2] }

        val startOfToday = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

        val upcoming = mutableListOf<ScheduledRelease>()
        val stalled = mutableListOf<ScheduledRelease>()
        val unknown = mutableListOf<Manga>()
        val arrived = mutableListOf<Manga>()
        val onHiatus = mutableListOf<Manga>()

        entries.forEach { entry ->
            val manga = entry.manga
            // When the app first saw the chapter, not what the source stamped on it: a source
            // with no dates stamps "now" on everything, every fetch, and would "arrive" daily.
            // Added today is not "a chapter arrived today": the whole backlog came in at once.
            // The entry still learns its rhythm from every chapter that arrives from now on.
            if (entry.lastFetch >= startOfToday && manga.date_added < startOfToday) arrived.add(manga)
            if (effectiveStatus(manga) == SManga.ON_HIATUS) {
                onHiatus.add(manga)
                return@forEach
            }
            val estimate = manga.id?.let { estimates[it] }
            when {
                estimate != null && estimate.isStalled(now) -> stalled.add(ScheduledRelease(manga, estimate))
                estimate != null -> upcoming.add(ScheduledRelease(manga, estimate))
                else -> {
                    val typical = sourceInterval[manga.source]
                    val last = entry.lastFetch.takeIf { it > 0 }
                    if (typical != null && last != null) {
                        // Wide on purpose: half a cycle either way says "probably weekly", no more.
                        val guess = ReleaseEstimate(nextRelease = last + typical, interval = typical, spread = typical / 2)
                        if (guess.isStalled(now)) unknown.add(manga) else upcoming.add(ScheduledRelease(manga, guess, guessed = true))
                    } else {
                        unknown.add(manga)
                    }
                }
            }
        }

        return ReleaseSchedule(
            upcoming = upcoming.sortedBy { it.estimate.nextRelease },
            stalled = stalled.sortedByDescending { it.estimate.nextRelease },
            unknown = unknown.sortedBy { it.title },
            arrived = arrived.sortedBy { it.title },
            onHiatus = onHiatus.sortedBy { it.title },
        )
    }

    /** What the trackers say when they say something; otherwise what the source says. */
    private fun effectiveStatus(manga: Manga): Int = extras.get(manga.id)?.status ?: manga.status

    companion object {
        /** "Caught up" for the calendar's purposes. A couple unread is a backlog of an evening. */
        const val CAUGHT_UP_MAX_UNREAD = 2
    }
}
