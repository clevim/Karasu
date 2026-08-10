package karasu.core.di

import eu.kanade.tachiyomi.source.MergedSourceFallback
import eu.kanade.tachiyomi.util.chapter.MergedSourceSync
import org.koin.dsl.module
import karasu.data.category.CategoryRepositoryImpl
import karasu.data.chapter.ChapterRepositoryImpl
import karasu.data.extension.repo.ExtensionRepoRepositoryImpl
import karasu.data.history.HistoryRepositoryImpl
import karasu.data.koreader.KoreaderApi
import karasu.data.library.custom.CustomMangaRepositoryImpl
import karasu.data.manga.MangaRepositoryImpl
import karasu.data.manga.failures.MangaUpdateFailureRepositoryImpl
import karasu.data.manga.merged.MergedMangaRepositoryImpl
import karasu.data.source.browse.filter.SavedSearchRepositoryImpl
import karasu.data.track.TrackRepositoryImpl
import karasu.domain.category.CategoryRepository
import karasu.domain.category.interactor.ApplyCategoryRules
import karasu.domain.category.interactor.BuildRuleInputs
import karasu.domain.category.interactor.DeleteCategories
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.interactor.InsertCategories
import karasu.domain.category.interactor.SetMangaCategories
import karasu.domain.category.interactor.TransferCategoryRules
import karasu.domain.category.interactor.UpdateCategories
import karasu.domain.chapter.ChapterRepository
import karasu.domain.chapter.interactor.DeleteChapter
import karasu.domain.chapter.interactor.GetAvailableScanlators
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.InsertChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.extension.interactor.TrustExtension
import karasu.domain.extension.repo.ExtensionRepoRepository
import karasu.domain.extension.repo.interactor.CreateExtensionRepo
import karasu.domain.extension.repo.interactor.DeleteExtensionRepo
import karasu.domain.extension.repo.interactor.GetExtensionRepo
import karasu.domain.extension.repo.interactor.ReplaceExtensionRepo
import karasu.domain.extension.repo.interactor.UpdateExtensionRepo
import karasu.domain.history.HistoryRepository
import karasu.domain.history.interactor.GetHistory
import karasu.domain.history.interactor.UpsertHistory
import karasu.domain.koreader.interactor.PrepareShelfCbz
import karasu.domain.koreader.interactor.SyncKoreaderShelf
import karasu.domain.library.custom.CustomMangaRepository
import karasu.domain.library.custom.interactor.CreateCustomManga
import karasu.domain.library.custom.interactor.DeleteCustomManga
import karasu.domain.library.custom.interactor.GetCustomManga
import karasu.domain.library.custom.interactor.RelinkCustomManga
import karasu.domain.manga.MangaRepository
import karasu.domain.manga.failures.MangaUpdateFailureRepository
import karasu.domain.manga.failures.ReadFailures
import karasu.domain.manga.failures.interactor.GetBrokenSources
import karasu.domain.manga.failures.interactor.UpdateFailures
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.manga.interval.FetchInterval
import karasu.domain.manga.interval.GetReleaseSchedule
import karasu.domain.manga.interval.RecalculateReleaseEstimates
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.InsertManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.manga.merged.MergedMangaRepository
import karasu.domain.manga.merged.interactor.MergedSourceHealth
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.recents.interactor.GetRecents
import karasu.domain.source.browse.filter.FilterSerializer
import karasu.domain.source.browse.filter.SavedSearchRepository
import karasu.domain.source.browse.filter.interactor.DeleteSavedSearch
import karasu.domain.source.browse.filter.interactor.GetSavedSearch
import karasu.domain.source.browse.filter.interactor.InsertSavedSearch
import karasu.domain.track.TrackRepository
import karasu.domain.track.interactor.DeleteTrack
import karasu.domain.track.interactor.GetTrack
import karasu.domain.track.interactor.InsertTrack

fun domainModule() = module {
    factory { TrustExtension(get(), get()) }

    single { KoreaderApi(get(), get()) }
    factory { BuildRuleInputs(get(), get(), get(), get(), get(), get(), get()) }
    factory { PrepareShelfCbz(get(), get()) }
    factory { SyncKoreaderShelf(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    single<CategoryRepository> { CategoryRepositoryImpl(get()) }
    factory { ApplyCategoryRules(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    factory { DeleteCategories(get()) }
    factory { GetCategories(get()) }
    factory { InsertCategories(get()) }
    factory { UpdateCategories(get()) }
    factory { TransferCategoryRules(get(), get()) }

    single<ExtensionRepoRepository> { ExtensionRepoRepositoryImpl(get()) }
    factory { CreateExtensionRepo(get()) }
    factory { DeleteExtensionRepo(get()) }
    factory { GetExtensionRepo(get()) }
    factory { ReplaceExtensionRepo(get()) }
    factory { UpdateExtensionRepo(get(), get()) }

    single<CustomMangaRepository> { CustomMangaRepositoryImpl(get()) }
    factory { CreateCustomManga(get()) }
    factory { DeleteCustomManga(get()) }
    factory { GetCustomManga(get()) }
    factory { RelinkCustomManga(get()) }

    single<MangaRepository> { MangaRepositoryImpl(get()) }
    factory { GetManga(get()) }
    factory { GetLibraryManga(get()) }
    factory { InsertManga(get()) }
    factory { UpdateManga(get()) }

    single<MangaUpdateFailureRepository> { MangaUpdateFailureRepositoryImpl(get()) }
    factory { UpdateFailures(get()) }
    factory { FetchInterval(get()) }
    factory { GetReleaseSchedule(get(), get()) }
    factory { RecalculateReleaseEstimates(get(), get(), get()) }
    // In-memory, so every reader and the broken-sources screen have to be looking at the same one.
    single { ReadFailures() }
    factory { GetBrokenSources(get(), get(), get(), get(), get()) }

    single<MergedMangaRepository> { MergedMangaRepositoryImpl(get()) }
    // Both hold a cache that only pays off — and, for MergedSources, only invalidates correctly —
    // when every caller shares the one instance.
    single { MergedSources(get()) }
    single { MergedSourceFallback(get(), get(), get(), get()) }
    // Holds what the last sync learned about each merge, so it can't be per-injection either.
    single { MergedSourceHealth(get(), get(), get(), get()) }
    factory { MergedSourceSync(get(), get(), get(), get(), get()) }

    factory { SetMangaCategories(get()) }

    single<ChapterRepository> { ChapterRepositoryImpl(get()) }
    factory { DeleteChapter(get()) }
    factory { GetAvailableScanlators(get()) }
    factory { GetChapter(get(), get(), get(), get()) }
    factory { InsertChapter(get()) }
    factory { UpdateChapter(get()) }

    single<HistoryRepository> { HistoryRepositoryImpl(get()) }
    factory { GetHistory(get()) }
    factory { UpsertHistory(get()) }

    factory { GetRecents(get(), get()) }

    single<TrackRepository> { TrackRepositoryImpl(get()) }
    factory { DeleteTrack(get()) }
    factory { GetTrack(get()) }
    factory { InsertTrack(get()) }

    single<SavedSearchRepository> { SavedSearchRepositoryImpl(get()) }
    factory { DeleteSavedSearch(get()) }
    factory { GetSavedSearch(get()) }
    factory { InsertSavedSearch(get()) }
    factory { FilterSerializer() }
}
