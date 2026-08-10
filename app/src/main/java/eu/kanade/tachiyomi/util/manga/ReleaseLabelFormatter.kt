package eu.kanade.tachiyomi.util.manga

import android.content.Context
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import karasu.domain.manga.interval.ReleaseLabel
import karasu.i18n.MR
import karasu.util.lang.getString

/**
 * Turns a [ReleaseLabel] into the phrase shown next to the chapter count.
 *
 * Weekday names come from the platform rather than the string files: every locale already ships
 * them, and they are the one part of this that must never read as a translation.
 */
fun ReleaseLabel.format(context: Context, locale: Locale = Locale.getDefault()): String = when (this) {
    ReleaseLabel.Today -> MR.strings.today.getString(context)
    ReleaseLabel.Tomorrow -> MR.strings.tomorrow.getString(context)
    is ReleaseLabel.EveryWeekOn ->
        context.getString(MR.strings.release_estimate_weekly, day.name(locale))
    is ReleaseLabel.Between ->
        context.getString(MR.strings.release_estimate_between, from.name(locale), to.name(locale))
    is ReleaseLabel.OnDate ->
        context.getString(
            MR.strings.release_estimate_on,
            date.format(DateTimeFormatter.ofPattern("d MMM", locale)),
        )
}

private fun DayOfWeek.name(locale: Locale): String =
    getDisplayName(TextStyle.FULL, locale).replaceFirstChar { it.titlecase(locale) }
