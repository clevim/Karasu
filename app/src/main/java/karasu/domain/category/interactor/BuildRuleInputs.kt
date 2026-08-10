package karasu.domain.category.interactor

import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.SourceManager
import karasu.domain.category.models.RuleCondition
import karasu.domain.category.models.RuleField
import karasu.domain.category.models.RuleInput
import karasu.domain.manga.failures.interactor.UpdateFailures
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.manga.interval.FetchInterval
import karasu.domain.track.interactor.GetTrack

/**
 * Turns the library into the snapshots a rule is evaluated against.
 *
 * Every input here is fetched once for the whole pass rather than per manga, which is what lets
 * a rule graph be evaluated over a large library without a query per entry. Anything that costs
 * its own read — currently only the track table — is skipped unless some condition asks for it.
 */
class BuildRuleInputs(
    private val getLibraryManga: GetLibraryManga,
    private val updateFailures: UpdateFailures,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val getTrack: GetTrack,
    private val trackManager: TrackManager,
    private val fetchInterval: FetchInterval,
) {

    /**
     * @param conditions every condition that will be evaluated, used only to decide which
     *   optional inputs are worth loading.
     */
    suspend fun await(
        conditions: Collection<RuleCondition>,
        now: Long = System.currentTimeMillis(),
    ): Map<Long, RuleInput> {
        val failures = updateFailures.await()
        val obsoleteSources = extensionManager.installedExtensionsFlow.value
            .filter { it.isObsolete }
            .flatMap { extension -> extension.sources.map { it.id } }
            .toSet()
        val trackers = trackerSnapshots(conditions)
        val estimates = if (conditions.any { it.field in SCHEDULE_FIELDS }) {
            fetchInterval.awaitAll()
        } else {
            emptyMap()
        }

        return getLibraryManga.await()
            .distinctBy { it.manga.id }
            .mapNotNull { row ->
                val id = row.manga.id ?: return@mapNotNull null
                val tracker = trackers[id]
                val estimate = estimates[id]
                id to RuleInput(
                    unread = row.unread,
                    read = row.read,
                    totalChapters = row.totalChapters,
                    lastRead = row.lastRead,
                    latestUpdate = row.latestUpdate,
                    status = row.manga.status,
                    sourceMissing = sourceManager.get(row.manga.source) == null,
                    sourceObsolete = row.manga.source in obsoleteSources,
                    updateFailures = failures[id] ?: 0,
                    trackerStatus = tracker?.status,
                    trackerScore = tracker?.score,
                    nextRelease = estimate?.nextRelease,
                    releaseStalled = estimate?.isStalled(now),
                )
            }
            .toMap()
    }

    private suspend fun trackerSnapshots(
        conditions: Collection<RuleCondition>,
    ): Map<Long, TrackerRuleSnapshot> {
        if (conditions.none { it.field in TRACKER_FIELDS }) return emptyMap()

        return getTrack.awaitAll()
            .groupBy { it.manga_id }
            .mapNotNull { (mangaId, tracks) ->
                trackerSnapshot(tracks) { trackManager.getService(it) }?.let { mangaId to it }
            }
            .toMap()
    }

    companion object {
        /** The fields whose answers require reading the track table. */
        val TRACKER_FIELDS = setOf(
            RuleField.TRACKED,
            RuleField.TRACKER_STATUS,
            RuleField.TRACKER_SCORE,
        )

        /** The fields whose answers require reading the release estimates. */
        val SCHEDULE_FIELDS = setOf(
            RuleField.DAYS_UNTIL_RELEASE,
            RuleField.RELEASE_STALLED,
        )
    }
}
