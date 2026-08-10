package eu.kanade.tachiyomi.appwidget.components

import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.wrapContentSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.appwidget.util.appWidgetBackgroundRadius
import eu.kanade.tachiyomi.appwidget.util.calculateRowAndColumnCount
import eu.kanade.tachiyomi.appwidget.util.stringResource
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.SearchActivity
import karasu.i18n.MR

/**
 * @param today whether [covers] are expected today, as opposed to being the next day that has
 *  anything. The header depends on it, and getting it wrong would tell someone a chapter is out
 *  when it is days away.
 */
data class ReleasesWidgetData(
    val today: Boolean,
    val covers: List<Pair<Long, Bitmap?>>,
)

@Composable
fun ReleasesWidget(data: ReleasesWidgetData?) {
    val (rowCount, columnCount) = LocalSize.current.calculateRowAndColumnCount()
    val mainIntent = Intent(LocalContext.current, MainActivity::class.java)
        .setAction(MainActivity.SHORTCUT_RELEASE_CALENDAR)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    // The panel wraps the covers instead of filling the widget: this one shows a handful of
    // entries rather than a full grid, so a slab sized to the whole cell is mostly flat colour
    // sitting on the wallpaper. The root stays transparent and only marks itself as the widget
    // background, which is what the system needs for its own rounding.
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .clickable(actionStartActivity(mainIntent)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = GlanceModifier
                .wrapContentSize()
                .background(ImageProvider(R.drawable.appwidget_background))
                .appWidgetBackgroundRadius()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                data == null -> CircularProgressIndicator()
                data.covers.isEmpty() -> Text(
                    text = stringResource(MR.strings.release_widget_nothing),
                    style = TextStyle(color = GlanceTheme.colors.onSurface),
                )
                else -> {
                    Text(
                        text = stringResource(
                            if (data.today) MR.strings.release_widget_today else MR.strings.release_widget_soon,
                        ),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = GlanceModifier.padding(bottom = 6.dp),
                    )
                    (0 until (rowCount - 1).coerceAtLeast(1)).forEach { i ->
                        val coverRow = (0 until columnCount).mapNotNull { j ->
                            data.covers.getOrNull(j + (i * columnCount))
                        }
                        if (coverRow.isNotEmpty()) {
                            Row(
                                modifier = GlanceModifier.wrapContentSize(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                coverRow.forEach { (mangaId, cover) ->
                                    Box(
                                        modifier = GlanceModifier.padding(3.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        val intent =
                                            SearchActivity.openMangaIntent(LocalContext.current, mangaId, true)
                                                .addFlags(
                                                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                                )
                                                // https://issuetracker.google.com/issues/238793260
                                                .addCategory(mangaId.toString())
                                        UpdatesMangaCover(
                                            modifier = GlanceModifier.clickable(actionStartActivity(intent)),
                                            cover = cover,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
