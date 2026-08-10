package karasu.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.domain.manga.models.Manga
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import karasu.domain.manga.interval.ReleaseCalendar
import karasu.domain.manga.interval.ReleaseSchedule
import karasu.domain.manga.interval.ScheduledRelease
import karasu.domain.manga.models.cover
import karasu.i18n.MR
import karasu.presentation.manga.components.MangaCover
import karasu.presentation.manga.components.MangaCoverRatio

private val COVER_WIDTH = 96.dp

/**
 * The library laid out by when each entry is expected to release.
 *
 * A day is a row rather than a cell in a month grid: covers are the point, and covers need width.
 * Empty days are drawn anyway so the week reads as a week instead of as a list that happens to
 * have dates in it.
 *
 * The two buckets at the bottom are as important as the days. An entry with no estimate is not a
 * failure to display, it is the honest state of a source that reports no upload dates and has
 * not been watched long enough yet — hiding those would make the calendar look like it lost half
 * the library.
 */
@Composable
fun ReleaseCalendarScreen(
    schedule: ReleaseSchedule?,
    calendar: ReleaseCalendar?,
    onMangaClick: (Manga) -> Unit,
    onSettingsClick: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    if (schedule == null || calendar == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(MR.strings.release_calendar),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = stringResource(MR.strings.settings),
                    )
                }
            }
        }

        items(calendar.days, key = { it.date.toString() }) { day ->
            DaySection(
                title = day.date.label(),
                releases = day.releases,
                onMangaClick = onMangaClick,
            )
        }

        if (calendar.later.isNotEmpty()) {
            item {
                DaySection(
                    title = stringResource(MR.strings.release_calendar_later),
                    releases = calendar.later,
                    onMangaClick = onMangaClick,
                )
            }
        }

        if (schedule.stalled.isNotEmpty()) {
            item {
                DaySection(
                    title = stringResource(MR.strings.release_calendar_stalled),
                    subtitle = stringResource(MR.strings.release_calendar_stalled_summary),
                    releases = schedule.stalled,
                    onMangaClick = onMangaClick,
                )
            }
        }

        if (schedule.unknown.isNotEmpty()) {
            item {
                SectionHeader(
                    title = stringResource(MR.strings.release_calendar_unknown),
                    subtitle = stringResource(MR.strings.release_calendar_unknown_summary),
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(schedule.unknown, key = { it.id ?: it.url }) { manga ->
                        MangaCard(manga = manga, hint = null, onClick = { onMangaClick(manga) })
                    }
                }
            }
        }
    }
}

@Composable
private fun DaySection(
    title: String,
    releases: List<ScheduledRelease>,
    onMangaClick: (Manga) -> Unit,
    subtitle: String? = null,
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        SectionHeader(title = title, subtitle = subtitle)
        if (releases.isEmpty()) {
            Text(
                text = stringResource(MR.strings.release_calendar_nothing_expected),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(releases, key = { it.manga.id ?: it.manga.url }) { release ->
                MangaCard(
                    manga = release.manga,
                    hint = release.estimate.accuracyLabel(),
                    onClick = { onMangaClick(release.manga) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MangaCard(manga: Manga, hint: String?, onClick: () -> Unit) {
    Column(modifier = Modifier.width(COVER_WIDTH)) {
        MangaCover(
            data = manga.cover(),
            ratio = MangaCoverRatio.BOOK,
            contentDescription = manga.title,
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = manga.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * Today and tomorrow are named; the rest of the week is a weekday, which is how anyone reading
 * a schedule thinks about it. Past the week the date itself is the only useful label.
 */
@Composable
private fun LocalDate.label(): String {
    val today = LocalDate.now()
    return when (this) {
        today -> stringResource(MR.strings.today)
        today.plusDays(1) -> stringResource(MR.strings.tomorrow)
        else -> {
            val weekday = dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            "$weekday, $dayOfMonth/$monthValue"
        }
    }
}

/**
 * How wide the guess is, in plain words.
 *
 * Shown because the width is the honest part of the estimate: a series the app has watched for
 * months lands on a day, while one it has seen three chapters of could be days out either way,
 * and a card that looks identical in both cases would be lying by omission.
 */
@Composable
private fun karasu.domain.manga.interval.ReleaseEstimate.accuracyLabel(): String? {
    val days = spread / (24 * 60 * 60 * 1000)
    return when {
        days <= 0L -> null
        days == 1L -> stringResource(MR.strings.release_calendar_give_or_take_day)
        else -> stringResource(MR.strings.release_calendar_give_or_take_days, days.toString())
    }
}
