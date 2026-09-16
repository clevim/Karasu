package karasu.domain.translation

import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.preference.getEnum
import karasu.translation.recognizer.OcrLanguage
import karasu.translation.translator.TranslationEngine

class TranslationPreferences(private val preferenceStore: PreferenceStore) {

    fun autoTranslateAfterDownload() = preferenceStore.getBoolean("auto_translate_after_download", false)

    fun showTranslations() = preferenceStore.getBoolean("show_translations", true)

    fun translateFrom() = preferenceStore.getEnum("translate_from", OcrLanguage.ENGLISH)

    /** BCP-47 tag, see [karasu.translation.translator.TranslationLanguages]. */
    fun translateTo() = preferenceStore.getString("translate_to", "pt-BR")

    fun engine() = preferenceStore.getEnum("translation_engine", TranslationEngine.MLKIT)

    fun engineApiKey() = preferenceStore.getString("translation_engine_api_key", "")

    // Free-model requests are capped per UTC day and OpenRouter does not report the tally, so the
    // app keeps its own. See [karasu.translation.translator.OpenRouterQuota].
    fun quotaUsed() = preferenceStore.getInt("translation_quota_used", 0)
    fun quotaDay() = preferenceStore.getString("translation_quota_day", "")
    fun quotaCap() = preferenceStore.getInt("translation_quota_cap", 50)

    /**
     * Free, instruction tuned, and deliberately *not* a reasoning model.
     *
     * The default used to be "openrouter/free", the router that picks whatever free model is up.
     * Resilient in principle, unusable here in practice: it kept landing on reasoning models,
     * which spend the whole completion budget deliberating and return no JSON at all. A named
     * model is worth the risk of that one slug being retired.
     */
    fun engineModel() = preferenceStore.getString("translation_engine_model", "google/gemma-4-31b-it:free")
}
