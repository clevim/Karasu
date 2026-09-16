package eu.kanade.tachiyomi.ui.setting.controllers

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.R
import karasu.domain.ui.UiPreferences
import karasu.i18n.MR
import karasu.util.lang.getString
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.migration.AutoMigrateJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.PreferenceKeys
import eu.kanade.tachiyomi.data.preference.changesIn
import eu.kanade.tachiyomi.data.updater.AppDownloadInstallJob
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.ExtensionUpdateJob
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.migration.MigrationController
import eu.kanade.tachiyomi.ui.setting.SettingsLegacyController
import eu.kanade.tachiyomi.ui.setting.bindTo
import eu.kanade.tachiyomi.ui.setting.defaultValue
import eu.kanade.tachiyomi.ui.setting.infoPreference
import eu.kanade.tachiyomi.ui.setting.intListPreference
import eu.kanade.tachiyomi.ui.setting.listPreference
import eu.kanade.tachiyomi.ui.setting.onChange
import eu.kanade.tachiyomi.ui.setting.onClick
import eu.kanade.tachiyomi.ui.setting.preference
import eu.kanade.tachiyomi.ui.setting.preferenceCategory
import eu.kanade.tachiyomi.ui.setting.summaryMRes as summaryRes
import eu.kanade.tachiyomi.ui.setting.switchPreference
import eu.kanade.tachiyomi.ui.setting.titleMRes as titleRes
import eu.kanade.tachiyomi.util.lang.addBetaTag
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.formatHourOfDay
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale
import uy.kohesive.injekt.injectLazy
import karasu.domain.base.BasePreferences.ExtensionInstaller
import karasu.presentation.extension.repo.ExtensionRepoController

class SettingsBrowseController : SettingsLegacyController() {

    /**
     * Languages that actually have an installed source, sorted by name.
     *
     * Offering every language the app knows would let someone schedule a nightly pass that can
     * never match anything, and the failure would be silent.
     */
    private val migrationLanguages: List<String>
        get() = sourceManager.getCatalogueSources()
            .map { it.lang }
            .distinct()
            .sortedBy { LocaleHelper.getDisplayName(it) }

    val sourceManager: SourceManager by injectLazy()
    var updatedExtNotifPref: SwitchPreferenceCompat? = null

    private val uiPreferences: UiPreferences by injectLazy()

    override fun setupPreferenceScreen(screen: PreferenceScreen) = screen.apply {
        titleRes = MR.strings.browse

        preferenceCategory {
            switchPreference {
                bindTo(preferences.hideInLibraryItems())
                titleRes = MR.strings.hide_in_library_items
            }
        }

        preferenceCategory {
            titleRes = MR.strings.extensions
            preference {
                title = context.getString(MR.strings.source_repos).addBetaTag(context)
                onClick { router.pushController(ExtensionRepoController().withFadeTransaction()) }
            }
            switchPreference {
                key = PreferenceKeys.automaticExtUpdates
                titleRes = MR.strings.check_for_extension_updates
                defaultValue = true

                onChange {
                    it as Boolean
                    ExtensionUpdateJob.setupTask(context, it)
                    true
                }
            }
            if (ExtensionManager.canAutoInstallUpdates()) {
                val intPref = intListPreference(activity) {
                    key = PreferenceKeys.autoUpdateExtensions
                    titleRes = MR.strings.auto_update_extensions
                    entryRange = 0..2
                    entriesRes = arrayOf(
                        MR.strings.over_any_network,
                        MR.strings.over_wifi_only,
                        MR.strings.dont_auto_update,
                    )
                    defaultValue = AppDownloadInstallJob.ONLY_ON_UNMETERED
                }
                val infoPref = if (basePreferences.extensionInstaller().get() != ExtensionInstaller.SHIZUKU) {
                    infoPreference(MR.strings.some_extensions_may_not_update)
                } else {
                    null
                }
                val switchPref = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    switchPreference {
                        key = "notify_ext_updated"
                        isPersistent = false
                        titleRes = MR.strings.notify_extension_updated
                        isChecked = Notifications.isNotificationChannelEnabled(
                            context,
                            Notifications.CHANNEL_EXT_UPDATED,
                        )
                        updatedExtNotifPref = this
                        onChange {
                            false
                        }
                        onClick {
                            val intent =
                                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                    putExtra(Settings.EXTRA_APP_PACKAGE, BuildConfig.APPLICATION_ID)
                                    putExtra(
                                        Settings.EXTRA_CHANNEL_ID,
                                        Notifications.CHANNEL_EXT_UPDATED,
                                    )
                                }
                            startActivity(intent)
                        }
                    }
                } else {
                    null
                }
                preferences.automaticExtUpdates().changesIn(viewScope) { value ->
                    arrayOf(intPref, infoPref, switchPref).forEach { it?.isVisible = value }
                }
            }
        }

        preferenceCategory {
            titleRes = MR.strings.pref_global_search
            switchPreference {
                key = PreferenceKeys.onlySearchPinned
                titleRes = MR.strings.only_search_pinned_when
            }
        }

        preferenceCategory {
            titleRes = MR.strings.migration
            // Only show this if someone has mass migrated manga once

            preference {
                titleRes = MR.strings.source_migration
                onClick { router.pushController(MigrationController().withFadeTransaction()) }
            }
            if (preferences.skipPreMigration().get() || preferences.migrationSources()
                .isSet()
            ) {
                switchPreference {
                    key = PreferenceKeys.skipPreMigration
                    titleRes = MR.strings.skip_pre_migration
                    summaryRes = MR.strings.use_last_saved_migration_preferences
                    defaultValue = false
                }
            }
            preference {
                key = "match_pinned_sources"
                titleRes = MR.strings.match_pinned_sources
                summaryRes = MR.strings.only_enable_pinned_for_migration
                onClick {
                    val ogSources = preferences.migrationSources().get()
                    val pinnedSources =
                        preferences.pinnedCatalogues().get().joinToString("/")
                    preferences.migrationSources().set(pinnedSources)
                    (activity as? MainActivity)?.setUndoSnackBar(
                        view?.snack(
                            MR.strings.migration_sources_changed,
                        ) {
                            setAction(MR.strings.undo) {
                                preferences.migrationSources().set(ogSources)
                            }
                        },
                    )
                }
            }

            preference {
                key = "match_enabled_sources"
                titleRes = MR.strings.match_enabled_sources
                summaryRes = MR.strings.only_enable_enabled_for_migration
                onClick {
                    val ogSources = preferences.migrationSources().get()
                    val languages = preferences.enabledLanguages().get()
                    val hiddenCatalogues = preferences.hiddenSources().get()
                    val enabledSources =
                        sourceManager.getCatalogueSources().filter { it.lang in languages }
                            .filterNot { it.id.toString() in hiddenCatalogues }
                            .sortedBy { "(${it.lang}) ${it.name}" }
                            .joinToString("/") { it.id.toString() }
                    preferences.migrationSources().set(enabledSources)
                    (activity as? MainActivity)?.setUndoSnackBar(
                        view?.snack(
                            MR.strings.migration_sources_changed,
                        ) {
                            setAction(MR.strings.undo) {
                                preferences.migrationSources().set(ogSources)
                            }
                        },
                    )
                }
            }

            intListPreference(activity) {
                bindTo(preferences.autoMigrateHour())
                titleRes = MR.strings.auto_migration
                summaryRes = MR.strings.auto_migration_summary
                entries = listOf(context.getString(MR.strings.auto_migration_off)) +
                    MIGRATE_HOURS.map { context.formatHourOfDay(it) }
                entryValues = listOf(-1) + MIGRATE_HOURS
                defaultValue = -1

                onChange {
                    // Read back after the write, like the digest hour: the schedule is rebuilt
                    // from the stored value, not from the one being chosen.
                    viewScope.launchUI { AutoMigrateJob.setupTask(context) }
                    true
                }
            }

            intListPreference(activity) {
                bindTo(preferences.autoMigrateDay())
                titleRes = MR.strings.auto_migration_day
                entries = listOf(context.getString(MR.strings.auto_migration_daily)) +
                    DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL, Locale.getDefault()) }
                entryValues = listOf(AutoMigrateJob.DAILY) + DayOfWeek.entries.map { it.value }
                defaultValue = AutoMigrateJob.DAILY

                preferences.autoMigrateHour().changesIn(viewScope) { isVisible = it >= 0 }

                onChange {
                    viewScope.launchUI { AutoMigrateJob.setupTask(context) }
                    true
                }
            }

            listPreference(activity) {
                bindTo(preferences.autoMigratePrimaryLang())
                titleRes = MR.strings.auto_migration_languages
                summaryRes = MR.strings.auto_migration_primary_language
                entries = listOf(context.getString(MR.strings.auto_migration_off)) +
                    migrationLanguages.map { LocaleHelper.getDisplayName(it) }
                entryValues = listOf("") + migrationLanguages
                defaultValue = ""

                preferences.autoMigrateHour().changesIn(viewScope) { isVisible = it >= 0 }
            }

            listPreference(activity) {
                bindTo(preferences.autoMigrateSecondaryLang())
                titleRes = MR.strings.auto_migration_secondary_language
                entries = listOf(context.getString(MR.strings.auto_migration_off)) +
                    migrationLanguages.map { LocaleHelper.getDisplayName(it) }
                entryValues = listOf("") + migrationLanguages
                defaultValue = ""

                // Nothing to fall back to before a primary is picked.
                preferences.autoMigratePrimaryLang()
                    .changesIn(viewScope) { isVisible = it.isNotEmpty() && preferences.autoMigrateHour().get() >= 0 }
            }

            infoPreference(MR.strings.you_can_migrate_in_library)
        }
        
        preferenceCategory {
            titleRes = MR.strings.sources

            switchPreference {
                bindTo(uiPreferences.enableSourceSwipeAction())
                titleRes = MR.strings.enable_source_swipe_action
            }
        }

        preferenceCategory {
            titleRes = MR.strings.nsfw_sources

            switchPreference {
                key = PreferenceKeys.showNsfwSource
                titleRes = MR.strings.show_in_sources_and_extensions
                summaryRes = MR.strings.requires_app_restart
                defaultValue = true
            }
            infoPreference(MR.strings.does_not_prevent_unofficial_nsfw)
        }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        updatedExtNotifPref?.isChecked = Notifications.isNotificationChannelEnabled(activity, Notifications.CHANNEL_EXT_UPDATED)
    }

    private companion object {
        /** Overnight-heavy: the pass is a burst of searches nobody should be waiting on. */
        val MIGRATE_HOURS = listOf(0, 3, 6, 9, 12, 18, 21)
    }
}
