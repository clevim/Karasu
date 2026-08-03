package eu.kanade.tachiyomi.ui.manga

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toFile
import co.touchlab.kermit.Logger
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.hippo.unifile.UniFile
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.History
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.bookmarkedFilter
import eu.kanade.tachiyomi.data.database.models.chapterOrder
import eu.kanade.tachiyomi.data.database.models.downloadedFilter
import eu.kanade.tachiyomi.data.database.models.prepareCoverUpdate
import eu.kanade.tachiyomi.data.database.models.readFilter
import eu.kanade.tachiyomi.data.database.models.removeCover
import eu.kanade.tachiyomi.data.database.models.sortDescending
import eu.kanade.tachiyomi.data.database.models.updateCoverLastModified
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.download.model.DownloadQueue
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.EnhancedTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.SourceNotFoundException
import eu.kanade.tachiyomi.source.getExtension
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter
import eu.kanade.tachiyomi.ui.manga.chapter.ChapterItem
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.ui.manga.track.TrackingBottomSheet
import eu.kanade.tachiyomi.ui.security.SecureActivityDelegate
import eu.kanade.tachiyomi.util.chapter.ChapterFilter
import eu.kanade.tachiyomi.util.chapter.MergedSourceSync
import eu.kanade.tachiyomi.util.chapter.ChapterSort
import eu.kanade.tachiyomi.util.chapter.ChapterUtil
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithTrackServiceTwoWay
import eu.kanade.tachiyomi.util.chapter.updateTrackChapterMarkedAsRead
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.lang.trimOrNull
import eu.kanade.tachiyomi.util.manga.MangaShortcutManager
import eu.kanade.tachiyomi.util.manga.MangaUtil
import eu.kanade.tachiyomi.util.shouldDownloadNewChapters
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNonCancellableIO
import eu.kanade.tachiyomi.util.system.launchNow
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.widget.TriStateCheckBox
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import karasu.domain.category.interactor.ApplyCategoryRules
import karasu.domain.category.interactor.GetCategories
import karasu.domain.chapter.interactor.ChapterGap
import karasu.domain.chapter.interactor.findChapterGaps
import karasu.domain.chapter.interactor.GetAvailableScanlators
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.history.interactor.GetHistory
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.library.custom.model.CustomMangaInfo
import karasu.domain.manga.failures.interactor.UpdateFailures
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.manga.merged.interactor.MergeHealth
import karasu.domain.manga.merged.interactor.MergedSourceHealth
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.manga.models.MangaUpdate
import karasu.domain.manga.models.MergedMangaSource
import karasu.domain.manga.models.cover
import karasu.domain.storage.StorageManager
import karasu.domain.track.interactor.DeleteTrack
import karasu.domain.track.interactor.GetTrack
import karasu.domain.track.interactor.InsertTrack
import karasu.i18n.MR
import karasu.util.lang.getString

class MangaDetailsPresenter(
    val mangaId: Long,
    val sourceManager: SourceManager = Injekt.get(),
    val preferences: PreferencesHelper = Injekt.get(),
    val coverCache: CoverCache = Injekt.get(),
    private val downloadManager: DownloadManager = Injekt.get(),
    private val chapterFilter: ChapterFilter = Injekt.get(),
    private val storageManager: StorageManager = Injekt.get(),
) : BaseCoroutinePresenter<MangaDetailsController>(),
    DownloadQueue.Listener {
    private val getAvailableScanlators: GetAvailableScanlators by injectLazy()
    private val getCategories: GetCategories by injectLazy()
    private val getChapter: GetChapter by injectLazy()
    private val mergedSourceSync: MergedSourceSync by injectLazy()
    private val mergedSources: MergedSources by injectLazy()
    private val mergedSourceHealth: MergedSourceHealth by injectLazy()
    private val applyCategoryRules: ApplyCategoryRules by injectLazy()
    private val updateFailures: UpdateFailures by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val updateChapter: UpdateChapter by injectLazy()
    private val updateManga: UpdateManga by injectLazy()
    private val deleteTrack: DeleteTrack by injectLazy()
    private val getTrack: GetTrack by injectLazy()
    private val insertTrack: InsertTrack by injectLazy()
    private val getHistory: GetHistory by injectLazy()

    private val networkPreferences: NetworkPreferences by injectLazy()
    private val koreaderPreferences: KoreaderPreferences by injectLazy()

//    private val currentMangaInternal: MutableStateFlow<Manga?> = MutableStateFlow(null)
//    val currentManga get() = currentMangaInternal.asStateFlow()

    lateinit var manga: Manga
    fun isMangaLateInitInitialized() = ::manga.isInitialized

    private val customMangaManager: CustomMangaManager by injectLazy()
    private val mangaShortcutManager: MangaShortcutManager by injectLazy()

    val source: Source by lazy { sourceManager.getOrStub(manga.source) }

    private lateinit var chapterSort: ChapterSort
    val extension by lazy { (source as? HttpSource)?.getExtension() }

    var isLockedFromSearch = false
    var hasRequested = false
    var isLoading = false
    var scrollType = 0

    private val loggedServices by lazy { Injekt.get<TrackManager>().services.filter { it.isLogged } }
    private var tracks = emptyList<Track>()

    var trackList: List<TrackItem> = emptyList()

    var chapters: List<ChapterItem> = emptyList()
        private set

    var allChapters: List<ChapterItem> = emptyList()
        private set

    var allHistory: List<History> = emptyList()
        private set

    val headerItem: MangaHeaderItem by lazy { MangaHeaderItem(mangaId, view?.fromCatalogue == true)}
    var tabletChapterHeaderItem: MangaHeaderItem? = null
        get() {
            when (view?.isTablet) {
                true -> if (field == null) {
                    field = MangaHeaderItem(mangaId, false).apply {
                        isChapterHeader = true
                    }
                }
                else -> if (field != null) {
                    field = null
                }
            }
            return field
        }
        private set

    var allChapterScanlators: Set<String> = emptySet()

    override val progressJobs: MutableMap<Download, Job> = mutableMapOf()
    override val queueListenerScope get() = presenterScope

    override fun onCreate() {
        val controller = view ?: return

        isLockedFromSearch = controller.shouldLockIfNeeded && SecureActivityDelegate.shouldBeLocked()
        if (!::manga.isInitialized) runBlocking { refreshMangaFromDb() }
        syncData()

        presenterScope.launchUI {
            downloadManager.statusFlow()
                .filter { it.manga.id == mangaId }
                .catch { error -> Logger.e(error) }
                .collect(::onStatusChange)
        }
        presenterScope.launchUI {
            downloadManager.progressFlow()
                .filter { it.manga.id == mangaId }
                .catch { error -> Logger.e(error) }
                .collect(::onQueueUpdate)
        }
        presenterScope.launchIO {
            downloadManager.queueState.collectLatest(::onQueueUpdate)
        }

        runBlocking {
            tracks = getTrack.awaitAllByMangaId(mangaId)
        }
    }

    /**
     * onCreate but executed after UI layout is ready otherwise it'd only show blank screen
     */
    fun onCreateLate() {
        val controller = view ?: return

        LibraryUpdateJob.updateFlow
            .filter { it == mangaId }
            .onEach { onUpdateManga() }
            .launchIn(presenterScope)

        val fetchMangaNeeded = !manga.initialized
        val fetchChaptersNeeded = runBlocking { getChaptersNow() }.isEmpty()

        presenterScope.launch {
            isLoading = true
            withUIContext {
                controller.updateHeader()
            }
            if (fetchMangaNeeded || fetchChaptersNeeded) {
                fetchMangaUpdateFromSource(
                    fetchDetails = fetchMangaNeeded,
                    fetchChapters = fetchChaptersNeeded,
                    manualFetch = false,
                )
            }
            isLoading = false
            withUIContext {
                controller.updateChapters()
            }

            setTrackItems()
        }

        refreshTracking(false)
    }

    fun fetchChapters(andTracking: Boolean = true) {
        presenterScope.launch {
            getChapters()
            if (andTracking) fetchTracks()
            withUIContext { view?.updateChapters() }
            getHistory()
        }
    }

    fun setCurrentManga(manga: Manga?) {
//        currentMangaInternal.update { manga }
        this.manga = manga!!
    }

    // TODO: Use flow to "sync" data instead
    fun syncData() {
        chapterSort = ChapterSort(manga, chapterFilter, preferences)
        headerItem.apply {
            isTablet = view?.isTablet == true
            isLocked = isLockedFromSearch
        }
    }

    suspend fun getChaptersNow(): List<ChapterItem> {
        getChapters()
        return chapters
    }

    /** Which source a chapter row came from. */
    data class ChapterSource(val name: String, val lang: String)

    /**
     * Source of each manga row this chapter list draws from, keyed by that row's id — the
     * manga's own row included.
     *
     * A merged chapter keeps the id of the row it was synced under, so this is what lets the
     * chapter list say which source a row this manga didn't fetch itself came from. Empty for
     * the usual case of a manga with no merges, so nothing here costs anything there.
     */
    var chapterSources: Map<Long, ChapterSource> = emptyMap()
        private set

    /**
     * True when the merged sources speak a language the manga's own source doesn't.
     *
     * Then chapter 5 appears once per language, and a flag on every row — not just the merged
     * ones — is the only thing that says which of them is which.
     */
    var showChapterLanguages = false
        private set

    /**
     * The manga row each chapter can come from, keyed by that row's id.
     *
     * Same keys as [chapterSources]: the merged source's own row is where a borrowed chapter's
     * url — and therefore the source that can open it — lives.
     */
    private var chapterMangas: Map<Long, Manga> = emptyMap()

    /** The manga row [chapter] belongs to: this one, or the merged source's it came from. */
    fun chapterManga(chapter: Chapter): Manga = chapter.manga_id?.let { chapterMangas[it] } ?: manga

    /** The source that can serve [chapter]. This manga's source would build a url it never had. */
    fun chapterSource(chapter: Chapter): HttpSource? =
        sourceManager.get(chapterManga(chapter).source) as? HttpSource

    private suspend fun refreshChapterSources() {
        if (!mergedSources.hasMerges(mangaId)) {
            chapterSources = emptyMap()
            chapterMangas = emptyMap()
            showChapterLanguages = false
            return
        }

        val own = sourceManager.getOrStub(manga.source)
        val mangas = mutableMapOf(mangaId to manga)
        val sources = buildMap {
            put(mangaId, ChapterSource(own.name, own.lang))
            mergedSources.await(mangaId).forEach { merge ->
                val child = getManga.awaitByUrlAndSource(merge.url, merge.source) ?: return@forEach
                val childId = child.id ?: return@forEach
                val source = sourceManager.getOrStub(merge.source)
                put(childId, ChapterSource(source.name, source.lang))
                mangas[childId] = child
            }
        }
        chapterSources = sources
        chapterMangas = mangas
        showChapterLanguages = sources.values.distinctBy { it.lang }.size > 1
    }

    private suspend fun getChapters(queue: List<Download> = downloadManager.queueState.value) {
        refreshChapterSources()
        val chapters = getChapter.awaitAll(mangaId, isScanlatorFiltered()).map { it.toModel() }
        allChapters = if (!isScanlatorFiltered()) chapters else getChapter.awaitAll(mangaId, false).map { it.toModel() }

        // Find downloaded chapters
        setDownloadedChapters(chapters, queue)
        allChapterScanlators = allChapters.mapNotNull { it.chapter.scanlator }.toSet()
        chapterGaps = findChapterGaps(allChapters.map { it.chapter.chapter_number })

        this.chapters = applyChapterFilters(chapters)
    }

    private suspend fun getHistory() {
        allHistory = getHistory.awaitAllByMangaId(mangaId)
    }

    /**
     * Finds and assigns the list of downloaded chapters.
     *
     * @param chapters the list of chapter from the database.
     */
    private fun setDownloadedChapters(chapters: List<ChapterItem>, queue: List<Download>) {
        for (chapter in chapters) {
            if (downloadManager.isChapterDownloaded(chapter, manga)) {
                chapter.status = Download.State.DOWNLOADED
            } else if (queue.isNotEmpty()) {
                chapter.status = queue.find { it.chapter.id == chapter.id }
                    ?.status ?: Download.State.default
            }
        }
    }

    /**
     * Converts a chapter from the database to an extended model, allowing to store new fields.
     */
    private fun Chapter.toModel(): ChapterItem {
        // Create the model object.
        val model = ChapterItem(this, manga)
        model.isLocked = isLockedFromSearch

        // Find an active download for this chapter.
        val download = downloadManager.queueState.value.find { it.chapter.id == id }

        if (download != null) {
            // If there's an active download, assign it.
            model.download = download
        }
        return model
    }

    /**
     * Whether the sorting method is descending or ascending.
     */
    fun sortDescending() = manga.sortDescending(preferences)

    fun sortingOrder() = manga.chapterOrder(preferences)

    /**
     * Applies the view filters to the list of chapters obtained from the database.
     * @param chapterList the list of chapters from the database
     * @return an observable of the list of chapters filtered and sorted.
     */
    private fun applyChapterFilters(chapterList: List<ChapterItem>): List<ChapterItem> {
        if (isLockedFromSearch) {
            return chapterList
        }
        getScrollType(chapterList)
        return chapterSort.getChaptersSorted(chapterList)
    }

    fun getChapterUrl(chapter: Chapter): String? {
        val source = chapterSource(chapter) ?: return null
        val chapterUrl = try { source.getChapterUrl(chapter) } catch (_: Exception) { null }
        return chapterUrl.takeIf { !it.isNullOrBlank() }
            ?: try { source.getChapterUrl(chapterManga(chapter), chapter) } catch (_: Exception) { null }
    }

    private fun getScrollType(chapters: List<ChapterItem>) {
        scrollType = when {
            ChapterUtil.hasMultipleVolumes(chapters) -> MULTIPLE_VOLUMES
            ChapterUtil.hasMultipleSeasons(chapters) -> MULTIPLE_SEASONS
            ChapterUtil.hasTensOfChapters(chapters) -> TENS_OF_CHAPTERS
            else -> 0
        }
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): ChapterItem? {
        return chapterSort.getNextUnreadChapter(chapters)
    }

    /**
     * Chapter numbers absent from this manga's whole list, recomputed whenever the list is.
     *
     * Taken from [allChapters] rather than [chapters]: with "unread only" on, everything already
     * read would read as a hole. Merged sources are already folded in by then, so a number here is
     * one that nothing you have carries — not one the primary source happens to be short of.
     */
    var chapterGaps: List<ChapterGap> = emptyList()
        private set

    /**
     * The line under the chapter count: the active filters, and how much the list is short of.
     *
     * Shares the filter line rather than adding a row of its own, because for most manga there are
     * no gaps and an empty row would be permanent furniture for a rare event.
     */
    fun chapterListSubtitle(context: Context): String {
        val filters = currentFilters()
        val missing = chapterGaps.sumOf { it.size }
        if (missing == 0) return filters
        val gapText = context.getString(MR.plurals.missing_chapters_count, missing, missing)
        return if (filters.isBlank()) gapText else "$filters • $gapText"
    }

    fun anyRead(): Boolean = allChapters.any { it.read }
    fun hasBookmark(): Boolean = allChapters.any { it.bookmark }
    fun hasDownloads(): Boolean = allChapters.any { it.isDownloaded }

    fun getUnreadChaptersSorted() =
        chapters.filter { !it.read && it.status == Download.State.NOT_DOWNLOADED }.distinctBy { it.name }
            .sortedWith(chapterSort.sortComparator(true))

    fun startDownloadingNow(chapter: Chapter) {
        downloadManager.startDownloadNow(chapter)
    }

    /**
     * Downloads the given list of chapters with the manager.
     * @param chapters the list of chapters to download.
     */
    fun downloadChapters(chapters: List<ChapterItem>) {
        downloadManager.downloadChapters(manga, chapters.filter { !it.isDownloaded })
    }

    /**
     * Deletes the given list of chapter.
     * @param chapter the chapter to delete.
     */
    fun deleteChapter(chapter: ChapterItem) {
        this.chapters.find { it.id == chapter.id }?.apply {
            if (chapter.chapter.bookmark && !preferences.removeBookmarkedChapters().get()) return@apply
            status = Download.State.NOT_DOWNLOADED
            download = null
        }

        view?.updateChapters()

        downloadManager.deleteChapters(listOf(chapter), manga, source, true)
    }

    /**
     * Deletes the given list of chapter.
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<ChapterItem>, update: Boolean = true, isEverything: Boolean = false) {
        chapters.forEach { chapter ->
            this.chapters.find { it.id == chapter.id }?.apply {
                if (chapter.chapter.bookmark && !preferences.removeBookmarkedChapters().get() && !isEverything) return@apply
                status = Download.State.NOT_DOWNLOADED
                download = null
            }
        }

        if (update) view?.updateChapters()

        if (isEverything) {
            downloadManager.deleteManga(manga, source)
        } else {
            downloadManager.deleteChapters(chapters, manga, source)
        }
    }

    suspend fun refreshMangaFromDb(): Manga {
        val dbManga = getManga.awaitById(mangaId)!!
        setCurrentManga(dbManga)
        return dbManga
    }

    /**
     * Refreshes details and/or chapters from the source.
     *
     * One combined [Source.getMangaUpdate] call rather than a detail fetch and a chapter fetch
     * racing each other: an extension-lib 1.6 source answers both from a single request, and
     * hitting it twice concurrently for the same manga is what that API exists to avoid. A source
     * that cannot answer both at once is asked separately, which is what 1.5 sources did anyway.
     */
    private suspend fun fetchMangaUpdateFromSource(
        fetchDetails: Boolean = true,
        fetchChapters: Boolean = true,
        manualFetch: Boolean = true,
    ) {
        if (!fetchDetails && !fetchChapters) return
        withIOContext {
            val existingChapters = if (fetchChapters) {
                getChapter.awaitAll(mangaId, false)
            } else {
                emptyList()
            }

            if (fetchDetails && fetchChapters) {
                try {
                    val update = source.getMangaUpdate(
                        manga = manga.copy(),
                        chapters = existingChapters,
                        fetchDetails = true,
                        fetchChapters = true,
                    )
                    applyMangaDetailsUpdate(update.manga)
                    applyChapterListUpdate(update.chapters, manualFetch)
                    return@withIOContext
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Fall through to the separate calls, which report their own failures.
                }
            }

            if (fetchDetails) {
                try {
                    applyMangaDetailsUpdate(
                        source.getMangaUpdate(
                            manga = manga.copy(),
                            chapters = emptyList(),
                            fetchDetails = true,
                            fetchChapters = false,
                        ).manga,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (e !is HttpException || e.code != 103) {
                        withUIContext {
                            view?.showError(trimException(e))
                        }
                    }
                }
            }

            if (fetchChapters) {
                try {
                    applyChapterListUpdate(
                        source.getMangaUpdate(
                            manga = manga.copy(),
                            chapters = existingChapters,
                            fetchDetails = false,
                            fetchChapters = true,
                        ).chapters,
                        manualFetch,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    recordChapterFetchFailure(e)
                }
            }
        }
    }

    private suspend fun applyMangaDetailsUpdate(networkManga: SManga) {
        manga.prepareCoverUpdate(coverCache, networkManga, false)
        manga.copyFrom(networkManga)
        manga.initialized = true

        updateManga.await(manga.toMangaUpdate())

        presenterScope.launchNonCancellableIO {
            val request =
                ImageRequest.Builder(preferences.context).data(manga.cover())
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.WRITE_ONLY)
                    .build()

            if (preferences.context.imageLoader.execute(request) is SuccessResult) {
                withUIContext {
                    view?.setPaletteColor()
                }
            }
        }
    }

    private suspend fun applyChapterListUpdate(networkChapters: List<SChapter>, manualFetch: Boolean) {
        // Merged sources have their own non-favourite manga rows that no update job
        // walks, so refresh them whenever this manga is refreshed.
        val mergedAdded = mergedSourceSync.await(manga.id!!)

        val (ownAdded, removed) = syncChaptersWithSource(networkChapters, manga, source)
        // Merged sources' new chapters appear in this manga's list, so they get the
        // same auto-download treatment as its own.
        val added = ownAdded + mergedAdded
        if (added.isNotEmpty()) {
            if (manga.shouldDownloadNewChapters(preferences) && manualFetch) {
                downloadChapters(
                    added.sortedBy { it.chapter_number }
                        .map { it.toModel() },
                )
            }
            view?.view?.context?.let { mangaShortcutManager.updateShortcuts(it) }
        }
        if (removed.isNotEmpty()) {
            val removedChaptersId = removed.map { it.id }
            val removedChapters = this@MangaDetailsPresenter.chapters.filter {
                it.id in removedChaptersId && it.isDownloaded
            }
            if (removedChapters.isNotEmpty()) {
                withUIContext {
                    view?.showChaptersRemovedPopup(removedChapters)
                }
            }
        }
        getChapters()
        getHistory()
        // A manual refresh that worked is as good a sign as a library update, so it
        // clears the broken-source count too — otherwise an entry the user just
        // fixed stays flagged until the next scheduled run.
        updateFailures.clear(manga.id!!)
        applyCategoryRules.awaitFor(manga.id!!)
    }

    private suspend fun recordChapterFetchFailure(e: Exception) {
        manga.id?.let {
            updateFailures.record(it, e, preferences.context.isOnline())
            // The failure count is a rule input, so recording one has to re-evaluate the entry
            // the same way the success path does. Without this a rule watching for a broken
            // source only fires on the next library update, not when the user sees it break.
            applyCategoryRules.awaitFor(it)
        }
        withUIContext {
            view?.showError(trimException(e))
        }
    }

    suspend fun mergedSources(): List<MergedMangaSource> = mergedSources.await(mangaId)

    /** Only the merges with something wrong with them, keyed by source id. */
    suspend fun mergedSourceHealth(): Map<Long, MergeHealth> =
        mergedSourceHealth.await(mangaId).filterValues { it != MergeHealth.OK }

    /**
     * Merges [source]'s copy of this manga in, so its chapters join this manga's list.
     *
     * The row for the other source already exists — global search inserts every result it
     * shows — so this only records the link and pulls that row's chapters in.
     */
    fun addMergedSource(source: Long, url: String) {
        presenterScope.launchIO {
            if (!mergedSources.addAtEnd(mangaId, source, url, ownSource = manga.source)) return@launchIO
            // Only a new source needs the network: its chapters aren't stored yet.
            try {
                mergedSourceSync.await(mangaId)
            } catch (e: Exception) {
                withUIContext { view?.showError(trimException(e)) }
            }
            reloadChapters()

            val summary = mergeSummary(source, url)
            withUIContext { view?.showMergeSummary(source, summary) }
        }
    }

    /** What a merge actually did to the chapter list, once the new source has been pulled in. */
    data class MergeSummary(val added: Int, val overlapped: Int)

    /**
     * Counts how much of the new source survived the merge.
     *
     * Merging is easy to get wrong — pick the wrong entry in search and you gain nothing but a
     * duplicate — and until now the only way to find out was to scroll the list. A source whose
     * chapters all lose to the ones already there reports 0 added, which is the signal that the
     * merge was pointless or wrong.
     */
    private suspend fun mergeSummary(source: Long, url: String): MergeSummary {
        val childId = getManga.awaitByUrlAndSource(url, source)?.id
            ?: return MergeSummary(0, 0)
        val fromSource = getChapter.awaitAllRaw(childId, false)
        val shown = getChapter.awaitAll(mangaId, false).mapNotNull { it.id }.toSet()
        val added = fromSource.count { it.id in shown }
        return MergeSummary(added = added, overlapped = fromSource.size - added)
    }

    fun removeMergedSource(source: Long) {
        presenterScope.launchIO {
            mergedSources.remove(mangaId, source)
            // Re-query so the removed source's chapters actually leave the list instead of
            // lingering as rows that no longer open. Re-fetch from source when online for an
            // authoritative "available chapters" list; fall back to a local reload otherwise.
            if (preferences.context.isOnline()) {
                fetchMangaUpdateFromSource(fetchDetails = false, fetchChapters = true, manualFetch = false)
            } else {
                reloadChapters()
            }
        }
    }

    /** Applies the dialog's order as the priority used to break ties between sources. */
    fun reorderMergedSources(sources: List<Long>) {
        presenterScope.launchIO {
            sources.forEachIndexed { index, source ->
                mergedSources.reorder(mangaId, source, index + 1)
            }
            reloadChapters()
        }
    }

    private suspend fun reloadChapters() {
        getChapters()
        withUIContext { view?.updateChapters() }
    }

    /** Refresh Manga Info and Chapter List (not tracking) */
    fun refreshAll() {
        val isLocal by lazy { manga.isLocal() }
        if (view?.isNotOnline() == true && !isLocal) return

        presenterScope.launch {
            isLoading = true
            fetchMangaUpdateFromSource(fetchDetails = true, fetchChapters = true, manualFetch = true)
            isLoading = false
            withUIContext {
                view?.updateChapters()
            }
        }
    }

    private fun trimException(e: java.lang.Exception): String {
        return (
            if (e !is SourceNotFoundException &&
                e.message?.contains(": ") == true
            ) {
                e.message?.split(": ")?.drop(1)
                    ?.joinToString(": ")
            } else {
                e.message
            }
            ) ?: view?.view?.context?.getString(MR.strings.unknown_error) ?: ""
    }

    /**
     * Bookmarks the given list of chapters.
     * @param selectedChapters the list of chapters to bookmark.
     */
    fun bookmarkChapters(selectedChapters: List<ChapterItem>, bookmarked: Boolean) {
        presenterScope.launchNonCancellableIO {
            val updates = selectedChapters.map {
                it.bookmark = bookmarked
                it.toProgressUpdate()
            }
            updateChapter.awaitAll(updates)
            getChapters()
            withUIContext { view?.updateChapters() }
        }
    }

    /**
     * Mark the selected chapter list as read/unread.
     * @param selectedChapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(
        selectedChapters: List<ChapterItem>,
        read: Boolean,
        deleteNow: Boolean = true,
        lastRead: Int? = null,
        pagesLeft: Int? = null,
    ) {
        presenterScope.launchNonCancellableIO {
            val updates = selectedChapters.map {
                it.read = read
                if (!read) {
                    it.last_page_read = lastRead ?: 0
                    it.pages_left = pagesLeft ?: 0
                }
                it.toProgressUpdate()
            }
            updateChapter.awaitAll(updates)
            // Starting or finishing a manga is what category rules react to, so re-file it
            // now instead of leaving it in the wrong category until the next library update.
            applyCategoryRules.awaitFor(mangaId)
            if (read && deleteNow && preferences.removeAfterMarkedAsRead().get()) {
                deleteChapters(selectedChapters, false)
            }
            getChapters()
            withUIContext { view?.updateChapters() }
            if (read && deleteNow) {
                val latestReadChapter = selectedChapters.maxByOrNull { it.chapter_number.toInt() }?.chapter
                updateTrackChapterMarkedAsRead(preferences, latestReadChapter, manga.id) {
                    fetchTracks()
                }
            }
        }
    }

    /**
     * Sets the sorting order and requests an UI update.
     */
    fun setSortOrder(sort: Int, descend: Boolean) {
        manga.setChapterOrder(sort, if (descend) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC)
        if (mangaSortMatchesDefault()) {
            manga.setSortToGlobal()
        }
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    fun setGlobalChapterSort(sort: Int, descend: Boolean) {
        preferences.sortChapterOrder().set(sort)
        preferences.chaptersDescAsDefault().set(descend)
        manga.setSortToGlobal()
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    fun mangaSortMatchesDefault(): Boolean {
        return (
            manga.sortDescending == preferences.chaptersDescAsDefault().get() &&
                manga.sorting == preferences.sortChapterOrder().get()
            ) || !manga.usesLocalSort
    }

    fun mangaFilterMatchesDefault(): Boolean {
        return (
            manga.readFilter == preferences.filterChapterByRead().get() &&
                manga.downloadedFilter == preferences.filterChapterByDownloaded().get() &&
                manga.bookmarkedFilter == preferences.filterChapterByBookmarked().get() &&
                manga.hideChapterTitles == preferences.hideChapterTitlesByDefault().get()
            ) || !manga.usesLocalFilter
    }

    fun resetSortingToDefault() {
        manga.setSortToGlobal()
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    /**
     * Removes all filters and requests an UI update.
     */
    fun setFilters(
        unread: TriStateCheckBox.State,
        downloaded: TriStateCheckBox.State,
        bookmarked: TriStateCheckBox.State,
    ) {
        manga.readFilter = when (unread) {
            TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_UNREAD
            TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_READ
            else -> Manga.SHOW_ALL
        }
        manga.downloadedFilter = when (downloaded) {
            TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_DOWNLOADED
            TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
            else -> Manga.SHOW_ALL
        }
        manga.bookmarkedFilter = when (bookmarked) {
            TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_BOOKMARKED
            TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
            else -> Manga.SHOW_ALL
        }
        manga.setFilterToLocal()
        if (mangaFilterMatchesDefault()) {
            manga.setFilterToGlobal()
        }
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    /**
     * Sets the active display mode.
     * @param hide set title to hidden
     */
    fun hideTitle(hide: Boolean) {
        manga.displayMode = if (hide) Manga.CHAPTER_DISPLAY_NUMBER else Manga.CHAPTER_DISPLAY_NAME
        manga.setFilterToLocal()
        presenterScope.launchNonCancellableIO { updateManga.await(MangaUpdate(manga.id!!, chapterFlags = manga.chapter_flags)) }
        if (mangaFilterMatchesDefault()) {
            manga.setFilterToGlobal()
        }
        view?.refreshAdapter()
    }

    fun resetFilterToDefault() {
        manga.setFilterToGlobal()
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    fun setGlobalChapterFilters(
        unread: TriStateCheckBox.State,
        downloaded: TriStateCheckBox.State,
        bookmarked: TriStateCheckBox.State,
    ) {
        preferences.filterChapterByRead().set(
            when (unread) {
                TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_UNREAD
                TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_READ
                else -> Manga.SHOW_ALL
            },
        )
        preferences.filterChapterByDownloaded().set(
            when (downloaded) {
                TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_DOWNLOADED
                TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_NOT_DOWNLOADED
                else -> Manga.SHOW_ALL
            },
        )
        preferences.filterChapterByBookmarked().set(
            when (bookmarked) {
                TriStateCheckBox.State.CHECKED -> Manga.CHAPTER_SHOW_BOOKMARKED
                TriStateCheckBox.State.IGNORE -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
                else -> Manga.SHOW_ALL
            },
        )
        preferences.hideChapterTitlesByDefault().set(manga.hideChapterTitles)
        manga.setFilterToGlobal()
        presenterScope.launchNonCancellableIO { asyncUpdateMangaAndChapters() }
    }

    private suspend fun asyncUpdateMangaAndChapters(justChapters: Boolean = false) {
        if (!justChapters) updateManga.await(MangaUpdate(manga.id!!, chapterFlags = manga.chapter_flags))
        getChapters()
        withUIContext { view?.updateChapters() }
    }

    private fun isScanlatorFiltered() = manga.filtered_scanlators?.isNotEmpty() == true

    fun currentFilters(): String {
        val filtersId = mutableListOf<StringResource?>()
        filtersId.add(if (manga.readFilter(preferences) == Manga.CHAPTER_SHOW_READ) MR.strings.read else null)
        filtersId.add(if (manga.readFilter(preferences) == Manga.CHAPTER_SHOW_UNREAD) MR.strings.unread else null)
        filtersId.add(if (manga.downloadedFilter(preferences) == Manga.CHAPTER_SHOW_DOWNLOADED) MR.strings.downloaded else null)
        filtersId.add(if (manga.downloadedFilter(preferences) == Manga.CHAPTER_SHOW_NOT_DOWNLOADED) MR.strings.not_downloaded else null)
        filtersId.add(if (manga.bookmarkedFilter(preferences) == Manga.CHAPTER_SHOW_BOOKMARKED) MR.strings.bookmarked else null)
        filtersId.add(if (manga.bookmarkedFilter(preferences) == Manga.CHAPTER_SHOW_NOT_BOOKMARKED) MR.strings.not_bookmarked else null)
        filtersId.add(if (isScanlatorFiltered()) MR.strings.scanlators else null)
        return filtersId.filterNotNull()
            .joinToString(", ") { view?.view?.context?.getString(it) ?: "" }
    }

    fun setScanlatorFilter(filteredScanlators: Set<String>) {
        presenterScope.launchNonCancellableIO {
            val manga = manga
            MangaUtil.setScanlatorFilter(
                updateManga,
                manga,
                if (filteredScanlators.size == allChapterScanlators.size) emptySet() else filteredScanlators
            )
            asyncUpdateMangaAndChapters(true)
        }
    }

    /**
     * Get user categories.
     *
     * @return List of categories, not including the default category
     */
    fun getCategories(): List<Category> {
        return runBlocking { getCategories.await() }
    }

    fun confirmDeletion() {
        presenterScope.launchNonCancellableIO {
            manga.removeCover(coverCache)
            customMangaManager.saveMangaInfo(CustomMangaInfo(
                mangaId = manga.id!!,
                title = null,
                author = null,
                artist = null,
                description = null,
                genre = null,
                status = null,
            ))
            downloadManager.deleteManga(manga, source)
            asyncUpdateMangaAndChapters(true)
        }
    }

    private fun onUpdateManga() = fetchChapters()

    fun shareManga() {
        val context = Injekt.get<Application>()

        val destDir = UniFile.fromFile(context.cacheDir)!!.createDirectory("shared_image")!!

        presenterScope.launchIO {
            try {
                val uri = saveCover(destDir)
                withUIContext {
                    view?.shareManga(uri.uri.toFile())
                }
            } catch (_: java.lang.Exception) {
            }
        }
    }

    private fun saveImage(cover: Bitmap, directory: File, manga: Manga): File? {
        directory.mkdirs()

        // Build destination file.
        val filename = DiskUtil.buildValidFilename("${manga.title} - Cover.jpg")

        val destFile = File(directory, filename)
        val stream: OutputStream = FileOutputStream(destFile)
        cover.compress(Bitmap.CompressFormat.JPEG, 75, stream)
        stream.flush()
        stream.close()
        return destFile
    }

    fun updateManga(
        title: String?,
        author: String?,
        artist: String?,
        uri: Uri?,
        description: String?,
        tags: Array<String>?,
        status: Int?,
        seriesType: Int?,
        lang: String?,
        resetCover: Boolean = false,
    ) {
        if (manga.isLocal()) {
            manga.title = if (title.isNullOrBlank()) manga.url else title.trim()
            manga.author = author?.trimOrNull()
            manga.artist = artist?.trimOrNull()
            manga.description = description?.trimOrNull()
            val tagsString = tags?.joinToString(", ") { tag ->
                tag.replaceFirstChar {
                    it.uppercase(Locale.getDefault())
                }
            }
            manga.genre = if (tags.isNullOrEmpty()) null else tagsString?.trim()
            if (seriesType != null) {
                manga.genre = setSeriesType(seriesType, manga.genre).joinToString(", ") {
                    it.replaceFirstChar { genre ->
                        genre.titlecase(Locale.getDefault())
                    }
                }
                manga.viewer_flags = -1
                presenterScope.launchIO { updateManga.await(MangaUpdate(manga.id!!, viewerFlags = manga.viewer_flags)) }
            }
            manga.status = status ?: SManga.UNKNOWN
            LocalSource(downloadManager.context).updateMangaInfo(manga, lang)
            presenterScope.launchIO {
                updateManga.await(
                    MangaUpdate(
                        manga.id!!,
                        title = manga.ogTitle,
                        author = manga.originalAuthor,
                        artist = manga.originalArtist,
                        description = manga.originalDescription,
                        genres = manga.originalGenre?.split(", ").orEmpty(),
                        status = manga.ogStatus,
                    )
                )
            }
        } else {
            var genre = if (!tags.isNullOrEmpty() && tags.joinToString(", ") != manga.originalGenre) {
                tags.map { tag -> tag.replaceFirstChar { it.titlecase(Locale.getDefault()) } }
                    .toTypedArray()
            } else {
                null
            }
            if (seriesType != null) {
                genre = setSeriesType(seriesType, genre?.joinToString())
                manga.viewer_flags = -1
                presenterScope.launchIO { updateManga.await(MangaUpdate(manga.id!!, viewerFlags = manga.viewer_flags)) }
            }
            val manga = CustomMangaInfo(
                mangaId = manga.id!!,
                title?.trimOrNull(),
                author?.trimOrNull(),
                artist?.trimOrNull(),
                description?.trimOrNull(),
                genre?.joinToString(),
                if (status != this.manga.ogStatus) status else null,
            )
            launchNow {
                customMangaManager.saveMangaInfo(manga)
            }
        }
        if (uri != null) {
            editCoverWithStream(uri)
        } else if (resetCover) {
            coverCache.deleteCustomCover(manga)
            presenterScope.launchIO { manga.updateCoverLastModified() }
            view?.setPaletteColor()
        }
        view?.updateHeader()
    }

    private fun setSeriesType(seriesType: Int, genres: String? = null): Array<String> {
        val tags = (genres ?: manga.genre)?.split(",")?.map { it.trim() }?.toMutableList() ?: mutableListOf()
        tags.removeAll { manga.isSeriesTag(it) }
        when (seriesType) {
            Manga.TYPE_MANGA -> tags.add("Manga")
            Manga.TYPE_MANHUA -> tags.add("Manhua")
            Manga.TYPE_MANHWA -> tags.add("Manhwa")
            Manga.TYPE_COMIC -> tags.add("Comic")
            Manga.TYPE_WEBTOON -> tags.add("Webtoon")
        }
        return tags.toTypedArray()
    }

    fun editCoverWithStream(uri: Uri): Boolean {
        val inputStream =
            downloadManager.context.contentResolver.openInputStream(uri) ?: return false
        if (manga.isLocal()) {
            LocalSource.updateCover(manga, inputStream)
            presenterScope.launchNonCancellableIO { manga.updateCoverLastModified() }
            view?.setPaletteColor()
            return true
        }

        if (manga.favorite) {
            coverCache.setCustomCoverToCache(manga, inputStream)
            presenterScope.launchNonCancellableIO { manga.updateCoverLastModified() }
            view?.setPaletteColor()
            return true
        }
        return false
    }

    fun shareCover(): Uri? {
        return try {
            val destDir = UniFile.fromFile(coverCache.context.cacheDir)!!.createDirectory("shared_image")!!
            val file = saveCover(destDir)
            file.uri
        } catch (e: Exception) {
            null
        }
    }

    fun saveCover(): Boolean {
        return try {
            val directory = if (preferences.folderPerManga().get()) {
                storageManager.getCoversDirectory()!!.createDirectory(DiskUtil.buildValidFilename(manga.title))!!
            } else {
                storageManager.getCoversDirectory()!!
            }
            val file = saveCover(directory)
            DiskUtil.scanMedia(preferences.context, file)
            true
        } catch (e: Exception) {
            if (networkPreferences.verboseLogging().get()) Logger.e(e) { "Unable to save cover" }
            false
        }
    }

    private fun saveCover(directory: UniFile): UniFile {
        val cover = coverCache.getCustomCoverFile(manga).takeIf { it.exists() } ?: coverCache.getCoverFile(manga.thumbnail_url, !manga.favorite)
        val type = cover?.let { ImageUtil.findImageType(it.inputStream()) }
            ?: throw Exception("Not an image")

        // Build destination file.
        val filename = DiskUtil.buildValidFilename("${manga.title}.${type.extension}")

        val destFile = directory.createFile(filename)!!
        cover.inputStream().use { input ->
            destFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        return destFile
    }

    fun isTracked(): Boolean =
        loggedServices.any { service -> tracks.any { it.sync_id == service.id } }

    fun hasTrackers(): Boolean = loggedServices.isNotEmpty()

    /** Only worth offering once there is a server to send to, the same test the sync job uses. */
    fun hasShelf(): Boolean = koreaderPreferences.serverUrl().get().isNotBlank()

    fun isOnShelf(): Boolean = manga.id?.toString() in koreaderPreferences.shelfManga().get()

    /** @return whether the manga is on the shelf afterwards. */
    fun toggleShelf(): Boolean {
        val id = manga.id?.toString() ?: return false
        val pref = koreaderPreferences.shelfManga()
        val add = id !in pref.get()
        // Read-modify-write: the set is the whole preference, so dropping the rest of it is the
        // way this goes wrong.
        pref.set(if (add) pref.get() + id else pref.get() - id)
        return add
    }

    // Tracking
    private fun setTrackItems() {
        trackList = loggedServices.filter { service ->
            if (service !is EnhancedTrackService) return@filter true
            service.accept(source)
        }.map { service ->
            TrackItem(tracks.find { it.sync_id == service.id }, service)
        }
    }

    suspend fun fetchTracks() {
        tracks = withContext(Dispatchers.IO) { getTrack.awaitAllByMangaId(manga.id!!) }
        setTrackItems()
        withContext(Dispatchers.Main) { view?.refreshTracking(trackList) }
    }

    fun refreshTracking(showOfflineSnack: Boolean = false, trackIndex: Int? = null) {
        if (view?.isNotOnline(showOfflineSnack) == false) {
            presenterScope.launch {
                val asyncList = (trackIndex?.let { listOf(trackList[it]) } ?: trackList.filter { it.track != null })
                    .map { item ->
                        async(Dispatchers.IO) {
                            val trackItem = try {
                                item.service.refresh(item.track!!)
                            } catch (e: Exception) {
                                trackError(e)
                                null
                            }
                            if (trackItem != null) {
                                insertTrack.await(trackItem)
                                syncChaptersWithTrackServiceTwoWay(chapters, trackItem, item.service)
                                trackItem
                            } else {
                                item.track
                            }
                        }
                    }
                asyncList.awaitAll()
                fetchTracks()
            }
        }
    }

    fun trackSearch(query: String, service: TrackService) {
        if (view?.isNotOnline() == false) {
            presenterScope.launch(Dispatchers.IO) {
                val results = try {
                    service.search(query)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { view?.trackSearchError(e) }
                    return@launch
                }
                withContext(Dispatchers.Main) { view?.onTrackSearchResults(results) }
            }
        }
    }

    fun registerTracking(item: Track?, service: TrackService) {
        if (item != null) {
            item.manga_id = manga.id!!

            presenterScope.launch {
                val binding = try {
                    service.bind(item)
                } catch (e: Exception) {
                    trackError(e)
                    null
                }
                withContext(Dispatchers.IO) {
                    if (binding != null) {
                        insertTrack.await(binding)
                    }

                    syncChaptersWithTrackServiceTwoWay(chapters, item, service)
                }
                fetchTracks()
            }
        }
    }

    fun removeTracker(trackItem: TrackItem, removeFromService: Boolean) {
        presenterScope.launch {
            withContext(Dispatchers.IO) {
                deleteTrack.awaitForManga(manga.id!!, trackItem.service.id)
                if (removeFromService && trackItem.service.canRemoveFromService()) {
                    trackItem.service.removeFromService(trackItem.track!!)
                }
            }
            fetchTracks()
        }
    }

    private fun updateRemote(track: Track, service: TrackService) {
        presenterScope.launch {
            val binding = try {
                service.update(track)
            } catch (e: Exception) {
                trackError(e)
                null
            }
            if (binding != null) {
                withContext(Dispatchers.IO) { insertTrack.await(binding) }
                fetchTracks()
            } else {
                trackRefreshDone()
            }
        }
    }

    private fun trackRefreshDone() {
        presenterScope.launch(Dispatchers.Main) { view?.trackRefreshDone() }
    }

    private fun trackError(error: Exception) {
        presenterScope.launch(Dispatchers.Main) { view?.trackRefreshError(error) }
    }

    fun setStatus(item: TrackItem, index: Int) {
        val track = item.track!!
        track.status = item.service.getStatusList()[index]
        if (item.service.isCompletedStatus(index) && track.total_chapters > 0L) {
            track.last_chapter_read = track.total_chapters.toFloat()
        }
        updateRemote(track, item.service)
    }

    fun setScore(item: TrackItem, index: Int) {
        val track = item.track!!
        track.score = item.service.indexToScore(index)
        updateRemote(track, item.service)
    }

    fun setLastChapterRead(item: TrackItem, chapterNumber: Int) {
        val track = item.track!!
        track.last_chapter_read = chapterNumber.toFloat()
        updateRemote(track, item.service)
    }

    fun setTrackerStartDate(item: TrackItem, date: Long) {
        val track = item.track!!
        track.started_reading_date = date
        updateRemote(track, item.service)
    }

    fun setTrackerFinishDate(item: TrackItem, date: Long) {
        val track = item.track!!
        track.finished_reading_date = date
        updateRemote(track, item.service)
    }

    suspend fun getSuggestedDate(readingDate: TrackingBottomSheet.ReadingDate): Long? {
        val chapters = getHistory.awaitAllByMangaId(manga.id ?: 0L)
        val date = when (readingDate) {
            TrackingBottomSheet.ReadingDate.Start -> chapters.minOfOrNull { it.last_read }
            TrackingBottomSheet.ReadingDate.Finish -> chapters.maxOfOrNull { it.last_read }
        } ?: return null
        return if (date <= 0L) null else date
    }

    override fun onStatusChange(download: Download) {
        super.onStatusChange(download)
        chapters.find { it.id == download.chapter.id }?.status = download.status
        onPageProgressUpdate(download)
    }

    private suspend fun onQueueUpdate(queue: List<Download>) = withIOContext {
        getChapters(queue)
        withUIContext {
            view?.updateChapters()
        }
    }

    override fun onQueueUpdate(download: Download) {
        // already handled by onStatusChange
    }

    override fun onProgressUpdate(download: Download) {
        // already handled by onStatusChange
    }

    override fun onPageProgressUpdate(download: Download) {
        chapters.find { it.id == download.chapter.id }?.download = download
        view?.updateChapterDownload(download)
    }

    companion object {
        const val MULTIPLE_VOLUMES = 1
        const val TENS_OF_CHAPTERS = 2
        const val MULTIPLE_SEASONS = 3
    }
}
