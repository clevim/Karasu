package karasu.domain.translation

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.preference.getEnum
import karasu.translation.recognizer.OcrLanguage
import karasu.translation.translator.TranslationEngine

class TranslationPreferences(private val preferenceStore: PreferenceStore) {

    fun autoTranslateAfterDownload() = preferenceStore.getBoolean("auto_translate_after_download", false)

    fun showTranslations() = preferenceStore.getBoolean("show_translations", true)

    /**
     * Letter translations into the balloon measured off the page, instead of the glyph box padded
     * by a fixed fraction.
     *
     * Experimental, and off until it has been looked at on real pages: the measurement is a set of
     * scanlines walking out from the text, which reads a flat white balloon well and has no answer
     * for lettering over busy art. Read at draw time, so turning it on or off shows immediately on
     * chapters already translated — the balloon is measured and stored either way.
     */
    fun balloonBounds() = preferenceStore.getBoolean("translation_balloon_bounds", false)

    fun translateFrom() = preferenceStore.getEnum("translate_from", OcrLanguage.ENGLISH)

    /** BCP-47 tag, see [karasu.translation.translator.TranslationLanguages]. */
    fun translateTo() = preferenceStore.getString("translate_to", "pt-BR")

    fun engine() = preferenceStore.getEnum("translation_engine", TranslationEngine.MLKIT)

    /** Private: a paid credential has no business in a backup file. */
    fun engineApiKey(engine: TranslationEngine) =
        preferenceStore.getString(Preference.privateKey(scoped(API_KEY, engine)), "")

    // Free-model requests are capped per UTC day and OpenRouter does not report the tally, so the
    // app keeps its own. See [karasu.translation.translator.OpenRouterQuota].
    fun quotaUsed() = preferenceStore.getInt("translation_quota_used", 0)
    fun quotaDay() = preferenceStore.getString("translation_quota_day", "")
    fun quotaCap() = preferenceStore.getInt("translation_quota_cap", 50)

    /**
     * Which model the engine should use. See [TranslationEngine.defaultModel] for each default.
     *
     * OpenRouter's is free, instruction tuned, and deliberately *not* a reasoning model. It used
     * to be "openrouter/free", the router that picks whatever free model is up: resilient in
     * principle, unusable here in practice, because it kept landing on reasoning models, which
     * spend the whole completion budget deliberating and return no JSON at all. A named model is
     * worth the risk of that one slug being retired.
     */
    fun engineModel(engine: TranslationEngine) =
        preferenceStore.getString(scoped(MODEL, engine), engine.defaultModel)

    companion object {
        private const val API_KEY = "translation_engine_api_key"
        private const val MODEL = "translation_engine_model"

        /**
         * Each engine keeps its own credential and model, so switching between them does not
         * make the user retype what is already stored.
         *
         * OpenRouter keeps the unsuffixed names it has always had rather than being migrated:
         * it was the only engine with a key to lose, and leaving its names alone is what stops
         * anyone losing one.
         */
        private fun scoped(name: String, engine: TranslationEngine) =
            if (engine == TranslationEngine.OPENROUTER) name else "${name}_${engine.name.lowercase()}"
    }
}
