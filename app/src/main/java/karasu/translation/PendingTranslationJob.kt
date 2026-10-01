package karasu.translation

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Translates, once on Wi-Fi, the chapters that finished downloading on mobile data while
 * "translate after download" waits for an unmetered network. Without it they stayed untranslated
 * for good, and nothing said so.
 *
 * The list lives in a preference, not in the work's input: chapters keep arriving while the
 * work waits for Wi-Fi, and a preference is what every one of them can add to.
 */
class PendingTranslationJob(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<TranslationPreferences>()
        val pending = preferences.pendingUnmeteredChapters().get()
        if (pending.isEmpty()) return Result.success()
        // Cleared first: whatever arrives while these are queued schedules a run of its own.
        preferences.pendingUnmeteredChapters().set(emptySet())
        val getChapter = Injekt.get<GetChapter>()
        val manager = Injekt.get<TranslationManager>()
        pending.mapNotNull { it.toLongOrNull() }.forEach { id ->
            getChapter.awaitById(id)?.let(manager::translateChapter)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "PendingTranslations"

        /** Remembers [chapterId] and makes sure a run is waiting for Wi-Fi. */
        fun add(context: Context, chapterId: Long) {
            val pending = Injekt.get<TranslationPreferences>().pendingUnmeteredChapters()
            pending.set(pending.get() + chapterId.toString())
            val request = OneTimeWorkRequestBuilder<PendingTranslationJob>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
