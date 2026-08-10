package eu.kanade.tachiyomi.appwidget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import androidx.core.graphics.drawable.toBitmap
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import coil3.asDrawable
import coil3.executeBlocking
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Precision
import coil3.size.Scale
import coil3.transform.RoundedCornersTransformation
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.appwidget.components.CoverHeight
import eu.kanade.tachiyomi.appwidget.components.CoverWidth
import eu.kanade.tachiyomi.appwidget.components.LockedWidget
import eu.kanade.tachiyomi.appwidget.components.ReleasesWidget
import eu.kanade.tachiyomi.appwidget.components.ReleasesWidgetData
import eu.kanade.tachiyomi.appwidget.util.calculateRowAndColumnCount
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.launchIO
import karasu.domain.manga.interval.GetReleaseSchedule
import karasu.domain.manga.interval.ReleaseEstimate
import karasu.domain.manga.interval.ScheduledRelease
import karasu.domain.manga.interval.calendar
import karasu.domain.manga.models.cover
import kotlinx.coroutines.MainScope
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Home screen widget showing what is expected to release today.
 *
 * Falls forward to the next day that has something rather than sitting empty: a widget that says
 * nothing on the days nothing is due would be blank most of the week, and a blank widget is one
 * the user removes. The header says which of the two it is showing, so the fallback never reads
 * as "these are out now".
 */
class ReleasesGlanceWidget : GlanceAppWidget() {
    private val app: Application by injectLazy()
    private val securityPreferences: SecurityPreferences by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    private val coroutineScope = MainScope()

    private var data: ReleasesWidgetData? = null

    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            if (securityPreferences.useBiometrics().get()) {
                LockedWidget()
            } else {
                ReleasesWidget(data)
            }
        }
    }

    fun loadData() {
        coroutineScope.launchIO {
            if (securityPreferences.useBiometrics().get()) {
                updateAll(app)
                return@launchIO
            }

            val manager = GlanceAppWidgetManager(app)
            val ids = manager.getGlanceIds(this@ReleasesGlanceWidget::class.java)
            if (ids.isEmpty()) return@launchIO

            val (rowCount, columnCount) = ids
                .flatMap { manager.getAppWidgetSizes(it) }
                .maxBy { it.height.value * it.width.value }
                .calculateRowAndColumnCount()
            // One row goes to the header, so asking for the full grid would render a row that
            // gets clipped off the bottom.
            val take = (rowCount - 1).coerceAtLeast(1) * columnCount

            data = load(take)
            ids.forEach { update(app, it) }
        }
    }

    private suspend fun load(
        take: Int,
        getReleaseSchedule: GetReleaseSchedule = Injekt.get(),
    ): ReleasesWidgetData {
        val categories = preferences.releaseScheduleCategories().get()
            .mapNotNull { it.toIntOrNull() }
            .toSet()
        val grace = ReleaseEstimate.graceOf(preferences.releaseMissGraceDays().get())
        val calendar = runCatching {
            getReleaseSchedule.await(categories).calendar(dayCount = 7, grace = grace)
        }
            .getOrNull()
            ?: return ReleasesWidgetData(today = true, covers = emptyList())

        val today = calendar.days.first().releases
        val soon = calendar.days.drop(1).firstOrNull { it.releases.isNotEmpty() }?.releases.orEmpty()
        val showing = today.ifEmpty { soon }

        return ReleasesWidgetData(
            today = today.isNotEmpty(),
            covers = showing.take(take).toCovers(),
        )
    }

    private fun List<ScheduledRelease>.toCovers(): List<Pair<Long, Bitmap?>> {
        val widthPx = CoverWidth.value.toInt().dpToPx
        val heightPx = CoverHeight.value.toInt().dpToPx
        val roundPx = app.resources.getDimension(R.dimen.appwidget_inner_radius)
        return mapNotNull { it.manga.takeIf { manga -> manga.id != null } }
            .map { manga -> manga.id!! to manga.toBitmap(widthPx, heightPx, roundPx) }
    }

    private fun Manga.toBitmap(widthPx: Int, heightPx: Int, roundPx: Float): Bitmap? {
        val request = ImageRequest.Builder(app)
            .data(cover())
            .memoryCachePolicy(CachePolicy.DISABLED)
            .precision(Precision.EXACT)
            .size(widthPx, heightPx)
            .scale(Scale.FILL)
            .let {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    it.transformations(RoundedCornersTransformation(roundPx))
                } else {
                    it // Handled by system
                }
            }
            .build()
        return app.imageLoader.executeBlocking(request).image?.asDrawable(app.resources)?.toBitmap()
    }
}
