package eu.kanade.tachiyomi.data.library

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.data.preference.DEVICE_BATTERY_NOT_LOW
import eu.kanade.tachiyomi.data.preference.DEVICE_CHARGING
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.isLocal
import java.util.Date
import java.util.concurrent.TimeUnit
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.manga.interval.FetchInterval
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Checks the handful of manga whose release window is open right now.
 *
 * The scheduled library update answers "has anything changed anywhere", and it is the wrong
 * tool for "this one posts on Thursday afternoons": running it often enough to catch that would
 * mean asking every source about every manga all day. This runs often instead, and asks about
 * almost nothing — only the entries [FetchInterval] says are due, which outside their windows is
 * none of them.
 *
 * The two jobs are complements, not alternatives. The full update stays on whatever frequency
 * the user picked and remains the safety net that catches anything the estimate got wrong.
 */
class ReleaseWatchJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<PreferencesHelper>()
        if (!preferences.smartLibraryUpdates().get()) return Result.success()

        // A full update is already walking the whole library; anything due is about to be
        // fetched by it, and two jobs hitting the same sources at once is what rate limits are
        // for. Nothing is lost by waiting: whatever is due stays due.
        if (LibraryUpdateJob.isRunning(context)) return Result.success()

        val now = Date().time
        val due = Injekt.get<FetchInterval>().awaitDue().filterValues { it <= now }
        if (due.isEmpty()) return Result.success()

        // The categories the release schedule covers. Empty means all of them.
        val included = preferences.releaseScheduleCategories().get().map(String::toInt)
        // A category excluded from the scheduled update is excluded here too. "Never update
        // this" is a statement about updating, not about which job does the updating, and
        // watching a category the user told the app to leave alone would plainly be wrong.
        val excluded = preferences.libraryUpdateCategoriesExclude().get().map(String::toInt)
        val library = Injekt.get<GetLibraryManga>().await()
        val excludedIds = library.filter { it.category in excluded }.mapNotNull { it.manga.id }.toSet()

        // Same restrictions the update job applies. Without this the batch would fill with manga
        // that get skipped on arrival — and a skipped manga is never fetched, so its estimate is
        // never rewritten and it stays due forever, crowding out the ones that can progress.
        val restrictions = preferences.libraryUpdateMangaRestriction().get()

        val toUpdate = library
            .filter { included.isEmpty() || it.category in included }
            .filter { manga ->
                val id = manga.manga.id ?: return@filter false
                due.containsKey(id) &&
                    id !in excludedIds &&
                    !manga.manga.isLocal() &&
                    restrictedBy(manga, restrictions) == null
            }
            .distinctBy { it.manga.id }
            .sortedBy { due[it.manga.id] }
            .take(BATCH_SIZE)

        if (toUpdate.isEmpty()) return Result.success()

        LibraryUpdateJob.startNow(context, mangaToUse = toUpdate)
        return Result.success()
    }

    companion object {
        private const val TAG = "ReleaseWatch"
        private const val WORK_NAME = "ReleaseWatch"

        /**
         * Most due manga per run. A library that has been closed for a week comes back with
         * everything due at once, and that should not turn into one burst of hundreds of
         * requests. The overflow is still due on the next run, minutes later.
         */
        private const val BATCH_SIZE = 40

        /**
         * Follows [LibraryUpdateJob.setupTask] rather than being wired up separately, so there
         * is one place that decides whether background updating happens at all.
         *
         * @param libraryInterval the frequency the full update was just set up with. Passed in
         *  rather than read back, because the caller may be reacting to a preference change that
         *  has not been written yet.
         */
        fun setupTask(context: Context, libraryInterval: Int) {
            val preferences = Injekt.get<PreferencesHelper>()
            val hours = preferences.releaseWatchInterval().get()
            val wm = WorkManager.getInstance(context)

            // Off when the user asked for manual updates only: they said not to go online on
            // their own, and this is no exception to that.
            if (hours <= 0 || libraryInterval <= 0 || !preferences.smartLibraryUpdates().get()) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }

            val restrictions = preferences.libraryUpdateDeviceRestriction().get()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (DEVICE_ONLY_ON_WIFI in restrictions) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresCharging(DEVICE_CHARGING in restrictions)
                .setRequiresBatteryNotLow(DEVICE_BATTERY_NOT_LOW in restrictions)
                .build()

            val request = PeriodicWorkRequestBuilder<ReleaseWatchJob>(
                hours.toLong(),
                TimeUnit.HOURS,
                15,
                TimeUnit.MINUTES,
            )
                .addTag(TAG)
                .setConstraints(constraints)
                .build()

            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
