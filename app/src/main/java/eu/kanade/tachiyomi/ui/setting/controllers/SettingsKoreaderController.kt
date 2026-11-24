package eu.kanade.tachiyomi.ui.setting.controllers

import android.content.Context
import android.text.format.DateUtils
import android.view.View
import androidx.preference.PreferenceScreen
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.koreader.KoreaderSyncJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.bindTo
import eu.kanade.tachiyomi.ui.setting.editTextPreference
import eu.kanade.tachiyomi.ui.setting.infoPreference
import eu.kanade.tachiyomi.ui.setting.intListPreference
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
import karasu.domain.koreader.KoreaderPreferences
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import uy.kohesive.injekt.injectLazy
import eu.kanade.tachiyomi.ui.setting.summaryMRes as summaryRes
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes

class SettingsKoreaderController : SettingsLegacyController() {

    private val koreaderPreferences: KoreaderPreferences by injectLazy()
    private val koreaderApi: KoreaderApi by injectLazy()
    private val storageManager: karasu.domain.storage.StorageManager by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()

    /**
     * Leaving this screen syncs.
     *
     * Every setting here changes what belongs on the shelf, and the answer to "what belongs on the
     * shelf" is worth nothing twelve hours later — a category picked or a screen width chosen wants
     * to be acted on now, on chapters that have been downloaded for weeks. Doing it on the way out
     * rather than on each toggle means changing three settings is one sync, not three.
     */
    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        KoreaderSyncJob.startIfAutomatic(applicationContext ?: return)
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = MR.strings.koreader

        preferenceCategory {
            titleRes = MR.strings.yata_connection_section

            editTextPreference(activity) {
                bindTo(koreaderPreferences.serverUrl())
                titleRes = MR.strings.koreader_server_url
                summary = koreaderPreferences.serverUrl().get()
                    .ifBlank { context.getString(MR.strings.koreader_server_url_hint) }
                onChange {
                    koreaderApi.invalidateOriginCache()
                    val newUrl = it as String
                    summary = newUrl.ifBlank {
                        context.getString(MR.strings.koreader_server_url_hint)
                    }
                    // The periodic job's constraints and its very existence come from these settings,
                    // so it is re-registered whenever one of them changes rather than only at startup.
                    val configured = newUrl.isNotBlank() || koreaderPreferences.fallbackServerUrl().get().isNotBlank()
                    KoreaderSyncJob.setupTask(context.applicationContext, prefConfigured = configured)
                    true
                }
            }
            editTextPreference(activity) {
                bindTo(koreaderPreferences.fallbackServerUrl())
                titleRes = MR.strings.koreader_fallback_url
                summary = koreaderPreferences.fallbackServerUrl().get()
                    .ifBlank { context.getString(MR.strings.koreader_fallback_url_hint) }
                onChange {
                    koreaderApi.invalidateOriginCache()
                    val newUrl = it as String
                    summary = newUrl.ifBlank {
                        context.getString(MR.strings.koreader_fallback_url_hint)
                    }
                    val configured = newUrl.isNotBlank() || koreaderPreferences.serverUrl().get().isNotBlank()
                    KoreaderSyncJob.setupTask(context.applicationContext, prefConfigured = configured)
                    true
                }
            }
            editTextPreference(activity) {
                bindTo(koreaderPreferences.apiKey())
                titleRes = MR.strings.koreader_api_key
                summaryRes = MR.strings.koreader_api_key_summary
                onChange {
                    koreaderApi.invalidateOriginCache()
                    true
                }
            }
            preference {
                titleRes = MR.strings.koreader_test_connection
                onClick { testConnection() }
            }
        }

        preferenceCategory {
            titleRes = MR.strings.yata_companion_section

            switchPreference {
                bindTo(koreaderPreferences.syncLibrary())
                titleRes = MR.strings.yata_sync_library
                summaryRes = MR.strings.yata_sync_library_summary
            }
            switchPreference {
                bindTo(koreaderPreferences.preferServerDownloads())
                titleRes = MR.strings.yata_prefer_server_downloads
                summaryRes = MR.strings.yata_prefer_server_downloads_summary
            }
            switchPreference {
                bindTo(koreaderPreferences.acquireViaServer())
                titleRes = MR.strings.yata_acquire_via_server
                summaryRes = MR.strings.yata_acquire_via_server_summary
            }
            switchPreference {
                bindTo(koreaderPreferences.autoUploadBackups())
                titleRes = MR.strings.yata_auto_upload_backups
                summaryRes = MR.strings.yata_auto_upload_backups_summary
            }
            preference {
                titleRes = MR.strings.yata_backup_upload_now
                summaryRes = MR.strings.yata_backup_upload_summary
                onClick { uploadLatestBackup() }
            }
            preference {
                titleRes = MR.strings.yata_missing_extensions_title
                fun update(missing: Set<String>) {
                    isVisible = missing.isNotEmpty()
                    if (missing.isNotEmpty()) {
                        val sorted = missing.sorted()
                        summary = context.getString(
                            MR.strings.yata_missing_extensions_info,
                            sorted.size,
                            sorted.joinToString(", "),
                        )
                    }
                }
                update(koreaderPreferences.missingExtensions().get())
                koreaderPreferences.missingExtensions().changes()
                    .onEach { update(it) }
                    .launchIn(viewScope)
                onClick { installMissingExtensions() }
            }
            infoPreference(MR.strings.yata_translation_hint)
        }

        preferenceCategory {
            titleRes = MR.strings.koreader_what_to_send

            // Which manga get sent is decided one entry at a time from its own page, so all this
            // screen can usefully say is how many are marked.
            val markedCount = koreaderPreferences.shelfManga().get().size
            infoPreference(MR.strings.koreader_marked_manga_empty).apply {
                if (markedCount > 0) {
                    summary = context.getString(MR.strings.koreader_marked_manga_summary, markedCount)
                }
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
            titleRes = MR.strings.koreader_pages

            switchPreference {
                bindTo(koreaderPreferences.optimizePages())
                titleRes = MR.strings.koreader_optimize_pages
                summaryRes = MR.strings.koreader_optimize_pages_summary
            }
            intListPreference(activity) {
                bindTo(koreaderPreferences.deviceScreenWidth())
                titleRes = MR.strings.koreader_device_width
                summaryRes = MR.strings.koreader_device_width_summary
                entriesRes = arrayOf(
                    MR.strings.koreader_device_width_original,
                    MR.strings.koreader_device_width_600,
                    MR.strings.koreader_device_width_758,
                    MR.strings.koreader_device_width_1072,
                    MR.strings.koreader_device_width_1236,
                    MR.strings.koreader_device_width_1264,
                    MR.strings.koreader_device_width_1272,
                    MR.strings.koreader_device_width_1404,
                    MR.strings.koreader_device_width_1440,
                    MR.strings.koreader_device_width_1860,
                    MR.strings.koreader_device_width_1986,
                )
                entryValues = listOf(0, 600, 758, 1072, 1236, 1264, 1272, 1404, 1440, 1860, 1986)
            }
            switchPreference {
                bindTo(koreaderPreferences.grayscalePages())
                titleRes = MR.strings.koreader_grayscale
                summaryRes = MR.strings.koreader_grayscale_summary
            }
            switchPreference {
                bindTo(koreaderPreferences.rightToLeft())
                titleRes = MR.strings.koreader_right_to_left
                summaryRes = MR.strings.koreader_right_to_left_summary
            }
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
                    KoreaderSyncJob.setupTask(context.applicationContext, prefOnlyOverWifi = it as Boolean)
                    true
                }
            }
            preference {
                titleRes = MR.strings.koreader_sync_now
                summary = lastSyncSummary(context)
                // The job finishes minutes after the tap, long after this screen was built, so
                // the line follows the preference instead of being a snapshot of it.
                koreaderPreferences.lastSyncAt().changes()
                    .onEach { summary = lastSyncSummary(context) }
                    .launchIn(viewScope)
                onClick {
                    if (!koreaderApi.isConfigured) {
                        context.toast(MR.strings.koreader_set_url_first)
                        return@onClick
                    }
                    KoreaderSyncJob.startNow(context.applicationContext)
                    context.toast(MR.strings.koreader_sync_started)
                }
            }
        }
    }

    private fun lastSyncSummary(context: Context): String {
        val at = koreaderPreferences.lastSyncAt().get()
        if (at <= 0L) return context.getString(MR.strings.koreader_never_synced)
        val relative = DateUtils.getRelativeTimeSpanString(
            at,
            System.currentTimeMillis(),
            DateUtils.MINUTE_IN_MILLIS,
        )
        return context.getString(
            MR.strings.koreader_last_sync,
            relative,
            koreaderPreferences.lastSyncSummary().get(),
        )
    }

    private fun testConnection() {
        val appCtx = applicationContext ?: return
        if (!koreaderApi.isConfigured) {
            activity?.toast(MR.strings.koreader_set_url_first)
            return
        }
        viewScope.launchIO {
            val message = try {
                val res = koreaderApi.checkHealth()
                val route = if (res.isFallback) "Fallback" else "LAN"
                val extras = buildList {
                    if (res.health.suwayomi.configured) {
                        add(if (res.health.suwayomi.reachable) "Suwayomi ${res.health.suwayomi.version}".trim() else "Suwayomi offline")
                    }
                    if (res.health.translate.configured) {
                        add("Translate (${res.health.translate.provider})")
                    }
                }
                val suffix = if (extras.isNotEmpty()) " · ${extras.joinToString(" · ")}" else ""
                "${MR.strings.koreader_connection_ok.getString(appCtx)} (v${res.health.version} · $route · ${res.latencyMs}ms$suffix)"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appCtx.getString(
                    MR.strings.koreader_connection_failed,
                    e.message ?: e::class.simpleName.orEmpty(),
                )
            }
            withUIContext { activity?.toast(message) }
        }
    }

    private fun uploadLatestBackup() {
        val appCtx = applicationContext ?: return
        if (!koreaderApi.isConfigured) {
            activity?.toast(MR.strings.koreader_set_url_first)
            return
        }
        viewScope.launchIO {
            val message = try {
                val backupsDir = storageManager.getBackupsDirectory()
                val latest = backupsDir?.listFiles()
                    .orEmpty()
                    .filter { it.isFile && (it.name?.endsWith(".tachibk") == true || it.name?.endsWith(".proto.gz") == true) }
                    .maxByOrNull { it.lastModified() }
                if (latest == null) {
                    MR.strings.yata_backup_none_found.getString(appCtx)
                } else {
                    val info = koreaderApi.uploadBackup(latest, latest.name ?: "karasu.tachibk")
                    appCtx.getString(MR.strings.yata_backup_uploaded, info.name)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                appCtx.getString(
                    MR.strings.koreader_connection_failed,
                    e.message ?: e::class.simpleName.orEmpty(),
                )
            }
            withUIContext { activity?.toast(message) }
        }
    }

    private fun installMissingExtensions() {
        val appCtx = applicationContext ?: return
        val missingPkgs = koreaderPreferences.missingExtensionPkgs().get()
        val installedPkgs = extensionManager.installedExtensionsFlow.value
            .map { it.pkgName }
            .toSet()
        val matches = extensionManager.availableExtensionsFlow.value
            .filter { it.pkgName in missingPkgs && it.pkgName !in installedPkgs }
        if (matches.isEmpty()) {
            activity?.toast(MR.strings.yata_missing_extensions_none_available)
            return
        }
        val installScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        installScope.launch {
            matches.forEach { ext ->
                try {
                    extensionManager.installExtension(
                        ExtensionManager.ExtensionInfo(ext),
                        installScope,
                    )
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e) { "Failed to queue install for missing extension ${ext.pkgName}" }
                }
            }
        }
        activity?.toast(appCtx.getString(MR.strings.yata_missing_extensions_installing, matches.size))
    }
}
