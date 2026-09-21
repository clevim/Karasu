package eu.kanade.tachiyomi.data.recommendation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import android.app.Notification
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.ForegroundInfo
import kotlinx.coroutines.delay
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.migration.AutoMigrateJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import java.util.concurrent.TimeUnit
import karasu.domain.recommendation.BuildProgress
import karasu.domain.recommendation.BuildRecommendations
import karasu.domain.recommendation.DiscardRecommendations
import karasu.domain.recommendation.EnrichFromTrackers
import karasu.domain.recommendation.FindMergeCandidates
import karasu.domain.recommendation.RecommendationStore
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Builds the recommendations, a batch of series at a time, until the window holds as many as
 * the reader asked for.
 *
 * A build is resumable: the first run gathers every candidate and reads the first batches; if
 * the target is not met before [RUN_BUDGET_MS] runs out, the queue is kept and the job books
 * itself again for the next day at the same hour, until it is. So a target of five hundred takes
 * a few quiet nights and a target of fifty takes one, with nothing to configure. Each batch is
 * written as it lands, so the window grows as the build goes and a killed run loses one batch.
 *
 * Modes: [MODE_FULL] discards everything and starts over (the schedule, "rebuild from scratch");
 * [MODE_CONTINUE] reads one more batch off the queue, or starts a full build when there is no
 * queue (the "refresh" button: one batch per tap); [MODE_RESUME] carries a scheduled build on
 * (the next-day booking).
 */
class RecommendationJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val mode = inputData.getString(MODE) ?: MODE_FULL
        val manual = inputData.getBoolean(MANUAL, false)
        val preferences = Injekt.get<PreferencesHelper>()
        if (!manual && preferences.recommendationHour().get() < 0) return Result.success()
        // A long run: promoted so the system does not cut it short. Refused on some devices when
        // the app is in the background; the run then simply goes on as ordinary work.
        runCatching { setForeground(getForegroundInfo()) }
        val store = Injekt.get<RecommendationStore>()
        val build = Injekt.get<BuildRecommendations>()
        val target = preferences.recommendationTarget().get()
        val deadline = System.currentTimeMillis() + RUN_BUDGET_MS
        return try {
            var snapshot = store.read()
            val fresh = mode == MODE_FULL || snapshot == null || (mode == MODE_CONTINUE && snapshot.pending.isEmpty())
            if (fresh) {
                // From zero: the old list and the rows it left behind go before anything is built,
                // so nothing of the last run can leak into this one.
                runCatching { Injekt.get<DiscardRecommendations>().await() }
                // What the trackers know about the library comes first: it feeds the profile below.
                runCatching {
                    Injekt.get<EnrichFromTrackers>().await { done, total -> report(BuildProgress(BuildProgress.Stage.ENRICHING, done, total)) }
                }.onFailure { Logger.w(it) { "Tracker enrichment failed; building on what the sources said" } }
                // Same names, other sources: where else the library could be read from.
                runCatching {
                    Injekt.get<FindMergeCandidates>().await { done, total -> report(BuildProgress(BuildProgress.Stage.MERGES, done, total)) }
                }.onFailure { Logger.w(it) { "Merge lookup failed" } }
                snapshot = build.search(onProgress = ::report)
                store.write(snapshot)
            }

            var batches = 0
            // "Fetch 50 more" means fifty more, target or no target; the schedule stops at the target.
            while (snapshot!!.pending.isNotEmpty() && (mode == MODE_CONTINUE || snapshot.shown < target)) {
                if (mode == MODE_CONTINUE && batches >= 1) break
                if (System.currentTimeMillis() > deadline) break
                snapshot = build.detailBatch(snapshot) { progress ->
                    // Progress counts towards the target, not the batch: that is the number the
                    // reader is waiting on.
                    report(BuildProgress(BuildProgress.Stage.DETAILS, snapshot!!.shown, target))
                }
                store.write(snapshot)
                batches++
                // A breath between batches, so a build is a hum and not a burst.
                if (snapshot.shown < target && snapshot.pending.isNotEmpty()) delay(BATCH_PAUSE_MS)
            }

            context.notificationManager.cancel(Notifications.ID_RECOMMENDATIONS_PROGRESS)
            val unfinished = snapshot.shown < target && snapshot.pending.isNotEmpty()
            // Only a scheduled build books its own continuation, and only while the schedule is on.
            if (unfinished && mode != MODE_CONTINUE && preferences.recommendationHour().get() >= 0) resumeTomorrow(context)
            if (manual) notify(snapshot.shown, unfinished)
            Result.success()
        } catch (e: Exception) {
            context.notificationManager.cancel(Notifications.ID_RECOMMENDATIONS_PROGRESS)
            Logger.e(e) { "Recommendation build failed" }
            if (manual) Result.failure() else Result.retry()
        }
    }

    private suspend fun report(progress: BuildProgress) {
        setProgress(workDataOf(STAGE to progress.stage.name, DONE to progress.done, TOTAL to progress.total))
        showProgress(progress)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = progressNotification(BuildProgress(BuildProgress.Stage.PROFILE))
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_RECOMMENDATIONS_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_RECOMMENDATIONS_PROGRESS, notification)
        }
    }

    /** One quiet, ongoing notification that follows the build stage by stage. */
    private fun showProgress(progress: BuildProgress) {
        context.notificationManager.notify(Notifications.ID_RECOMMENDATIONS_PROGRESS, progressNotification(progress))
    }

    private fun progressNotification(progress: BuildProgress): Notification {
        val localeContext = context.localeContext
        return localeContext.notificationBuilder(Notifications.CHANNEL_RECOMMENDATIONS_PROGRESS) {
            setSmallIcon(R.drawable.ic_karasu)
            setContentTitle(localeContext.getString(MR.strings.recommendation_refreshing))
            setContentText(progress.label(localeContext))
            setProgress(progress.total, progress.done, progress.total == 0)
            setOngoing(true)
            setOnlyAlertOnce(true)
        }.build()
    }

    private fun notify(count: Int, unfinished: Boolean) {
        val localeContext = context.localeContext
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.SHORTCUT_RECOMMENDATIONS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = localeContext.notificationBuilder(Notifications.CHANNEL_RECOMMENDATIONS) {
            setSmallIcon(R.drawable.ic_karasu)
            setContentTitle(localeContext.getString(MR.strings.recommendations))
            setContentText(
                localeContext.getString(MR.plurals.recommendation_built, count, count) +
                    if (unfinished) " · " + localeContext.getString(MR.strings.recommendation_more_pending) else "",
            )
            setContentIntent(open)
            setAutoCancel(true)
        }.build()
        context.notificationManager.notify(Notifications.ID_RECOMMENDATIONS, notification)
    }

    companion object {
        private const val TAG = "Recommendations"
        private const val WORK_NAME = "Recommendations"
        private const val NOW_WORK_NAME = "RecommendationsNow"
        private const val MANUAL = "manual"
        private const val MODE = "mode"
        const val MODE_FULL = "full"
        const val MODE_CONTINUE = "continue"
        const val MODE_RESUME = "resume"
        private const val RESUME_WORK_NAME = "RecommendationsResume"

        /** How long one run may work before it hands the rest to the next day. */
        private const val RUN_BUDGET_MS = 40L * 60 * 1000

        /** Between batches. */
        private const val BATCH_PAUSE_MS = 3_000L
        private const val STAGE = "stage"
        private const val DONE = "done"
        private const val TOTAL = "total"

        fun setupTask(context: Context) {
            val preferences = Injekt.get<PreferencesHelper>()
            val hour = preferences.recommendationHour().get()
            val wm = WorkManager.getInstance(context)
            if (hour < 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val network = if (preferences.recommendationOnlyOnWifi().get()) NetworkType.UNMETERED else NetworkType.CONNECTED
            val days = preferences.recommendationIntervalDays().get().coerceAtLeast(1).toLong()
            val request = PeriodicWorkRequestBuilder<RecommendationJob>(days, TimeUnit.DAYS)
                .setInputData(workDataOf(MODE to MODE_FULL))
                .setInitialDelay(AutoMigrateJob.millisUntil(AutoMigrateJob.DAILY, hour), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .addTag(TAG)
                .build()
            // Replaced rather than updated: this job is about *when* it fires.
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)
        }

        /**
         * A run now. One at a time: asking twice does not queue two.
         *
         * @param mode [MODE_CONTINUE] reads one more batch (or starts over when there is no
         *   queue); [MODE_FULL] discards everything first. On a full run the screen is emptied at
         *   once — the reader asked for fresh, and the old list is what they asked to be rid of.
         */
        fun runNow(context: Context, mode: String = MODE_CONTINUE) {
            if (mode == MODE_FULL) Injekt.get<RecommendationStore>().clear()
            val request = OneTimeWorkRequestBuilder<RecommendationJob>()
                .setInputData(workDataOf(MANUAL to true, MODE to mode))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** The rest of a scheduled build, tomorrow at the same hour. */
        private fun resumeTomorrow(context: Context) {
            val preferences = Injekt.get<PreferencesHelper>()
            val hour = preferences.recommendationHour().get().coerceAtLeast(0)
            val network = if (preferences.recommendationOnlyOnWifi().get()) NetworkType.UNMETERED else NetworkType.CONNECTED
            val request = OneTimeWorkRequestBuilder<RecommendationJob>()
                .setInputData(workDataOf(MODE to MODE_RESUME))
                .setInitialDelay(AutoMigrateJob.millisUntil(AutoMigrateJob.DAILY, hour), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(RESUME_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Whether a reader-started rebuild is queued or running. */
        fun isRunningNowFlow(context: Context): Flow<Boolean> = progressFlow(context).map { it != null }

        /**
         * Where the reader-started rebuild is, or null when none is queued or running. Queued
         * but not started yet reads as the first stage, so the reader sees something at once.
         */
        fun progressFlow(context: Context): Flow<BuildProgress?> = stateFlow(context).map { (it as? RunState.Running)?.progress }

        /** Whether the reader-started rebuild is running, and if not, how the last one ended. */
        sealed interface RunState {
            data class Running(val progress: BuildProgress) : RunState
            data object Idle : RunState
            data object Failed : RunState
        }

        fun stateFlow(context: Context): Flow<RunState> =
            // By tag, so a scheduled run and a next-day resumption show their progress too. Only
            // RUNNING counts: a periodic request sits ENQUEUED between runs, always.
            WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { list ->
                val info = list.firstOrNull { it.state == WorkInfo.State.RUNNING }
                if (info == null) {
                    return@map if (list.any { it.state == WorkInfo.State.FAILED }) RunState.Failed else RunState.Idle
                }
                val stage = info.progress.getString(STAGE)?.let { runCatching { BuildProgress.Stage.valueOf(it) }.getOrNull() }
                    ?: BuildProgress.Stage.PROFILE
                RunState.Running(BuildProgress(stage, info.progress.getInt(DONE, 0), info.progress.getInt(TOTAL, 0)))
            }

        /** The stage as a line of text, with the count when the stage has one. */
        fun BuildProgress.label(context: Context): String = when (stage) {
            BuildProgress.Stage.ENRICHING -> context.getString(MR.strings.recommendation_stage_enriching, done, total)
            BuildProgress.Stage.MERGES -> context.getString(MR.strings.recommendation_stage_merges, done, total)
            BuildProgress.Stage.PROFILE -> context.getString(MR.strings.recommendation_stage_profile)
            BuildProgress.Stage.SEARCHING -> context.getString(MR.strings.recommendation_stage_searching, done, total)
            BuildProgress.Stage.TRACKERS -> context.getString(MR.strings.recommendation_stage_trackers, done, total)
            BuildProgress.Stage.DETAILS -> context.getString(MR.strings.recommendation_stage_details, done, total)
            BuildProgress.Stage.RANKING -> context.getString(MR.strings.recommendation_stage_ranking)
        }
    }
}
