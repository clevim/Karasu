package eu.kanade.tachiyomi.data.library

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import java.time.Duration
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import karasu.domain.manga.interval.GetReleaseSchedule
import karasu.domain.manga.interval.ReleaseEstimate
import karasu.domain.manga.interval.calendar
import karasu.i18n.MR
import karasu.util.lang.getString
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Posts one notification a day listing what the library is expected to release today.
 *
 * The calendar already knows this; the notification exists because a calendar only answers when
 * it is opened, and the whole point of an estimate is to not have to go looking. Reading only, no
 * network: everything it says was worked out by the update that already ran.
 */
class ReleaseDigestJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val preferences = Injekt.get<PreferencesHelper>()
        if (preferences.releaseDigestHour().get() < 0) return Result.success()

        val categories = preferences.releaseScheduleCategories().get()
            .mapNotNull(String::toIntOrNull)
            .toSet()

        // Day one of the calendar rather than a fresh query, so the notification and the screen
        // it opens can never disagree — including about overdue entries, which the calendar puts
        // on today because the chapter has not arrived and is therefore still coming.
        val today = Injekt.get<GetReleaseSchedule>().await(categories)
            .calendar(
                dayCount = 1,
                grace = ReleaseEstimate.graceOf(preferences.releaseMissGraceDays().get()),
            )
            .days.firstOrNull()
            ?.releases
            .orEmpty()

        // Silence is the right answer here. A daily "nothing today" is a notification that
        // trains the user to swipe the channel away.
        if (today.isEmpty()) return Result.success()

        notify(today.map { it.manga.title })
        return Result.success()
    }

    private fun notify(titles: List<String>) {
        val localeContext = context.localeContext
        val openCalendar = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.SHORTCUT_RELEASE_CALENDAR)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = localeContext
            .notificationBuilder(Notifications.CHANNEL_RELEASE_DIGEST) {
                setSmallIcon(R.drawable.ic_calendar_month_24dp)
                setContentTitle(
                    localeContext.getString(MR.plurals.release_digest_title, titles.size, titles.size),
                )
                setContentText(titles.joinToString(", "))
                // The list is the content, so it should be readable without tapping through.
                setStyle(NotificationCompat.BigTextStyle().bigText(titles.joinToString("\n")))
                setContentIntent(openCalendar)
                setAutoCancel(true)
            }
            .build()

        context.notificationManager.notify(Notifications.ID_RELEASE_DIGEST, notification)
    }

    companion object {
        private const val TAG = "ReleaseDigest"
        private const val WORK_NAME = "ReleaseDigest"

        /**
         * Scheduled from [LibraryUpdateJob.setupTask] like the watcher, so one place decides
         * whether any of the background release machinery runs.
         */
        fun setupTask(context: Context) {
            val hour = Injekt.get<PreferencesHelper>().releaseDigestHour().get()
            val wm = WorkManager.getInstance(context)

            if (hour < 0) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }

            val request = PeriodicWorkRequestBuilder<ReleaseDigestJob>(1, TimeUnit.DAYS)
                .setInitialDelay(millisUntilHour(hour), TimeUnit.MILLISECONDS)
                .addTag(TAG)
                .build()

            // Replaced rather than updated: the whole point of this job is *when* it fires, and
            // UPDATE keeps the existing schedule, so changing the hour would not move it.
            wm.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                request,
            )
        }

        /**
         * How long until the next [hour] o'clock in local time.
         *
         * Computed on [ZonedDateTime] rather than in milliseconds so the day the clocks change is
         * still one day: a fixed 24 hour period would drift the digest an hour off twice a year
         * and never drift back.
         */
        @VisibleForTesting
        fun millisUntilHour(hour: Int, now: ZonedDateTime = ZonedDateTime.now()): Long {
            val todayAt = now.truncatedTo(ChronoUnit.DAYS).withHour(hour)
            val next = if (todayAt.isAfter(now)) todayAt else todayAt.plusDays(1)
            return Duration.between(now, next).toMillis()
        }
    }
}
