package karasu.domain.manga.interval

import eu.kanade.tachiyomi.data.database.models.Chapter
import karasu.data.DatabaseHandler
import kotlin.math.roundToInt

/**
 * Stores when each manga is expected to release, and therefore when to ask about it.
 *
 * A scheduled update that asks every source about every manga every few hours is mostly wasted
 * traffic: a weekly series answers with the same list six times out of seven, and each of those
 * calls is a request the source counts against the user. The release rhythm is already in the
 * chapter list the source just returned, so the estimate costs nothing extra.
 *
 * Only background updating honours any of this. A manual update always asks, so a wrong estimate
 * can never be the reason a user cannot see a new chapter.
 */
class FetchInterval(
    private val handler: DatabaseHandler,
) {
    /** Manga id to the time it is next worth checking. Absent means now, right for new manga. */
    suspend fun awaitDue(): Map<Long, Long> = runCatching {
        handler.awaitList {
            manga_fetch_stateQueries.findAllDue { mangaId, nextCheck -> mangaId to nextCheck }
        }.toMap()
    }.getOrDefault(emptyMap())

    /** The full estimate per manga, for anything that shows the schedule rather than uses it. */
    suspend fun awaitAll(): Map<Long, ReleaseEstimate> = runCatching {
        handler.awaitList {
            manga_fetch_stateQueries.findAll { mangaId, _, nextRelease, interval, spread ->
                mangaId to ReleaseEstimate(nextRelease, interval, spread)
            }
        }.toMap()
    }.getOrDefault(emptyMap())

    /**
     * Records what [chapters] say about [mangaId]'s rhythm, one release per chapter number.
     *
     * Three scanlators posting chapter 12 over three days are one release, not three: counted
     * separately they drag the median gap down to a day or two, and a plainly weekly series is
     * then "stalled" a week after every chapter. The same goes for merged sources, where the
     * merged list keeps the priority source's row and that source may be the slow one — the
     * release is the *earliest* anyone posted it.
     */
    suspend fun record(mangaId: Long, chapters: List<Chapter>, now: Long = System.currentTimeMillis()): Boolean {
        val (uploadDates, fetchDates) = releaseDates(chapters)
        return record(mangaId, uploadDates, fetchDates, now)
    }

    /**
     * Records what [uploadDates] and [fetchDates] say about [mangaId]'s rhythm.
     *
     * Best-effort like the rest of the scheduling side: this only decides what to skip, so it
     * must never be the reason an update fails.
     */
    suspend fun record(
        mangaId: Long,
        uploadDates: List<Long>,
        fetchDates: List<Long>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val manualDays = manualIntervalDays(mangaId)
        return write(mangaId, manualDays, uploadDates, fetchDates, now)
    }

    /** Days between releases the user set for [mangaId], or null when it goes by the measured rhythm. */
    suspend fun manualIntervalDays(mangaId: Long): Int? = runCatching {
        handler.awaitOneOrNull { manga_fetch_stateQueries.findManualInterval(mangaId) }?.manual_interval?.toInt()
    }.getOrNull()

    /**
     * Sets, or with null clears, the user's own answer to how often [mangaId] releases.
     *
     * The estimate is rewritten from [chapters] right away so the calendar and the watcher
     * follow the new rhythm without waiting for a fetch.
     */
    suspend fun setManualIntervalDays(
        mangaId: Long,
        days: Int?,
        chapters: List<Chapter>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        val (uploadDates, fetchDates) = releaseDates(chapters)
        return write(mangaId, days, uploadDates, fetchDates, now)
    }

    private suspend fun write(
        mangaId: Long,
        manualDays: Int?,
        uploadDates: List<Long>,
        fetchDates: List<Long>,
        now: Long,
    ): Boolean {
        val estimate = if (manualDays != null && manualDays > 0) {
            ReleaseEstimate.manual(ReleaseEstimate.daysOf(manualDays), uploadDates, fetchDates, now)
        } else {
            ReleaseEstimate.estimate(uploadDates, fetchDates, now) ?: return false
        }
        return runCatching {
            handler.await {
                manga_fetch_stateQueries.upsert(
                    manga_id = mangaId,
                    next_check = estimate.nextCheck(now),
                    next_release = estimate.nextRelease,
                    interval = estimate.interval,
                    spread = estimate.spread,
                    manual_interval = manualDays?.toLong(),
                )
            }
        }.isSuccess
    }

    /**
     * Backs off a manga whose source just failed.
     *
     * Without this a manga on a broken source stays permanently due — the estimate is only
     * rewritten on success — and the watcher would ask about it every single run, forever. The
     * delay doubles with each consecutive failure so a source having a bad afternoon recovers
     * quickly while a dead one is left alone.
     */
    suspend fun backOff(mangaId: Long, consecutiveFailures: Int, now: Long = System.currentTimeMillis()) {
        val delay = (ReleaseEstimate.MIN_POLL shl consecutiveFailures.coerceIn(0, 8))
            .coerceAtMost(ReleaseEstimate.MAX_INTERVAL)
        runCatching {
            handler.await {
                manga_fetch_stateQueries.delayCheck(manga_id = mangaId, next_check = now + delay)
            }
        }
    }
}

/**
 * Upload and fetch dates with one entry per chapter number: the earliest of each across every
 * row that carries that number. Unnumbered chapters have nothing to collapse against and are
 * kept as they are.
 */
fun releaseDates(chapters: List<Chapter>): Pair<List<Long>, List<Long>> {
    val (numbered, unnumbered) = chapters.partition { it.isRecognizedNumber }
    val perNumber = numbered.groupBy { (it.chapter_number * 1000f).roundToInt() }.values
    val uploads = perNumber.map { group -> group.map { it.date_upload }.filter { it > 0 }.minOrNull() ?: 0L } +
        unnumbered.map { it.date_upload }
    val fetches = perNumber.map { group -> group.minOf { it.date_fetch } } +
        unnumbered.map { it.date_fetch }
    return uploads to fetches
}
