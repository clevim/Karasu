package eu.kanade.tachiyomi.data.migration

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.smartsearch.SmartSearchEngine
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.migration.manga.process.MigrationProcessAdapter
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import karasu.domain.manga.failures.interactor.BreakageKind
import karasu.domain.manga.failures.interactor.GetBrokenSources
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.migration.FindMigrationTarget
import karasu.domain.migration.MangaAliases
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Finds somewhere else to read the entries whose source has gone away, once a day.
 *
 * The pass is unattended, but the *migration* is not unconditional. Moving an entry rewrites its
 * read history, bookmarks, categories and tracking onto the target and there is no undo, so only
 * a match the app is genuinely sure of is applied here; anything weaker is parked for review and
 * the notification says how many are waiting. That split is the whole reason this can run while
 * nobody is watching — the hand search was made the whole flow in the first place precisely
 * because a middling guess is not good enough to act on.
 *
 * The expensive half — searching every candidate source under every name the series goes by — is
 * what the job is really for, and it is done for the parked entries too, so review is one tap
 * rather than a search.
 */
class AutoMigrateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val preferences: PreferencesHelper = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val getBrokenSources: GetBrokenSources = Injekt.get()
    private val getManga: GetManga = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()

    override suspend fun doWork(): Result {
        if (preferences.autoMigrateHour().get() < 0) return Result.success()

        val languages = listOf(
            preferences.autoMigratePrimaryLang().get(),
            preferences.autoMigrateSecondaryLang().get(),
        ).filter { it.isNotBlank() }.distinct()
        if (languages.isEmpty()) return Result.success()

        val stranded = runCatching { getBrokenSources.await() }.getOrDefault(emptyList())
            .filter { it.kind in MIGRATABLE }
            .flatMap { broken -> broken.entries.map { it.mangaId } }
            .distinct()
        if (stranded.isEmpty()) return Result.success()

        val searchEngine = SmartSearchEngine(Job() + Dispatchers.IO)
        val finder = FindMigrationTarget(
            aliases = MangaAliases(Injekt.get(), Injekt.get()),
            searchEngine = searchEngine,
        )

        val migrated = mutableListOf<String>()
        val parked = mutableMapOf<Long, Long>()

        for (mangaId in stranded) {
            currentCoroutineContext().ensureActive()
            val manga = getManga.awaitById(mangaId) ?: continue
            // Never offer a source the entry is already on as a replacement for itself.
            val targets = candidateSources(languages, manga.source)
            val found = try {
                finder.await(manga, targets)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Auto migration search failed for ${manga.title}" }
                continue
            } ?: continue

            val local = try {
                materialize(found.source, searchEngine, found.sManga)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Could not pull ${found.sManga.title} from ${found.source.name}" }
                continue
            } ?: continue

            if (found.confident) {
                migrate(manga, local)
                migrated += manga.title
            } else {
                parked[mangaId] = local.id ?: continue
            }
        }

        preferences.autoMigratePending().set(parked.entries.joinToString(",") { "${it.key}:${it.value}" })
        if (migrated.isNotEmpty() || parked.isNotEmpty()) {
            notify(migrated, parked.size)
        }
        return Result.success()
    }

    /**
     * Installed sources in the chosen languages, best language first.
     *
     * The order carries the preference: [FindMigrationTarget] stops at the first source it is
     * confident about, so listing pt-BR before English is what makes pt-BR win a tie.
     */
    private fun candidateSources(languages: List<String>, exclude: Long): List<CatalogueSource> {
        val installed = sourceManager.getCatalogueSources().filter { it.id != exclude }
        return languages.flatMap { lang -> installed.filter { it.lang.equals(lang, ignoreCase = true) } }
    }

    /**
     * Creates the local row for the target and fills it with the source's details and chapters.
     *
     * Without the chapter list there is nothing for the migration to carry read state onto, so a
     * target that yields no chapters is treated as not found rather than migrated to empty.
     */
    private suspend fun materialize(
        source: CatalogueSource,
        searchEngine: SmartSearchEngine,
        sManga: SManga,
    ): Manga? {
        val local = searchEngine.networkToLocalManga(sManga, source.id)
        val update = source.getMangaUpdate(
            manga = local,
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true,
        )
        if (update.chapters.isEmpty()) return null
        local.copyFrom(update.manga)
        updateManga.await(local.toMangaUpdate())
        syncChaptersWithSource(update.chapters, local, source)
        return local
    }

    /** The same move the review screen makes, with the same flags. */
    private suspend fun migrate(from: Manga, to: Manga) {
        MigrationProcessAdapter.migrateMangaInternal(
            flags = preferences.migrateFlags().get(),
            enhancedServices = Injekt.get<TrackManager>().services.filterIsInstance<EnhancedTrackService>(),
            coverCache = Injekt.get<CoverCache>(),
            customMangaManager = Injekt.get<CustomMangaManager>(),
            prevSource = sourceManager.get(from.source),
            source = sourceManager.get(to.source) ?: return,
            prevManga = from,
            manga = to,
            replace = true,
        )
    }

    private fun notify(migrated: List<String>, parkedCount: Int) {
        val localeContext = context.localeContext
        val review = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.SHORTCUT_MIGRATE_REVIEW)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when {
            migrated.isEmpty() ->
                localeContext.getString(MR.plurals.auto_migration_pending, parkedCount, parkedCount)
            else ->
                localeContext.getString(MR.plurals.auto_migration_done, migrated.size, migrated.size)
        }
        val body = buildList {
            addAll(migrated)
            if (parkedCount > 0 && migrated.isNotEmpty()) {
                add(localeContext.getString(MR.plurals.auto_migration_pending, parkedCount, parkedCount))
            }
        }

        val notification = localeContext.notificationBuilder(Notifications.CHANNEL_AUTO_MIGRATE) {
            setSmallIcon(R.drawable.ic_karasu)
            setContentTitle(title)
            setContentText(body.joinToString(", "))
            setStyle(NotificationCompat.BigTextStyle().bigText(body.joinToString("\n")))
            // Only useful when there is something to look at; otherwise the report is the whole
            // message and tapping it should not open a screen that says "nothing here".
            if (parkedCount > 0) setContentIntent(review)
            setAutoCancel(true)
        }.build()

        context.notificationManager.notify(Notifications.ID_AUTO_MIGRATE, notification)
    }

    companion object {
        private const val TAG = "AutoMigrate"
        private const val WORK_NAME = "AutoMigrate"

        /** Sources that are gone or on their way out. A merely failing source is having a bad day. */
        private val MIGRATABLE = setOf(BreakageKind.SOURCE_MISSING, BreakageKind.SOURCE_OBSOLETE)

        /** The parked targets, as the review screen wants them: entry id to candidate id. */
        fun pendingReview(preferences: PreferencesHelper): Map<Long, Long> =
            parsePending(preferences.autoMigratePending().get())

        /**
         * Anything that is not a clean `from:to` pair is dropped rather than throwing.
         *
         * This is a preference, so it can be stale, hand-edited or left over from an older build,
         * and none of those are worth crashing a review screen over.
         */
        @VisibleForTesting
        fun parsePending(stored: String): Map<Long, Long> = stored
            .split(',')
            .mapNotNull { pair ->
                val (from, to) = pair.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                val fromId = from.toLongOrNull() ?: return@mapNotNull null
                val toId = to.toLongOrNull() ?: return@mapNotNull null
                fromId to toId
            }
            .toMap()

        fun clearPendingReview(preferences: PreferencesHelper) =
            preferences.autoMigratePending().set("")

        fun setupTask(context: Context) {
            val preferences = Injekt.get<PreferencesHelper>()
            val hour = preferences.autoMigrateHour().get()
            val day = preferences.autoMigrateDay().get()
            val wm = WorkManager.getInstance(context)

            if (hour < 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }

            val repeatDays = if (day in 1..7) 7L else 1L
            val request = PeriodicWorkRequestBuilder<AutoMigrateJob>(repeatDays, TimeUnit.DAYS)
                .setInitialDelay(millisUntil(day, hour), TimeUnit.MILLISECONDS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .addTag(TAG)
                .build()

            // Replaced rather than updated, for the same reason as the digest: this job is about
            // *when* it fires, and UPDATE would keep the old day and hour.
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request,
            )
        }

        /** [autoMigrateDay] value meaning "no particular day". */
        const val DAILY = -1

        /**
         * How long until the next [hour] o'clock on [day], or on any day when [day] is [DAILY].
         *
         * Done on [ZonedDateTime] rather than in milliseconds so a week that contains a clock
         * change is still a week: adding a fixed 7 × 24 hours would walk the run an hour off
         * twice a year and never walk it back. Landing exactly on the hour counts as passed, so
         * rescheduling at the moment the job fires books the next slot, not the same one again.
         */
        @VisibleForTesting
        fun millisUntil(day: Int, hour: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
            val todayAt = now.truncatedTo(ChronoUnit.DAYS).withHour(hour)
            var next = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
            if (day in 1..7) {
                // At most six hops: a weekday is always within a week of any other.
                while (next.dayOfWeek.value != day) next = next.plusDays(1)
            }
            return Duration.between(now, next).toMillis()
        }
    }
}
