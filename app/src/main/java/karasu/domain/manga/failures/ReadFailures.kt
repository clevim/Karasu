package karasu.domain.manga.failures

import java.util.concurrent.ConcurrentHashMap

/**
 * Sources that failed while the user was reading, as opposed to during a library update.
 *
 * These are a different animal from the ones in [MangaUpdateFailureRepository]. An update failure
 * is a slow accumulation — the counter needs several in a row before it means anything, and it is
 * persisted because the pattern is the point. A read failure is immediate and specific: this
 * source, this manga, just now, while someone was waiting for a page. It is worth acting on the
 * first time it happens and worthless a day later, so it lives in memory and expires on its own.
 *
 * Three things read this. The merged-source fallback uses it to stop paying a broken source's
 * timeout on every chapter open, the broken-sources screen uses it to say that a source is failing
 * to serve pages — which no library update would ever have noticed — and global search uses it to
 * put the sources that are down at the back of the queue.
 */
class ReadFailures {

    /** One source failing one manga, with enough to explain it on screen. */
    data class ReadFailure(
        val mangaId: Long,
        val sourceId: Long,
        val mangaTitle: String,
        val message: String?,
        val at: Long,
    )

    // ponytail: never pruned on a timer. One entry per merged manga you actually failed to read
    // this session, so it stays tiny; give it a size cap if that ever stops being true.
    private val failures = ConcurrentHashMap<Pair<Long, Long>, ReadFailure>()

    /** Source id to when it last failed at anything, for the "is this source up" question. */
    private val sourceFailures = ConcurrentHashMap<Long, Long>()

    fun record(mangaId: Long, sourceId: Long, mangaTitle: String, message: String?) {
        failures[mangaId to sourceId] = ReadFailure(
            mangaId = mangaId,
            sourceId = sourceId,
            mangaTitle = mangaTitle,
            message = message,
            at = System.currentTimeMillis(),
        )
        recordSource(sourceId)
    }

    /** Called when a source serves a chapter, which is the only thing that clears its record. */
    fun clear(mangaId: Long, sourceId: Long) {
        failures.remove(mangaId to sourceId)
        clearSource(sourceId)
    }

    /**
     * A source that failed with no particular manga behind it — a search that timed out, say.
     *
     * Kept apart from the per-manga records above because the two answer different questions. That
     * a source can't serve *this* manga says nothing about the rest of it: the entry may simply be
     * gone. That a source is down says nothing about which manga you asked it for.
     */
    fun recordSource(sourceId: Long) {
        sourceFailures[sourceId] = System.currentTimeMillis()
    }

    fun clearSource(sourceId: Long) {
        sourceFailures.remove(sourceId)
    }

    /** Whether [sourceId] failed recently enough that it isn't worth going to first. */
    fun isSourceFailing(sourceId: Long): Boolean =
        sourceFailures[sourceId]?.let { System.currentTimeMillis() - it < COOLDOWN_MS } == true

    /**
     * Whether [sourceId] failed [mangaId] recently enough to still be worth avoiding.
     *
     * Long enough to cover a reading session so a binge doesn't re-time-out on every chapter,
     * short enough that a source which comes back up is picked up again without a restart.
     */
    fun isRecent(mangaId: Long, sourceId: Long): Boolean =
        failures[mangaId to sourceId]?.let { System.currentTimeMillis() - it.at < COOLDOWN_MS } == true

    /** Everything still inside the cooldown, for the broken-sources screen. */
    fun recent(): List<ReadFailure> {
        val cutoff = System.currentTimeMillis() - COOLDOWN_MS
        return failures.values.filter { it.at >= cutoff }
    }

    private companion object {
        const val COOLDOWN_MS = 10 * 60 * 1000L
    }
}
