package eu.kanade.tachiyomi.ui.setting.controllers

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.bindTo
import eu.kanade.tachiyomi.ui.setting.editTextPreference
import eu.kanade.tachiyomi.ui.setting.infoPreference
import eu.kanade.tachiyomi.ui.setting.listPreference
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.ui.setting.summaryMRes as summaryRes
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import karasu.domain.translation.TranslationPreferences
import karasu.i18n.MR
import karasu.translation.data.TranslationCache
import karasu.translation.recognizer.OcrLanguage
import karasu.translation.translator.OpenRouterQuota
import karasu.translation.translator.TranslationEngine
import karasu.translation.translator.TranslationLanguages
import karasu.util.lang.getString
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

/**
 * Chapter translation, on a screen of its own. It used to be the last category of the reader's
 * settings — a dozen rows under the volume-key options, found by those who already knew it was
 * there — and it has grown into a feature with engines, keys, models and a data cost of its own.
 */
class SettingsTranslationController : SettingsLegacyController() {

    private val translationPreferences: TranslationPreferences by injectLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = MR.strings.translation

        infoPreference(MR.strings.translation_privacy_info)

        preferenceCategory {
            titleRes = MR.strings.display

            switchPreference {
                bindTo(translationPreferences.showTranslations())
                titleRes = MR.strings.show_translations
                summaryRes = MR.strings.show_translations_summary
            }
            switchPreference {
                bindTo(translationPreferences.balloonBounds())
                titleRes = MR.strings.translation_balloon_bounds
                summaryRes = MR.strings.translation_balloon_bounds_summary
            }
        }

        preferenceCategory {
            titleRes = MR.strings.translation_engine_category

            listPreference(activity) {
                bindTo(translationPreferences.engine())
                titleRes = MR.strings.translation_engine
                entryValues = TranslationEngine.entries.map { it.name }
                entries = TranslationEngine.entries.map { it.label }
            }
            // One set of fields per engine, each shown only for the engine it belongs to: the
            // preferences are bound when the screen is built, so a single field could not follow
            // the engine picker anyway, and each engine keeps its own stored values.
            TranslationEngine.entries.filter { it.needsApiKey }.forEach { engine ->
                editTextPreference(activity) {
                    bindTo(translationPreferences.engineApiKey(engine))
                    titleRes = MR.strings.translation_api_key
                    summaryRes = MR.strings.translation_api_key_summary

                    translationPreferences.engine().changesIn(viewScope) { isVisible = it == engine }
                }
                editTextPreference(activity) {
                    bindTo(translationPreferences.engineModel(engine))
                    titleRes = MR.strings.translation_model
                    summaryRes = if (engine == TranslationEngine.GEMINI) {
                        MR.strings.translation_model_summary_gemini
                    } else {
                        MR.strings.translation_model_summary
                    }

                    translationPreferences.engine().changesIn(viewScope) { isVisible = it == engine }
                }
            }
            switchPreference {
                bindTo(translationPreferences.geminiReadsPages())
                titleRes = MR.strings.translation_gemini_reads_pages
                summaryRes = MR.strings.translation_gemini_reads_pages_summary

                translationPreferences.engine().changesIn(viewScope) { isVisible = it == TranslationEngine.GEMINI }
            }
            preference {
                titleRes = MR.strings.translation_quota
                isPersistent = false
                val quota: OpenRouterQuota = Injekt.get()
                summary = context.getString(
                    MR.strings.translation_quota_summary,
                    quota.remaining(),
                    quota.cap(),
                )
                onClick {
                    viewScope.launchIO {
                        quota.refreshCap(translationPreferences.engineApiKey(TranslationEngine.OPENROUTER).get())
                        withUIContext {
                            summary = context.getString(
                                MR.strings.translation_quota_summary,
                                quota.remaining(),
                                quota.cap(),
                            )
                        }
                    }
                }
                // The free daily allowance is OpenRouter's, not a thing every engine has.
                translationPreferences.engine().changesIn(viewScope) {
                    isVisible = it == TranslationEngine.OPENROUTER
                }
            }
        }

        preferenceCategory {
            titleRes = MR.strings.translation_languages

            listPreference(activity) {
                bindTo(translationPreferences.translateFrom())
                titleRes = MR.strings.translate_from
                entryValues = OcrLanguage.entries.map { it.name }
                entries = OcrLanguage.entries.map { TranslationLanguages.displayName(it.code) }
            }
            listPreference(activity) {
                bindTo(translationPreferences.translateTo())
                titleRes = MR.strings.translate_to
                entryValues = TranslationLanguages.targets
                entries = TranslationLanguages.targets.map { TranslationLanguages.displayName(it) }
            }
        }

        preferenceCategory {
            titleRes = MR.strings.automatic

            switchPreference {
                bindTo(translationPreferences.autoTranslateAfterDownload())
                titleRes = MR.strings.auto_translate_after_download
                summaryRes = MR.strings.auto_translate_after_download_summary
            }
            switchPreference {
                bindTo(translationPreferences.autoTranslateOnlyUnmetered())
                titleRes = MR.strings.auto_translate_only_unmetered
                summaryRes = MR.strings.auto_translate_only_unmetered_summary

                translationPreferences.autoTranslateAfterDownload().changesIn(viewScope) { isVisible = it }
            }
        }

        preferenceCategory {
            titleRes = MR.strings.translation_cache

            preference {
                titleRes = MR.strings.translation_clear_cache
                summaryRes = MR.strings.translation_clear_cache_summary
                isPersistent = false
                onClick {
                    viewScope.launchIO {
                        Injekt.get<TranslationCache>().clear()
                        withUIContext { activity?.toast(MR.strings.translation_clear_cache_done) }
                    }
                }
            }
        }
    }
}
