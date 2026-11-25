package eu.kanade.tachiyomi.data.koreader

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.util.system.workManager
import java.util.concurrent.TimeUnit
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.koreader.interactor.SyncKoreaderShelf
import karasu.domain.koreader.interactor.SyncYatagarasuLibrary
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Periodic and manual reconcile of the KOReader shelf and Yatagarasu companion library sync.
 *
 * Deliberately not a foreground worker. A first sync of many chapters can outlive WorkManager's
 * execution window, but the run is a reconcile — whatever reached the shelf stays there, and the
 * retry uploads only what is still missing — so being stopped costs time, not correctness, and
 * that is cheaper than a permanent notification channel for a background file copy.
 */
class KoreaderSyncJob(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<KoreaderPreferences>()
        if (!isConfigured(preferences)) return Result.success()

        val syncLibEnabled = preferences.syncLibrary().get()
        var shelfResult: SyncKoreaderShelf.Summary? = null
        var shelfError: Exception? = null
        var libResult: SyncYatagarasuLibrary.Summary? = null
        var libError: Exception? = null

        try {
            val res = Injekt.get<SyncKoreaderShelf>().await()
            Logger.i {
                "KOReader shelf synced: ${res.uploaded} uploaded, ${res.removed} removed, " +
                    "${res.markedRead} marked read, ${res.queuedForDownload} queued"
            }
            shelfResult = res
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e) { "KOReader shelf sync failed" }
            shelfError = e
        }

        if (syncLibEnabled) {
            try {
                val res = Injekt.get<SyncYatagarasuLibrary>().await()
                val libSummary = describeLibrary(res)
                preferences.lastLibrarySyncSummary().set(libSummary)
                Logger.i {
                    "Yatagarasu library synced: $libSummary " +
                        "(missing extensions: ${res.missingExtensions}, total: ${res.totalLibraryManga})"
                }
                libResult = res
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e) { "Yatagarasu library sync failed" }
                libError = e
            }
        }

        if (shelfResult == null && (!syncLibEnabled || libResult == null)) {
            val primaryError = libError ?: shelfError
            record(preferences, describeFailure(primaryError))
            return if (runAttemptCount < MAX_RETRY_ATTEMPTS) Result.retry() else Result.failure()
        }

        val summary = buildCombinedSummary(
            shelfResult = shelfResult,
            shelfError = shelfError,
            syncLibEnabled = syncLibEnabled,
            libResult = libResult,
            libError = libError,
        )
        record(preferences, summary)
        return Result.success()
    }

    private fun record(preferences: KoreaderPreferences, summary: String) {
        preferences.lastSyncSummary().set(summary)
        preferences.lastSyncAt().set(System.currentTimeMillis())
    }

    private fun describeFailure(error: Exception?): String = applicationContext.getString(
        MR.strings.koreader_sync_failed_summary,
        error?.message ?: error?.let { it::class.simpleName }.orEmpty(),
    )

    private fun isShelfIdle(result: SyncKoreaderShelf.Summary): Boolean =
        result.uploaded == 0 && result.removed == 0 &&
            result.markedRead == 0 && result.queuedForDownload == 0

    private fun describeShelfCounts(result: SyncKoreaderShelf.Summary): String =
        applicationContext.getString(
            MR.strings.koreader_sync_counts,
            result.uploaded,
            result.removed,
            result.markedRead,
            result.queuedForDownload,
        )

    private fun describeLibrary(result: SyncYatagarasuLibrary.Summary): String {
        val deltas = buildList {
            if (result.mangaAdded > 0) {
                add(applicationContext.getString(MR.strings.yata_delta_manga_added, result.mangaAdded))
            }
            if (result.mangaRemoved > 0) {
                add(applicationContext.getString(MR.strings.yata_delta_manga_removed, result.mangaRemoved))
            }
            if (result.chaptersUpdated > 0) {
                add(applicationContext.getString(MR.strings.yata_delta_chapters, result.chaptersUpdated))
            }
            if (result.mergedAdded > 0) {
                add(applicationContext.getString(MR.strings.yata_delta_merged, result.mergedAdded))
            }
            if (result.reposAdded > 0) {
                add(applicationContext.getString(MR.strings.yata_delta_repos, result.reposAdded))
            }
        }
        return if (deltas.isNotEmpty()) {
            deltas.joinToString(", ")
        } else {
            applicationContext.getString(MR.strings.yata_library_up_to_date, result.totalLibraryManga)
        }
    }

    private fun buildCombinedSummary(
        shelfResult: SyncKoreaderShelf.Summary?,
        shelfError: Exception?,
        syncLibEnabled: Boolean,
        libResult: SyncYatagarasuLibrary.Summary?,
        libError: Exception?,
    ): String {
        val shelfPart = when {
            shelfResult != null && !isShelfIdle(shelfResult) -> describeShelfCounts(shelfResult)
            shelfError != null -> describeFailure(shelfError)
            else -> null
        }
        val libPart = when {
            !syncLibEnabled -> null
            libResult != null -> describeLibrary(libResult)
            libError != null -> describeFailure(libError)
            else -> null
        }
        val parts = listOfNotNull(shelfPart, libPart)
        return if (parts.isNotEmpty()) {
            parts.joinToString(" · ")
        } else {
            applicationContext.getString(MR.strings.koreader_sync_nothing_to_do)
        }
    }

    companion object {
        private fun isConfigured(preferences: KoreaderPreferences): Boolean =
            preferences.serverUrl().get().isNotBlank() || preferences.fallbackServerUrl().get().isNotBlank()

        fun isRunning(context: Context): Boolean {
            val wm = context.workManager
            val manualRunning = wm.getWorkInfosForUniqueWork(TAG_MANUAL).get()
                .any { it.state == WorkInfo.State.RUNNING }
            val autoRunning = wm.getWorkInfosForUniqueWork(TAG_AUTO).get()
                .any { it.state == WorkInfo.State.RUNNING }
            return manualRunning || autoRunning
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            val wm = context.workManager
            return combine(
                wm.getWorkInfosForUniqueWorkFlow(TAG_MANUAL),
                wm.getWorkInfosForUniqueWorkFlow(TAG_AUTO),
            ) { manual, auto ->
                manual.any { it.state == WorkInfo.State.RUNNING } ||
                    auto.any { it.state == WorkInfo.State.RUNNING }
            }.distinctUntilChanged()
        }

        fun setupTask(
            context: Context,
            prefInterval: Int? = null,
            prefOnlyOverWifi: Boolean? = null,
            prefConfigured: Boolean? = null,
        ) {
            val preferences = Injekt.get<KoreaderPreferences>()
            val interval = prefInterval ?: preferences.syncInterval().get()
            val configured = prefConfigured ?: isConfigured(preferences)
            if (interval <= 0 || !configured) {
                context.workManager.cancelUniqueWork(TAG_AUTO)
                return
            }

            val request = PeriodicWorkRequestBuilder<KoreaderSyncJob>(
                interval.toLong(),
                TimeUnit.HOURS,
                10,
                TimeUnit.MINUTES,
            )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .addTag(TAG_AUTO)
                .setConstraints(constraints(preferences, prefOnlyOverWifi))
                .build()

            context.workManager.enqueueUniquePeriodicWork(
                TAG_AUTO,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Sync because something happened that the shelf or library depends on: chapters finished
         * downloading, the library found new ones, the reader closed, or sync settings were edited.
         *
         * Respects [KoreaderPreferences.onlyOverWifi] and uses [ExistingWorkPolicy.KEEP] so automatic
         * event triggers never use mobile data when Wi-Fi-only is enabled and never interrupt an
         * already running sync. Silent when the interval is Manual.
         */
        fun startIfAutomatic(context: Context) {
            val preferences = Injekt.get<KoreaderPreferences>()
            if (preferences.syncInterval().get() <= 0 || !isConfigured(preferences)) return
            val request = OneTimeWorkRequestBuilder<KoreaderSyncJob>()
                .addTag(TAG_MANUAL)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
                .setConstraints(constraints(preferences))
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        /**
         * Explicit manual sync requested by the user ("Sync now"): runs immediately over any active
         * network (Wi-Fi or mobile data) and replaces any stuck/waiting work.
         */
        fun startNow(context: Context) {
            if (!isConfigured(Injekt.get())) return
            val request = OneTimeWorkRequestBuilder<KoreaderSyncJob>()
                .addTag(TAG_MANUAL)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.REPLACE, request)
        }

        private fun constraints(
            preferences: KoreaderPreferences,
            onlyOverWifi: Boolean? = null,
        ) = Constraints(
            requiredNetworkType = if (onlyOverWifi ?: preferences.onlyOverWifi().get()) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            },
        )

        private const val MAX_RETRY_ATTEMPTS = 3
        private const val TAG_AUTO = "KoreaderSync"
        private const val TAG_MANUAL = "$TAG_AUTO:manual"
    }
}
