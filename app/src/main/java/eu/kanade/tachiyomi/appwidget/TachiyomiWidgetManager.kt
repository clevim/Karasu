package eu.kanade.tachiyomi.appwidget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager

class TachiyomiWidgetManager {

    suspend fun Context.init() {
        val manager = GlanceAppWidgetManager(this)
        if (manager.getGlanceIds(UpdatesGridGlanceWidget::class.java).isNotEmpty()) {
            UpdatesGridGlanceWidget().loadData()
        }
        refreshReleases()
    }

    /**
     * Redraws the release widget if one is on the home screen.
     *
     * Called after a library update as well as at startup: an update is the only thing that
     * moves a release estimate, and a calendar widget showing yesterday's guess is worse than
     * one that is a few seconds late.
     */
    suspend fun Context.refreshReleases() {
        val manager = GlanceAppWidgetManager(this)
        if (manager.getGlanceIds(ReleasesGlanceWidget::class.java).isNotEmpty()) {
            ReleasesGlanceWidget().loadData()
        }
    }
}
