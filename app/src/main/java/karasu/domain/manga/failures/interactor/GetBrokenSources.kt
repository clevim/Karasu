package karasu.domain.manga.failures.interactor

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.SourceManager
import karasu.domain.manga.failures.MangaUpdateFailureRepository
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
)

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

    companion object {
        /**
         * One failure is noise — a timeout, a site restarting. Two in a row is a pattern, and
         * matches what the category rules treat as meaningful.
         */
        const val DEFAULT_MINIMUM_FAILURES = 2
    }
}
