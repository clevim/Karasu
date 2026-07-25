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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Periodic reconcile of the KOReader shelf.
 *
 * Uploads are whole CBZ files and the shelf is normally on the same LAN, so a failed run is
 * retried with backoff rather than reported: there is nothing the user can do about the tablet
 * being off, and the next run picks up exactly where this one stopped.
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
        if (preferences.serverUrl().get().isBlank()) return Result.success()

        return try {
            val result = Injekt.get<SyncKoreaderShelf>().await()
            Logger.i {
                "KOReader shelf synced: ${result.uploaded} uploaded, ${result.removed} removed, " +
                    "${result.markedRead} marked read, ${result.queuedForDownload} queued"
            }
            Result.success()
        } catch (e: Exception) {
            Logger.e(e) { "KOReader shelf sync failed" }
            Result.retry()
        }
    }

    companion object {
        fun isRunning(context: Context): Boolean = context.workManager
            .getWorkInfosByTag(TAG_MANUAL).get()
            .any { it.state == WorkInfo.State.RUNNING }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = Injekt.get<KoreaderPreferences>()
            val interval = prefInterval ?: preferences.syncInterval().get()
            if (interval <= 0 || preferences.serverUrl().get().isBlank()) {
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
                .setConstraints(constraints(preferences))
                .build()

            context.workManager.enqueueUniquePeriodicWork(
                TAG_AUTO,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** Manual sync ignores the interval but still respects the metered-connection choice. */
        fun startNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<KoreaderSyncJob>()
                .addTag(TAG_MANUAL)
                .setConstraints(constraints(Injekt.get()))
                .build()
            context.workManager.enqueueUniqueWork(TAG_MANUAL, ExistingWorkPolicy.KEEP, request)
        }

        private fun constraints(preferences: KoreaderPreferences) = Constraints(
            requiredNetworkType = if (preferences.onlyOverWifi().get()) {
                NetworkType.UNMETERED
            } else {
                NetworkType.CONNECTED
            },
        )

        private const val TAG_AUTO = "KoreaderSync"
        private const val TAG_MANUAL = "$TAG_AUTO:manual"
    }
}
