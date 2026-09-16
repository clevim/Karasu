package karasu.translation.translator

import karasu.domain.translation.TranslationPreferences
import karasu.translation.model.PageTranslation
import karasu.translation.recognizer.OcrLanguage
import java.io.Closeable

interface TextTranslator : Closeable {
    val fromLang: OcrLanguage

    /** BCP-47 tag of the target language, see [TranslationLanguages]. */
    val toLang: String

    /** Fills in [karasu.translation.model.TranslationBlock.translation] in place. */
    suspend fun translate(pages: MutableMap<String, PageTranslation>)
}

enum class TranslationEngine(val label: String, val needsApiKey: Boolean) {
    MLKIT("ML Kit (on device)", false),
    OPENROUTER("OpenRouter", true),
    ;

    fun build(
        preferences: TranslationPreferences,
        fromLang: OcrLanguage,
        toLang: String,
    ): TextTranslator = when (this) {
        MLKIT -> MLKitTranslator(fromLang, toLang)
        OPENROUTER -> OpenRouterTranslator(
            fromLang = fromLang,
            toLang = toLang,
            apiKey = preferences.engineApiKey().get(),
            modelName = preferences.engineModel().get(),
        )
    }
}
