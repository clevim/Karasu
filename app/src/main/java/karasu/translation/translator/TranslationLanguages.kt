package karasu.translation.translator

import com.google.mlkit.nl.translate.TranslateLanguage
import java.util.Locale

/**
 * The languages a chapter can be translated into.
 *
 * ponytail: no enum here — ML Kit already publishes the list and the JDK already knows how to
 * name a language in the reader's own locale, so keeping a hand-written copy of 59 entries
 * would only be one more thing to keep in sync.
 */
object TranslationLanguages {

    /** ML Kit only knows "pt"; scans are read in Brazil, so that is the variant worth naming. */
    private const val PORTUGUESE_BR = "pt-BR"

    /** Every supported target, sorted by how its name reads in the current locale. */
    val targets: List<String> by lazy {
        TranslateLanguage.getAllLanguages()
            .map { if (it == TranslateLanguage.PORTUGUESE) PORTUGUESE_BR else it }
            .sortedBy { displayName(it) }
    }

    /** Name of the language for the user, e.g. "Japonês" when the app is in Portuguese. */
    fun displayName(tag: String, locale: Locale = Locale.getDefault()): String =
        Locale.forLanguageTag(tag).getDisplayName(locale)
            .replaceFirstChar { it.uppercase(locale) }

    /**
     * Name of the language for a translation prompt. English, and with the region kept, so an
     * LLM is asked for "Portuguese (Brazil)" rather than plain Portuguese.
     */
    fun promptName(tag: String): String = displayName(tag, Locale.ENGLISH)

    /**
     * The plain language code ML Kit's translator wants. The region has to be dropped first:
     * ML Kit rejects "pt-BR" outright rather than resolving it to "pt".
     */
    fun mlkitCode(tag: String): String =
        TranslateLanguage.fromLanguageTag(Locale.forLanguageTag(tag).language)
            ?: TranslateLanguage.ENGLISH
}
