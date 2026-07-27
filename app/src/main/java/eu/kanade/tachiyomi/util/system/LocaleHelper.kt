package eu.kanade.tachiyomi.util.system

import android.content.Context
import androidx.core.os.LocaleListCompat
import karasu.i18n.MR
import karasu.util.lang.getString
import eu.kanade.tachiyomi.ui.source.SourcePresenter
import java.util.Locale

/**
 * Utility class to change the application's language in runtime.
 */
object LocaleHelper {

    /**
     * Returns Display name of a string language code
     */
    fun getSourceDisplayName(lang: String?, context: Context): String {
        return when (lang) {
            "", "other" -> context.getString(MR.strings.other)
            SourcePresenter.LAST_USED_KEY -> context.getString(MR.strings.last_used)
            SourcePresenter.PINNED_KEY -> context.getString(MR.strings.pinned)
            "all" -> context.getString(MR.strings.all)
            else -> getLocalizedDisplayName(lang)
        }
    }

    fun getDisplayName(lang: String): String {
        val normalizedLang = when (lang) {
            "zh-CN" -> "zh-Hans"
            "zh-TW" -> "zh-Hant"
            else -> lang
        }

        return Locale.forLanguageTag(normalizedLang).displayName
    }

    /**
     * The `ic_flag_*` drawable for a source language, or null when there is none.
     *
     * Source languages carry a region ("pt-BR", "es-419") more often than the flag set does, so a
     * regional code falls back to the plain language before giving up.
     */
    fun getFlagDrawable(context: Context, lang: String?): Int? {
        if (lang.isNullOrBlank()) return null
        fun lookup(name: String) = context.resources
            .getIdentifier("ic_flag_$name", "drawable", context.packageName)
            .takeIf { it != 0 }

        return lookup(lang.replace("-", "_"))
            ?: lang.substringBefore("-").takeIf { it != lang }?.let { lookup(it) }
    }

    /**
     * Returns Display name of a string language code
     *
     * @param lang empty for system language
     */
    fun getLocalizedDisplayName(lang: String?): String {
        if (lang == null) {
            return ""
        }

        val locale = when (lang) {
            "" -> LocaleListCompat.getAdjustedDefault()[0]
            "zh-CN" -> Locale.forLanguageTag("zh-Hans")
            "zh-TW" -> Locale.forLanguageTag("zh-Hant")
            else -> Locale.forLanguageTag(lang)
        }
        return locale!!.getDisplayName(locale).replaceFirstChar { it.uppercase(locale) }
    }
}
