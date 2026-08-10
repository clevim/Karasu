package eu.kanade.tachiyomi.ui.library.calendar

import android.app.Activity
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsLibraryController
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import karasu.domain.manga.interval.GetReleaseSchedule
import karasu.domain.manga.interval.ReleaseCalendar
import karasu.domain.manga.interval.ReleaseEstimate
import karasu.domain.manga.interval.ReleaseSchedule
import karasu.domain.manga.interval.calendar
import karasu.presentation.library.ReleaseCalendarScreen
import uy.kohesive.injekt.injectLazy

/**
 * The release calendar: what the library is expected to publish, and when.
 *
 * Read on entry rather than observed. The estimates only change when an update runs, which is
 * not something that happens while someone is looking at this screen, and a live query over the
 * whole library would cost more than the screen is worth.
 */
class ReleaseCalendarController : BaseComposeController() {

    private val getReleaseSchedule: GetReleaseSchedule by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    private var schedule by mutableStateOf<ReleaseSchedule?>(null)
    private var calendar by mutableStateOf<ReleaseCalendar?>(null)

    /**
     * Reloaded on every attach and every resume rather than once per composition.
     *
     * Conductor keeps the composition alive while another screen sits on top of it, so a
     * `LaunchedEffect(Unit)` would run once and never again — the phone still open the next
     * morning would label yesterday "Hoje". "Today" is decided here, so it has to be decided
     * each time the screen is actually looked at.
     */
    private fun load() {
        viewScope.launchUI {
            val categories = preferences.releaseScheduleCategories().get()
                .mapNotNull { it.toIntOrNull() }
                .toSet()
            val loaded = withIOContext { getReleaseSchedule.await(categories) }
            schedule = loaded
            calendar = loaded.calendar(
                dayCount = DAYS_SHOWN,
                grace = ReleaseEstimate.graceOf(preferences.releaseMissGraceDays().get()),
            )
        }
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        load()
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        if (view != null) load()
    }

    @Composable
    override fun ScreenContent() {
        ReleaseCalendarScreen(
            schedule = schedule,
            calendar = calendar,
            contentPadding = PaddingValues(bottom = 32.dp),
            onMangaClick = { manga ->
                manga.id?.let { router.pushController(MangaDetailsController(it).withFadeTransaction()) }
            },
            // The settings live in one place rather than being mirrored into a sheet here: two
            // screens editing the same preferences is two places for them to drift apart.
            onSettingsClick = {
                router.pushController(SettingsLibraryController().withFadeTransaction())
            },
        )
    }

    companion object {
        /** Two weeks: long enough to show a monthly series, short enough to stay scrollable. */
        private const val DAYS_SHOWN = 14
    }
}
