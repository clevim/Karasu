package karasu.domain.manga.failures.interactor

import karasu.domain.manga.failures.MangaUpdateFailureRepository
import karasu.domain.manga.failures.shouldCountUpdateFailure

/**
 * Tracks how many updates in a row a manga's source has failed.
 *
 * Every call is best-effort: this exists to feed a category rule, so it must never be the
 * reason a library update fails.
 */
class UpdateFailures(
    private val repository: MangaUpdateFailureRepository,
) {
    suspend fun await(): Map<Long, Int> = runCatching { repository.getAll() }.getOrDefault(emptyMap())

    /**
     * Records [error] against [mangaId] if it is the source's fault.
     *
     * [online] is the device's own connectivity: with no connection every manga fails at
     * once, and none of it says anything about the sources.
     */
    suspend fun record(mangaId: Long, error: Throwable, online: Boolean) {
        if (!online || !shouldCountUpdateFailure(error)) return
        runCatching { repository.record(mangaId, error.message, System.currentTimeMillis()) }
    }

    /** Called whenever an update succeeds, which is what makes the count consecutive. */
    suspend fun clear(mangaId: Long) {
        runCatching { repository.clear(mangaId) }
    }
}
