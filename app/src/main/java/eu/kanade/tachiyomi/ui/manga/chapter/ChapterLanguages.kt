package eu.kanade.tachiyomi.ui.manga.chapter

import android.app.Activity
import android.content.Context
import android.text.style.ImageSpan
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import karasu.i18n.MR
import karasu.util.lang.getString
import android.R as AR

/** A language this chapter row stands for, paired with the row to open for it. */
typealias ChapterLanguage = Pair<MangaDetailsPresenter.ChapterSource, Chapter>

/**
 * The languages a merged chapter row covers, its own first.
 *
 * A row with no alternates is just itself, so this is a single entry for every unmerged manga —
 * which is also what keeps the flag drawing below to one flag in the usual case.
 */
fun Chapter.languageSources(presenter: MangaDetailsPresenter): List<ChapterLanguage> =
    alternates.ifEmpty { listOf(this) }
        .mapNotNull { row -> row.manga_id?.let { presenter.chapterSources[it] }?.to(row) }
        .distinctBy { it.first.lang }

/**
 * Prefixes [text] with [flags] drawn inline, sized off [textSize] rather than the drawables' own
 * intrinsic size — the flag assets are authored at wildly different sizes.
 */
fun flaggedText(context: Context, flags: List<Int>, textSize: Float, text: CharSequence): CharSequence {
    if (flags.isEmpty()) return text
    val height = textSize.toInt()
    return buildSpannedString {
        flags.forEach { flag ->
            val drawable = AppCompatResources.getDrawable(context, flag) ?: return@forEach
            drawable.setBounds(0, 0, height * 3 / 2, height)
            inSpans(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM)) { append(" ") }
            append(" ")
        }
        append(text)
    }
}

/**
 * Asks which translation to open when a chapter row stands for more than one language.
 *
 * The row is the same chapter either way, so the choice is a flat list of sources rather than
 * something the list itself has to expand into.
 */
fun Activity.showChapterLanguageDialog(languages: List<ChapterLanguage>, onPick: (Chapter) -> Unit) {
    val textSize = 16f * resources.displayMetrics.scaledDensity
    val items = languages.map { (source, _) ->
        flaggedText(
            this,
            listOfNotNull(LocaleHelper.getFlagDrawable(this, source.lang)),
            textSize,
            "${LocaleHelper.getLocalizedDisplayName(source.lang)} • ${source.name}",
        )
    }.toTypedArray()

    materialAlertDialog()
        .setTitle(MR.strings.source.getString(this))
        .setItems(items) { _, index -> onPick(languages[index].second) }
        .setNegativeButton(AR.string.cancel, null)
        .show()
}
