package karasu.domain.manga.interval

import karasu.data.DatabaseHandler

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
        val estimate = ReleaseEstimate.estimate(uploadDates, fetchDates, now) ?: return false
        return runCatching {
            handler.await {
                manga_fetch_stateQueries.upsert(
                    manga_id = mangaId,
                    next_check = estimate.nextCheck(now),
                    next_release = estimate.nextRelease,
                    interval = estimate.interval,
                    spread = estimate.spread,
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
