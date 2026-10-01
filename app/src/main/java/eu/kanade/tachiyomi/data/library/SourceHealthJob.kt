package eu.kanade.tachiyomi.data.library

import android.content.Context
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
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import java.util.concurrent.TimeUnit
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.failures.interactor.UpdateFailures
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Once a week, asks every source the chosen categories read from for one chapter list — the
 * entry most recently read on it — and files what fails where the broken-sources screen looks.
 *
 * A library update would find the same breakage, but only for what it updates: categories left
 * out of updates (a "finished, rereading someday" shelf, a reserve source) never get asked, and a
 * source that died there is discovered the day the reader opens a chapter. One request per source
 * is enough to tell a working source from a dead one, and cheap enough to run unattended.
 */
class SourceHealthJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<PreferencesHelper>()
        val categories = preferences.sourceCheckCategories().get().mapNotNull { it.toIntOrNull() }.toSet()
        if (categories.isEmpty()) return Result.success()
        val sourceManager = Injekt.get<SourceManager>()
        val getChapter = Injekt.get<GetChapter>()
        val failures = Injekt.get<UpdateFailures>()

        val probes = Injekt.get<GetLibraryManga>().await()
            .filter { it.category in categories }
            .distinctBy { it.manga.id }
            .groupBy { it.manga.source }
            .mapNotNull { (_, entries) -> entries.maxByOrNull { it.lastRead } }

        val broken = mutableListOf<String>()
        for (probe in probes) {
            val manga = probe.manga
            val id = manga.id ?: continue
            val source = sourceManager.get(manga.source) as? CatalogueSource ?: continue
            val error: Throwable? = try {
                val chapters = withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                    source.getMangaUpdate(
                        manga = manga.copy(),
                        chapters = getChapter.awaitAllRaw(id, false),
                        fetchDetails = false,
                        fetchChapters = true,
                    ).chapters
                }
                when {
                    chapters == null -> IllegalStateException("No answer in ${PROBE_TIMEOUT_MS / 1000}s")
                    chapters.isEmpty() -> IllegalStateException("No chapters found")
                    else -> null
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (error == null) {
                failures.clear(id)
            } else {
                Logger.w(error) { "Source check: ${source.name} failed" }
                failures.record(id, error, online = true)
                broken += source.name
            }
        }
        if (broken.isNotEmpty()) notify(broken.distinct())
        return Result.success()
    }

    private fun notify(sources: List<String>) {
        val notification = applicationContext.notificationBuilder(Notifications.CHANNEL_LIBRARY_ERROR) {
            setSmallIcon(R.drawable.ic_karasu)
            setContentTitle(applicationContext.getString(MR.plurals.source_check_failed, sources.size, sources.size))
            setContentText(sources.joinToString(", "))
            setStyle(NotificationCompat.BigTextStyle().bigText(sources.joinToString("\n")))
            setContentIntent(NotificationReceiver.openBrokenSourcesPendingActivity(applicationContext))
            setAutoCancel(true)
        }.build()
        applicationContext.notificationManager.notify(Notifications.ID_SOURCE_CHECK, notification)
    }

    companion object {
        private const val WORK_NAME = "SourceHealthCheck"
        private const val PROBE_TIMEOUT_MS = 30_000L

        /** Scheduled while categories are chosen, cancelled when none are. */
        fun setupTask(context: Context) {
            val wm = WorkManager.getInstance(context)
            if (Injekt.get<PreferencesHelper>().sourceCheckCategories().get().isEmpty()) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SourceHealthJob>(7, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
