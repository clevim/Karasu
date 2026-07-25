package eu.kanade.tachiyomi.ui.setting.controllers

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.koreader.KoreaderSyncJob
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.bindTo
import eu.kanade.tachiyomi.ui.setting.editTextPreference
import eu.kanade.tachiyomi.ui.setting.infoPreference
import eu.kanade.tachiyomi.ui.setting.intListPreference
import eu.kanade.tachiyomi.ui.setting.multiSelectListPreferenceMat
import eu.kanade.tachiyomi.ui.setting.onChange
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import karasu.data.koreader.KoreaderApi
import karasu.domain.category.interactor.GetCategories
import karasu.domain.koreader.KoreaderPreferences
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.ui.setting.summaryMRes as summaryRes
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

class SettingsKoreaderController : SettingsLegacyController() {

    private val koreaderPreferences: KoreaderPreferences by injectLazy()
    private val koreaderApi: KoreaderApi by injectLazy()
    private val getCategories: GetCategories by injectLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = MR.strings.koreader

        editTextPreference(activity) {
            bindTo(koreaderPreferences.serverUrl())
            titleRes = MR.strings.koreader_server_url
            summary = koreaderPreferences.serverUrl().get()
                .ifBlank { context.getString(MR.strings.koreader_server_url_hint) }
            onChange {
                summary = (it as String).ifBlank {
                    context.getString(MR.strings.koreader_server_url_hint)
                }
                // The periodic job's constraints and its very existence come from these settings,
                // so it is re-registered whenever one of them changes rather than only at startup.
                KoreaderSyncJob.setupTask(context.applicationContext)
                true
            }
        }
        editTextPreference(activity) {
            bindTo(koreaderPreferences.apiKey())
            titleRes = MR.strings.koreader_api_key
            summaryRes = MR.strings.koreader_api_key_summary
        }
        preference {
            titleRes = MR.strings.koreader_test_connection
            onClick { testConnection() }
        }

        preferenceCategory {
            titleRes = MR.strings.koreader_what_to_send

            // ponytail: blocking, like every other category picker in settings. The whole
            // preference tree is built synchronously, so loading this asynchronously would mean
            // rebuilding the screen around a state holder for one small select on screen open.
            val categories = listOf(Category.createDefault(context)) +
                runBlocking { getCategories.await() }

            multiSelectListPreferenceMat(activity) {
                bindTo(koreaderPreferences.syncCategories())
                titleRes = MR.strings.categories
                summaryRes = MR.strings.koreader_categories_summary
                entries = categories.map { it.name }
                entryValues = categories.map { it.id.toString() }
                noSelectionRes = MR.strings.none
            }
            intListPreference(activity) {
                bindTo(koreaderPreferences.chaptersPerManga())
                titleRes = MR.strings.koreader_chapters_per_manga
                entries = listOf(1, 2, 3, 5, 10).map { it.toString() }
                entryValues = listOf(1, 2, 3, 5, 10)
            }
            intListPreference(activity) {
                bindTo(koreaderPreferences.maxManga())
                titleRes = MR.strings.koreader_max_manga
                entries = listOf(5, 10, 20, 50).map { it.toString() }
                entryValues = listOf(5, 10, 20, 50)
            }
            preference {
                titleRes = MR.strings.koreader_filter
                summaryRes = MR.strings.koreader_filter_summary
                onClick {
                    router.pushController(KoreaderFilterController().withFadeTransaction())
                }
            }
            infoPreference(MR.strings.koreader_needs_cbz)
        }

        preferenceCategory {
            titleRes = MR.strings.koreader_reading

            switchPreference {
                bindTo(koreaderPreferences.markReadFromDevice())
                titleRes = MR.strings.koreader_mark_read
                summaryRes = MR.strings.koreader_mark_read_summary
            }
        }

        preferenceCategory {
            titleRes = MR.strings.koreader_sync_section

            intListPreference(activity) {
                bindTo(koreaderPreferences.syncInterval())
                titleRes = MR.strings.koreader_sync_interval
                entriesRes = arrayOf(
                    MR.strings.manual,
                    MR.strings.every_6_hours,
                    MR.strings.every_12_hours,
                    MR.strings.daily,
                    MR.strings.weekly,
                )
                entryValues = listOf(0, 6, 12, 24, 168)
                onChange {
                    KoreaderSyncJob.setupTask(context.applicationContext, it as Int)
                    true
                }
            }
            switchPreference {
                bindTo(koreaderPreferences.onlyOverWifi())
                titleRes = MR.strings.koreader_only_over_wifi
                onChange {
                    KoreaderSyncJob.setupTask(context.applicationContext)
                    true
                }
            }
            preference {
                titleRes = MR.strings.koreader_sync_now
                onClick {
                    if (koreaderPreferences.serverUrl().get().isBlank()) {
                        context.toast(MR.strings.koreader_set_url_first)
                        return@onClick
                    }
                    KoreaderSyncJob.startNow(context.applicationContext)
                    context.toast(MR.strings.koreader_sync_started)
                }
            }
        }
    }

    private fun testConnection() {
        if (koreaderPreferences.serverUrl().get().isBlank()) {
            activity?.toast(MR.strings.koreader_set_url_first)
            return
        }
        viewScope.launchIO {
            val message = try {
                koreaderApi.check()
                MR.strings.koreader_connection_ok.getString(activity!!)
            } catch (e: Exception) {
                activity!!.getString(
                    MR.strings.koreader_connection_failed,
                    e.message ?: e::class.simpleName.orEmpty(),
                )
            }
            withUIContext { activity?.toast(message) }
        }
    }
}
