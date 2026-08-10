package karasu.domain.category.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.source.SourceManager
import karasu.domain.category.models.CategoryRule
import karasu.domain.category.models.CategoryTransition
import karasu.domain.category.models.RuleField
import karasu.domain.category.models.RuleInput
import karasu.domain.category.models.evaluate
import karasu.domain.library.LibraryPreferences
import karasu.domain.manga.MangaRepository
import karasu.domain.manga.failures.interactor.UpdateFailures
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.manga.interval.FetchInterval
import karasu.domain.manga.interval.ReleaseEstimate
import karasu.domain.track.interactor.GetTrack
import kotlinx.serialization.json.Json

/**
 * Moves manga between categories according to the rules their categories carry.
 *
 * The library view returns one row per (manga, category) pair, which is exactly the
 * granularity a rule is evaluated at: a manga filed in two rule categories is considered for
 * both. A manga still moves at most once per run — two categories handing it back and forth
 * is an authoring mistake, and stopping after one move keeps that mistake slow and visible
 * instead of turning it into a loop.
 *
 * Categories without a rule are never read from and never written to, so nothing the user
 * organised by hand can be touched by this.
 */
class ApplyCategoryRules(
    private val getCategories: GetCategories,
    private val getLibraryManga: GetLibraryManga,
    private val mangaRepository: MangaRepository,
    private val sourceManager: SourceManager,
    private val extensionManager: ExtensionManager,
    private val updateFailures: UpdateFailures,
    private val libraryPreferences: LibraryPreferences,
    private val getTrack: GetTrack,
    private val trackManager: TrackManager,
    private val fetchInterval: FetchInterval,
) {

    suspend fun await(now: Long = System.currentTimeMillis()) = run(mangaId = null, now = now)

    /**
     * Full pass, but at most once a day.
     *
     * The Library screen runs this on every open purely so time-based rules ("not read in three
     * months") have something that fires them, and those can only come true with the calendar.
     * Everything else that changes a manga already triggers its own pass, so re-scanning the
     * whole library each time the tab is opened bought nothing.
     */
    suspend fun awaitSweep(now: Long = System.currentTimeMillis()) {
        val last = libraryPreferences.lastCategoryRuleSweep()
        if (now - last.get() < SWEEP_INTERVAL_MS) return
        // Recorded only once the pass actually finished. Marking it up front would turn any
        // failure into a whole day without rules running, since nothing else retries this.
        run(mangaId = null, now = now)
        last.set(now)
    }

    /** Same pass restricted to one manga, for reacting to it being read. */
    suspend fun awaitFor(mangaId: Long, now: Long = System.currentTimeMillis()) =
        run(mangaId = mangaId, now = now)

    private suspend fun run(mangaId: Long?, now: Long) {
        val categories = getCategories.await()
        val rules = categories
            .mapNotNull { category -> category.parseRule()?.let { category.id?.toLong() to it } }
            .toMap()
        if (rules.isEmpty()) return
        val existingIds = categories.mapNotNull { it.id?.toLong() }.toSet()

        val rows = getLibraryManga.await()
            .filter { mangaId == null || it.manga.id == mangaId }

        // All of these are one lookup for the whole pass rather than per manga.
        val failures = updateFailures.await()
        val obsoleteSources = obsoleteSources()
        val trackers = trackerSnapshots(rules.values)
        val estimates = releaseEstimates(rules.values)

        val moved = mutableSetOf<Long>()
        rows.forEach { row ->
            val id = row.manga.id ?: return@forEach
            if (id in moved) return@forEach
            val from = row.category.toLong()
            val rule = rules[from] ?: return@forEach

            val input = row.toRuleInput(
                failures = failures[id] ?: 0,
                obsolete = row.manga.source in obsoleteSources,
                tracker = trackers[id],
                estimate = estimates[id],
                now = now,
            )
            val target = rule.evaluate(input, now) ?: return@forEach
            if (target == from) return@forEach
            // A category deleted after the rule was written leaves a dangling target, and
            // moving the manga to nowhere would just lose it. DEFAULT_CATEGORY is not a row,
            // it is what having no category looks like, so it is always a valid target.
            if (target != DEFAULT_CATEGORY && target !in existingIds) return@forEach

            move(id, from = from, to = target)
            moved += id
        }
    }

    private fun obsoleteSources(): Set<Long> = extensionManager.installedExtensionsFlow.value
        .filter { it.isObsolete }
        .flatMap { extension -> extension.sources.map { it.id } }
        .toSet()

    /**
     * Tracker state per manga, but only if some rule actually asks for it.
     *
     * Reading the track table is the one input here that costs a query nobody else needed, so a
     * library whose rules are all about chapter counts keeps paying nothing for this.
     */
    private suspend fun trackerSnapshots(
        rules: Collection<CategoryRule>,
    ): Map<Long, TrackerRuleSnapshot> {
        val usesTracker = rules.any { rule ->
            rule.transitions.any { transition ->
                transition.conditions.any { it.field in TRACKER_FIELDS }
            }
        }
        if (!usesTracker) return emptyMap()

        return getTrack.awaitAll()
            .groupBy { it.manga_id }
            .mapNotNull { (mangaId, tracks) ->
                trackerSnapshot(tracks) { trackManager.getService(it) }?.let { mangaId to it }
            }
            .toMap()
    }

    /**
     * Release estimates per manga, but only if some rule actually asks about the schedule.
     *
     * Same bargain as [trackerSnapshots]: its own table, its own query, and a library whose
     * rules are all about chapter counts never pays for it.
     */
    private suspend fun releaseEstimates(
        rules: Collection<CategoryRule>,
    ): Map<Long, ReleaseEstimate> {
        val usesSchedule = rules.any { rule ->
            rule.transitions.any { transition ->
                transition.conditions.any { it.field in SCHEDULE_FIELDS }
            }
        }
        if (!usesSchedule) return emptyMap()

        return fetchInterval.awaitAll()
    }

    /**
     * How many entries [transitions] would move out of [categoryId] if they ran right now.
     *
     * Writes nothing. This is what the editor shows so a rule can be checked against the real
     * library before it is trusted to rearrange it — the same evaluation [run] does, minus the
     * move, so a rule that previews as 0 really is one that would do nothing.
     */
    suspend fun countMatches(
        categoryId: Long,
        transitions: List<CategoryTransition>,
        now: Long = System.currentTimeMillis(),
    ): Int {
        if (transitions.isEmpty()) return 0
        val rule = CategoryRule(transitions)
        val existingIds = getCategories.await().mapNotNull { it.id?.toLong() }.toSet()
        val failures = updateFailures.await()
        val obsolete = obsoleteSources()
        val trackers = trackerSnapshots(listOf(rule))
        val estimates = releaseEstimates(listOf(rule))

        return getLibraryManga.await().count { row ->
            val id = row.manga.id ?: return@count false
            if (row.category.toLong() != categoryId) return@count false
            val input = row.toRuleInput(
                failures = failures[id] ?: 0,
                obsolete = row.manga.source in obsolete,
                tracker = trackers[id],
                estimate = estimates[id],
                now = now,
            )
            val target = rule.evaluate(input, now) ?: return@count false
            target != categoryId && (target == DEFAULT_CATEGORY || target in existingIds)
        }
    }

    private suspend fun move(mangaId: Long, from: Long, to: Long) {
        val current = getCategories.awaitByMangaId(mangaId).mapNotNull { it.id?.toLong() }
        val updated = (current - from).let { if (to == DEFAULT_CATEGORY) it else it + to }
        mangaRepository.setCategories(mangaId, updated.distinct())
    }

    private fun LibraryManga.toRuleInput(
        failures: Int,
        obsolete: Boolean,
        tracker: TrackerRuleSnapshot?,
        estimate: ReleaseEstimate?,
        now: Long,
    ) = RuleInput(
        unread = unread,
        read = read,
        totalChapters = totalChapters,
        lastRead = lastRead,
        latestUpdate = latestUpdate,
        status = manga.status,
        sourceMissing = sourceManager.get(manga.source) == null,
        sourceObsolete = obsolete,
        updateFailures = failures,
        trackerStatus = tracker?.status,
        trackerScore = tracker?.score,
        nextRelease = estimate?.nextRelease,
        releaseStalled = estimate?.isStalled(now),
    )

    private fun Category.parseRule(): CategoryRule? {
        val raw = rule?.takeIf { it.isNotBlank() } ?: return null
        return try {
            json.decodeFromString<CategoryRule>(raw).takeIf { it.transitions.isNotEmpty() }
        } catch (e: Exception) {
            Logger.e(e) { "Ignoring unreadable rule on category $id" }
            null
        }
    }

    private companion object {
        /** Not a row: a manga with no category shows up under the default header. */
        const val DEFAULT_CATEGORY = 0L

        const val SWEEP_INTERVAL_MS = 24 * 60 * 60 * 1000L

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

        val json = Json { ignoreUnknownKeys = true }
    }
}
