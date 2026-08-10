package karasu.domain.manga.interval

import eu.kanade.tachiyomi.domain.manga.models.Manga
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 */
data class ReleaseSchedule(
    val upcoming: List<ScheduledRelease>,
    val stalled: List<ScheduledRelease>,
    val unknown: List<Manga>,
)

data class ScheduledRelease(
    val manga: Manga,
    val estimate: ReleaseEstimate,
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

class GetReleaseSchedule(
    private val getLibraryManga: GetLibraryManga,
    private val fetchInterval: FetchInterval,
) {
    /**
     * @param categories which categories to include. Empty means the whole library, matching how
     *  the same selection is read everywhere else.
     */
    suspend fun await(
        categories: Set<Int> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): ReleaseSchedule {
        val estimates = fetchInterval.awaitAll()
        val library = getLibraryManga.await()
            .filter { categories.isEmpty() || it.category in categories }
            .distinctBy { it.manga.id }
            .map { it.manga }

        val upcoming = mutableListOf<ScheduledRelease>()
        val stalled = mutableListOf<ScheduledRelease>()
        val unknown = mutableListOf<Manga>()

        library.forEach { manga ->
            val estimate = manga.id?.let { estimates[it] }
            when {
                estimate == null -> unknown.add(manga)
                estimate.isStalled(now) -> stalled.add(ScheduledRelease(manga, estimate))
                else -> upcoming.add(ScheduledRelease(manga, estimate))
            }
        }

        return ReleaseSchedule(
            upcoming = upcoming.sortedBy { it.estimate.nextRelease },
            stalled = stalled.sortedByDescending { it.estimate.nextRelease },
            unknown = unknown.sortedBy { it.title },
        )
    }
}
