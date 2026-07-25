package karasu.domain.manga.failures.models

/** A manga whose updates keep failing, with enough detail to explain why on screen. */
data class MangaUpdateFailure(
    val mangaId: Long,
    val failures: Int,
    /** The last error the source produced, null when it gave none. */
    val lastMessage: String?,
    /** Epoch millis of the most recent failed attempt. */
    val lastAttempt: Long,
)
