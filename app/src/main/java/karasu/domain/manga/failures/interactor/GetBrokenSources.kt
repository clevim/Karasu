package karasu.domain.manga.failures.interactor

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.SourceManager
import karasu.domain.manga.failures.FailureCause
import karasu.domain.manga.failures.MangaUpdateFailureRepository
import karasu.domain.manga.failures.ReadFailures
import karasu.domain.manga.failures.causeOf
import karasu.domain.manga.interactor.GetLibraryManga

/**
 * Why a source counts as broken, worst first.
 *
 * The order is the order the screen shows them in, and it is deliberate: a missing extension is
 * already unusable, an obsolete one will become unusable, and a failing one might just be a
 * website having a bad week.
 */
enum class BreakageKind {
    /** The extension is gone, so nothing can be fetched at all. */
    SOURCE_MISSING,

    /** Installed but no longer offered by its repository, so it will stop working. */
    SOURCE_OBSOLETE,

    /** Still installed and offered, but its updates keep failing. */
    FAILING,

    /**
     * Failed to serve pages while someone was reading, just now.
     *
     * Last because it is the least final — a source can fail one chapter and be fine on the next
     * — but it is the only kind a library update would never have found, since updates only ever
     * ask for chapter lists.
     */
    READ_FAILING,
}

data class BrokenEntry(
    val mangaId: Long,
    val title: String,
    val failures: Int,
    val lastMessage: String?,
    val lastAttempt: Long,
)

data class BrokenSource(
    val sourceId: Long,
    val sourceName: String,
    val kind: BreakageKind,
    val entries: List<BrokenEntry>,
) {
    /**
     * What the failures look like, taken from the most recent one that said anything.
     *
     * Drives which recovery the screen offers. A source that is refusing requests and a source
     * whose extension can no longer parse it both fail every update, but migrating away from the
     * first one throws away a library over what is often a bad afternoon.
     */
    val cause: FailureCause
        get() = entries.asSequence()
            .sortedByDescending { it.lastAttempt }
            .mapNotNull { it.lastMessage?.takeIf(String::isNotBlank) }
            .firstOrNull()
            ?.let(::causeOf)
            ?: FailureCause.UNKNOWN
}

/**
 * The library grouped by the source that is letting it down.
 *
 * All three signals already exist — the extension list knows what is missing or obsolete, and
 * the failure table has been counting since the category rules shipped — but until now they
 * only surfaced indirectly, as a rule quietly moving something. This is the same data made
 * answerable: what stopped working, and what do I migrate.
 */
class GetBrokenSources(
    private val getLibraryManga: GetLibraryManga,
    private val failureRepository: MangaUpdateFailureRepository,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val readFailures: ReadFailures,
) {

    suspend fun await(minimumFailures: Int = DEFAULT_MINIMUM_FAILURES): List<BrokenSource> {
        val failures = runCatching { failureRepository.getAllDetailed() }
            .getOrDefault(emptyList())
            .associateBy { it.mangaId }

        val obsoleteSources = extensionManager.installedExtensionsFlow.value
            .filter { it.isObsolete }
            .flatMap { extension -> extension.sources.map { it.id } }
            .toSet()

        // One row per (manga, category) comes back, and a manga filed twice is still one manga.
        return getLibraryManga.await()
            .distinctBy { it.manga.id }
            .mapNotNull { libraryManga ->
                val mangaId = libraryManga.manga.id ?: return@mapNotNull null
                val sourceId = libraryManga.manga.source
                val failure = failures[mangaId]

                val kind = when {
                    sourceManager.get(sourceId) == null -> BreakageKind.SOURCE_MISSING
                    sourceId in obsoleteSources -> BreakageKind.SOURCE_OBSOLETE
                    (failure?.failures ?: 0) >= minimumFailures -> BreakageKind.FAILING
                    else -> return@mapNotNull null
                }

                Triple(
                    sourceId,
                    kind,
                    BrokenEntry(
                        mangaId = mangaId,
                        title = libraryManga.manga.title,
                        failures = failure?.failures ?: 0,
                        lastMessage = failure?.lastMessage,
                        lastAttempt = failure?.lastAttempt ?: 0L,
                    ),
                )
            }
            .plus(readFailureRows())
            .groupBy { (sourceId, kind, _) -> sourceId to kind }
            .map { (key, group) ->
                val (sourceId, kind) = key
                BrokenSource(
                    sourceId = sourceId,
                    sourceName = sourceManager.getOrStub(sourceId).name,
                    kind = kind,
                    entries = group.map { it.third }.sortedBy { it.title },
                )
            }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.sourceName }))
    }

    /**
     * Sources that failed to serve a page recently, as their own rows.
     *
     * Not folded into the library pass above, because these are keyed by the source that actually
     * failed rather than the source a library entry sits on. A merged source has its own manga row
     * and usually isn't in the library at all, so going through the library would drop exactly the
     * failures this is here to surface. A single failure counts: unlike an update, someone was
     * waiting for it.
     */
    private fun readFailureRows(): List<Triple<Long, BreakageKind, BrokenEntry>> =
        readFailures.recent().map { failure ->
            Triple(
                failure.sourceId,
                BreakageKind.READ_FAILING,
                BrokenEntry(
                    mangaId = failure.mangaId,
                    title = failure.mangaTitle,
                    failures = 1,
                    lastMessage = failure.message,
                    lastAttempt = failure.at,
                ),
            )
        }

    companion object {
        /**
         * One failure is noise — a timeout, a site restarting. Two in a row is a pattern, and
         * matches what the category rules treat as meaningful.
         */
        const val DEFAULT_MINIMUM_FAILURES = 2
    }
}
