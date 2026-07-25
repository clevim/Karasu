package karasu.core.di

import android.app.Application
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.core.preference.AndroidPreferenceStore
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.core.storage.AndroidStorageFolderProvider
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackPreferences
import eu.kanade.tachiyomi.network.NetworkPreferences
import org.koin.dsl.module
import karasu.domain.backup.BackupPreferences
import karasu.domain.base.BasePreferences
import karasu.domain.download.DownloadPreferences
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.library.LibraryPreferences
import karasu.domain.recents.RecentsPreferences
import karasu.domain.source.SourcePreferences
import karasu.domain.storage.StoragePreferences
import karasu.domain.ui.UiPreferences
import karasu.domain.ui.settings.ReaderPreferences

fun preferenceModule(application: Application) = module {
    single<PreferenceStore> { AndroidPreferenceStore(application) }

    single { BasePreferences(get()) }

    single { SourcePreferences(get()) }

    single { TrackPreferences(get()) }

    single { UiPreferences(get()) }

    single { ReaderPreferences(get()) }

    single { RecentsPreferences(get()) }

    single { DownloadPreferences(get()) }

    single {
        NetworkPreferences(
            get(),
            BuildConfig.FLAVOR == "dev" || BuildConfig.DEBUG,
        )
    }

    single { SecurityPreferences(get()) }

    single { BackupPreferences(get()) }

    single { LibraryPreferences(get()) }

    single { KoreaderPreferences(get()) }

    single {
        PreferencesHelper(
            context = application,
            preferenceStore = get(),
        )
    }

    single {
        StoragePreferences(
            folderProvider = get<AndroidStorageFolderProvider>(),
            preferenceStore = get(),
        )
    }
}
