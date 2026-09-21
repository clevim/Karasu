package karasu.presentation.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import karasu.domain.manga.interval.ReleaseMonth
import java.time.YearMonth
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import karasu.presentation.core.enterAlwaysAppBarScrollBehavior
import karasu.presentation.KarasuScaffold
import karasu.presentation.AppBarType
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import androidx.compose.foundation.lazy.rememberLazyListState
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
    month: ReleaseMonth?,
    monthView: Boolean,
    onMonthViewChange: (Boolean) -> Unit,
    onlyCaughtUp: Boolean,
    onOnlyCaughtUpChange: (Boolean) -> Unit,
    onMangaClick: (Manga) -> Unit,
    onSettingsClick: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val onBack = LocalBackPress.current ?: {}
    val listState = rememberLazyListState()

    // The same small app bar every other pushed screen has; the settings shortcut is its action.
    KarasuScaffold(
        onNavigationIconClicked = onBack,
        title = stringResource(MR.strings.release_calendar),
        appBarType = AppBarType.SMALL,
        scrollBehavior = enterAlwaysAppBarScrollBehavior(
            canScroll = { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 },
        ),
        actions = {
            // Two toggles that change what is on screen, then the settings that change how it
            // is computed. Toggles are filled when on, the way filter icons read elsewhere.
            IconButton(onClick = { onOnlyCaughtUpChange(!onlyCaughtUp) }) {
                Icon(
                    imageVector = if (onlyCaughtUp) Icons.Filled.CheckCircle else Icons.Outlined.CheckCircle,
                    contentDescription = stringResource(MR.strings.release_calendar_only_caught_up),
                    tint = if (onlyCaughtUp) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
            IconButton(onClick = { onMonthViewChange(!monthView) }) {
                Icon(
                    imageVector = if (monthView) Icons.Outlined.ViewList else Icons.Outlined.CalendarMonth,
                    contentDescription = stringResource(if (monthView) MR.strings.release_calendar_list_view else MR.strings.release_calendar_month_view),
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = stringResource(MR.strings.settings),
                )
            }
        },
    ) { innerPadding ->
        if (schedule == null || calendar == null || month == null) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@KarasuScaffold
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // What landed today comes first: "did it come out?" is the question the day starts with.
            if (schedule.arrived.isNotEmpty()) {
                item {
                    SectionHeader(title = stringResource(MR.strings.release_calendar_arrived))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(schedule.arrived, key = { "arrived-" + (it.id ?: it.url) }) { manga ->
                            MangaCard(manga = manga, hint = "✓", onClick = { onMangaClick(manga) })
                        }
                    }
                }
            }

            if (monthView) {
                item {
                    MonthGrid(month = month, onMangaClick = onMangaClick)
                }
            } else {
                items(calendar.days, key = { it.date.toString() }) { day ->
                    DaySection(
                        title = day.date.label(),
                        releases = day.releases,
                        onMangaClick = onMangaClick,
                    )
                }
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

            if (schedule.onHiatus.isNotEmpty()) {
                item {
                    SectionHeader(
                        title = stringResource(MR.strings.release_calendar_hiatus),
                        subtitle = stringResource(MR.strings.release_calendar_hiatus_summary),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(schedule.onHiatus, key = { "hiatus-" + (it.id ?: it.url) }) { manga ->
                            MangaCard(manga = manga, hint = null, onClick = { onMangaClick(manga) })
                        }
                    }
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
                    // A guess borrowed from the source's other series says so, instead of
                    // dressing up as a measurement with a "±" on it.
                    hint = if (release.guessed) stringResource(MR.strings.release_calendar_probably) else release.estimate.accuracyLabel(),
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

/**
 * The month as a grid: a cell per day, a dot per expected release, the day's covers below the
 * grid for whichever day is tapped. Today is tapped by default, so the grid opens on something.
 */
@Composable
private fun MonthGrid(month: ReleaseMonth, onMangaClick: (Manga) -> Unit) {
    val today = LocalDate.now()
    var selected by remember(month.month) { mutableStateOf(if (today.yearMonth == month.month) today else month.month.atDay(1)) }
    val byDate = month.days.associateBy { it.date }
    // Cells before the first of the month, so weekdays line up with their column.
    val firstColumn = (month.days.first().date.dayOfWeek.value % 7)
    val cells: List<LocalDate?> = List(firstColumn) { null } + month.days.map { it.date }
    val rows = cells.chunked(7)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = month.month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
                .replaceFirstChar { it.titlecase(Locale.getDefault()) } + " " + month.month.year,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            for (i in 0 until 7) {
                Text(
                    text = java.time.DayOfWeek.of(if (i == 0) 7 else i).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { date -> DayCell(date, byDate[date]?.releases?.size ?: 0, date == selected, date == today) { date?.let { selected = it } } }
                repeat(7 - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
        val releases = byDate[selected]?.releases.orEmpty()
        DaySection(title = selected.label(), releases = releases, onMangaClick = onMangaClick)
    }
}

@Composable
private fun RowScope.DayCell(date: LocalDate?, count: Int, selected: Boolean, isToday: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .weight(1f)
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .then(if (date != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = date?.dayOfMonth?.toString().orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        // Up to three dots; past that the number says it.
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.height(8.dp)) {
            if (count in 1..3) repeat(count) { Dot() }
            if (count > 3) Text(text = count.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Dot() {
    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
}

private val LocalDate.yearMonth: YearMonth get() = YearMonth.from(this)
