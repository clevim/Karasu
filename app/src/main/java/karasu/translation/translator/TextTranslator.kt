package karasu.translation.translator

import karasu.domain.translation.TranslationPreferences
import karasu.translation.model.PageTranslation
import karasu.translation.model.SeriesNotes
import karasu.translation.recognizer.OcrLanguage
import karasu.translation.translator.OpenAiChatTranslator.Companion.DEEPSEEK_ENDPOINT
import karasu.translation.translator.OpenAiChatTranslator.Companion.OPENROUTER_ENDPOINT
import java.io.Closeable
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

interface TextTranslator : Closeable {
    val fromLang: OcrLanguage

    /** BCP-47 tag of the target language, see [TranslationLanguages]. */
    val toLang: String

    /**
     * Terms this engine settled on while translating: proper nouns, recurring vocabulary.
     *
     * Read after [translate] and folded into the manga's notes, so the next chapter is told what
     * the last one decided. Empty for an engine that translates a line at a time and has no
     * notion of a series.
     */
    val learned: Map<String, String> get() = emptyMap()

    /** Fills in [karasu.translation.model.TranslationBlock.translation] in place. */
    suspend fun translate(pages: MutableMap<String, PageTranslation>)
}

/**
 * @param needsApiKey whether the engine has a credential to enter.
 * @param defaultModel what the model field starts at; blank for engines without one.
 */
enum class TranslationEngine(
    val label: String,
    val needsApiKey: Boolean,
    val defaultModel: String = "",
) {
    MLKIT("ML Kit (on device)", false),
    OPENROUTER("OpenRouter", true, defaultModel = "google/gemma-4-31b-it:free"),
    DEEPSEEK("DeepSeek", true, defaultModel = "deepseek-chat"),
    ;

    /** @param context what is known about this series (notes, glossary); only an LLM uses it. */
    fun build(
        preferences: TranslationPreferences,
        fromLang: OcrLanguage,
        toLang: String,
        context: SeriesNotes = SeriesNotes(),
    ): TextTranslator = when (this) {
        MLKIT -> MLKitTranslator(fromLang, toLang)
        // One client for both: they speak the same OpenAI-shaped protocol, and what differs is
        // the address, the attribution headers and whether there is a free allowance to check.
        OPENROUTER, DEEPSEEK -> OpenAiChatTranslator(
            fromLang = fromLang,
            toLang = toLang,
            apiKey = preferences.engineApiKey(this).get(),
            modelName = preferences.engineModel(this).get(),
            context = context,
            endpoint = if (this == DEEPSEEK) DEEPSEEK_ENDPOINT else OPENROUTER_ENDPOINT,
            providerName = label,
            quota = if (this == OPENROUTER) Injekt.get() else null,
        )
    }
}
