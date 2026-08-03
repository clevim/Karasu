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
 * Two things read this. The merged-source fallback uses it to stop paying a broken source's
 * timeout on every chapter open, and the broken-sources screen uses it to say that a source is
 * failing to serve pages, which no library update would ever have noticed.
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

    fun record(mangaId: Long, sourceId: Long, mangaTitle: String, message: String?) {
        failures[mangaId to sourceId] = ReadFailure(
            mangaId = mangaId,
            sourceId = sourceId,
            mangaTitle = mangaTitle,
            message = message,
            at = System.currentTimeMillis(),
        )
    }

    /** Called when a source serves a chapter, which is the only thing that clears its record. */
    fun clear(mangaId: Long, sourceId: Long) {
        failures.remove(mangaId to sourceId)
    }

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
