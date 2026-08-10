package eu.kanade.tachiyomi.data.library

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import dev.icerock.moko.resources.StringResource
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import eu.kanade.tachiyomi.appwidget.TachiyomiWidgetManager
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.prepareCoverUpdate
import eu.kanade.tachiyomi.data.download.DownloadJob
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.koreader.KoreaderSyncJob
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.data.preference.DEVICE_BATTERY_NOT_LOW
import eu.kanade.tachiyomi.data.preference.DEVICE_CHARGING
import eu.kanade.tachiyomi.data.preference.DEVICE_ONLY_ON_WIFI
import eu.kanade.tachiyomi.data.preference.MANGA_HAS_UNREAD
import eu.kanade.tachiyomi.data.preference.MANGA_NON_COMPLETED
import eu.kanade.tachiyomi.data.preference.MANGA_NON_READ
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.extension.ExtensionUpdateJob
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.MergedSourceSync
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithSource
import eu.kanade.tachiyomi.util.chapter.syncChaptersWithTrackServiceTwoWay
import eu.kanade.tachiyomi.util.manga.MangaShortcutManager
import eu.kanade.tachiyomi.util.isLocal
import eu.kanade.tachiyomi.util.shouldDownloadNewChapters
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.isConnectedToWifi
import eu.kanade.tachiyomi.util.system.isOnline
import eu.kanade.tachiyomi.util.system.localeContext
import eu.kanade.tachiyomi.util.system.tryToSetForeground
import eu.kanade.tachiyomi.util.system.withIOContext
import java.io.File
import java.lang.ref.WeakReference
import java.util.Date
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import karasu.domain.category.interactor.ApplyCategoryRules
import karasu.domain.category.interactor.GetCategories
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.failures.FailureCause
import karasu.domain.manga.failures.causeOf
import karasu.domain.manga.failures.interactor.GetBrokenSources
import karasu.domain.manga.failures.interactor.UpdateFailures
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.manga.interval.FetchInterval
import karasu.domain.manga.models.cover
import karasu.domain.track.interactor.GetTrack
import karasu.domain.track.interactor.InsertTrack
import karasu.i18n.MR
import karasu.util.lang.getString

/**
 * Why this manga would be skipped under [restrictions], or null if it would be updated.
 *
 * Shared with [ReleaseWatchJob] on purpose. The watcher picks manga by release window and hands
 * them here to be fetched; if it did not apply the same rules, a manga the user has restricted
 * would be handed over on every run, skipped on every run, and — because a skipped manga is
 * never fetched, so its estimate is never rewritten — stay due forever. The watcher's batch
 * would fill up with entries that can never make progress.
 */
internal fun restrictedBy(manga: LibraryManga, restrictions: Set<String>): StringResource? = when {
    MANGA_NON_COMPLETED in restrictions && manga.manga.status == SManga.COMPLETED ->
        MR.strings.skipped_reason_completed
    MANGA_HAS_UNREAD in restrictions && manga.unread != 0 ->
        MR.strings.skipped_reason_not_caught_up
    MANGA_NON_READ in restrictions && manga.totalChapters > 0 && !manga.hasRead ->
        MR.strings.skipped_reason_not_started
    manga.manga.update_strategy != UpdateStrategy.ALWAYS_UPDATE ->
        MR.strings.skipped_reason_not_always_update
    else -> null
}

class LibraryUpdateJob(private val context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {

    private val getCategories: GetCategories = Injekt.get()
    private val getChapter: GetChapter = Injekt.get()

    private val coverCache: CoverCache = Injekt.get()
    private val sourceManager: SourceManager = Injekt.get()
    private val preferences: PreferencesHelper = Injekt.get()
    private val downloadManager: DownloadManager = Injekt.get()
    private val trackManager: TrackManager = Injekt.get()
    private val mangaShortcutManager: MangaShortcutManager = Injekt.get()
    private val getLibraryManga: GetLibraryManga = Injekt.get()
    private val mergedSourceSync: MergedSourceSync = Injekt.get()
    private val applyCategoryRules: ApplyCategoryRules = Injekt.get()
    private val updateFailures: UpdateFailures = Injekt.get()
    private val fetchInterval: FetchInterval = Injekt.get()
    private val getBrokenSources: GetBrokenSources = Injekt.get()
    private val updateManga: UpdateManga = Injekt.get()
    private val getTrack: GetTrack = Injekt.get()
    private val insertTrack: InsertTrack by injectLazy()

    private var extraDeferredJobs = mutableListOf<Deferred<Any>>()

    private val extraScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val emitScope = MainScope()

    private val mangaToUpdate = mutableListOf<LibraryManga>()

    // Every map below is written from the per-source coroutines at the same time, so none of them
    // can be a plain LinkedHashMap. ConcurrentHashMap rejects null values, hence the empty-string
    // fallbacks at the write sites: the error file prints the message as-is either way.
    private val mangaToUpdateMap = ConcurrentHashMap<Long, List<LibraryManga>>()

    private val categoryIds = mutableSetOf<Int>()

    // Manga id to when it is next expected to have a chapter. Empty on manual runs, which is
    // what makes [filterMangaToUpdate] skip nothing there.
    private var dueDates: Map<Long, Long> = emptyMap()

    // Consecutive failures per manga as they stood when the run started, read once so the
    // failure path can back a manga off without another query per exception.
    private var failureCounts: Map<Long, Int> = emptyMap()

    // List containing new updates
    private val newUpdates = ConcurrentHashMap<LibraryManga, Array<Chapter>>()

    // List containing failed updates
    private val failedUpdates = ConcurrentHashMap<Manga, String>()

    // List containing skipped updates
    private val skippedUpdates = ConcurrentHashMap<Manga, String>()

    val count = AtomicInteger(0)

    // Boolean to determine if user wants to automatically download new chapters.
    private val downloadNew: Boolean = preferences.downloadNewChapters().get()

    // Boolean to determine if DownloadManager has downloads
    private var hasDownloads = false

    private val requestSemaphore = Semaphore(5)

    // For updates delete removed chapters if not preference is set as well
    private val deleteRemoved by lazy { preferences.deleteRemovedChapters().get() != 1 }

    private val notifier = LibraryUpdateNotifier(context.localeContext)

    override suspend fun doWork(): Result {
        if (tags.contains(WORK_NAME_AUTO)) {
            val preferences = Injekt.get<PreferencesHelper>()
            val restrictions = preferences.libraryUpdateDeviceRestriction().get()
            if ((DEVICE_ONLY_ON_WIFI in restrictions) && !context.isConnectedToWifi()) {
                return Result.failure()
            }

            // Find a running manual worker. If exists, try again later
            if (instance != null) {
                return Result.retry()
            }
        }

        tryToSetForeground()

        instance = WeakReference(this)

        val target = inputData.getString(KEY_TARGET)?.let { Target.valueOf(it) } ?: Target.CHAPTERS

        // Only the scheduled run skips: pulling to refresh means "ask now", and a user who did
        // that and got nothing would have no way to tell an up-to-date manga from a skipped one.
        if (target == Target.CHAPTERS &&
            tags.contains(WORK_NAME_AUTO) &&
            preferences.smartLibraryUpdates().get()
        ) {
            dueDates = fetchInterval.awaitDue().restrictToScheduledCategories()
        }

        // If this is a chapter update, set the last update time to now
        if (target == Target.CHAPTERS) {
            preferences.libraryUpdateLastTimestamp().set(Date().time)
        }

        val savedMangasList = inputData.getLongArray(KEY_MANGAS)?.asList()?.plus(extraManga)
        extraManga = emptyList()

        val mangaList = (
            if (savedMangasList != null) {
                val mangas =
                    getLibraryManga.await()
                        .filter { it.manga.id in savedMangasList }
                        .distinctBy { it.manga.id }
                val categoryId = inputData.getInt(KEY_CATEGORY, -1)
                if (categoryId > -1) categoryIds.add(categoryId)
                mangas
            } else {
                getMangaToUpdate()
            }
            ).sortedBy { it.manga.title }

        return withIOContext {
            try {
                launchTarget(target, mangaList)
                // New chapters change the counts category rules read, so re-file the library
                // before the run ends rather than leaving it stale until the next one.
                applyCategoryRules.await()
                // And the shelf is filled from the library, so it is out of date the moment the
                // library is not. Whether the new chapters are downloaded yet or the sync has to
                // queue them itself, this is the point at which it can tell.
                if (target == Target.CHAPTERS) KoreaderSyncJob.startIfAutomatic(context)
                Result.success()
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // Assume success although cancelled
                    finishUpdates(true)
                    Result.success()
                } else {
                    Logger.e(e) { "Failed to update library" }
                    Result.failure()
                }
            } finally {
                instance = null
                sendUpdate(null)
                notifier.cancelProgressNotification()
            }
        }
    }

    private suspend fun launchTarget(target: Target, mangaToAdd: List<LibraryManga>) {
        if (target == Target.CHAPTERS) {
            sendUpdate(STARTING_UPDATE_SOURCE)
        }
        when (target) {
            Target.CHAPTERS -> updateChaptersJob(filterMangaToUpdate(mangaToAdd))
            Target.DETAILS -> updateDetails(mangaToAdd)
            else -> updateTrackings(mangaToAdd)
        }
    }

    private suspend fun sendUpdate(mangaId: Long?) {
        if (isStopped) {
            updateMutableFlow.tryEmit(mangaId)
        } else {
            emitScope.launch { updateMutableFlow.emit(mangaId) }
        }
    }

    private suspend fun updateChaptersJob(mangaToAdd: List<LibraryManga>) {
        // Initialize the variables holding the progress of the updates.
        // A manga whose source has been failing is likely to fail again, and a failure costs a
        // whole timeout with a permit held. Sorting them last means the library that can be
        // updated is done by the time the broken sources are still waiting to time out.
        val failures = runCatching { updateFailures.await() }.getOrDefault(emptyMap())
        failureCounts = failures
        val ordered = mangaToAdd.sortedBy { failures[it.manga.id] ?: 0 }
        mangaToUpdate.addAll(ordered)
        mangaToUpdateMap.putAll(ordered.groupBy { it.manga.source })
        checkIfMassiveUpdate()
        coroutineScope {
            val list = mangaToUpdateMap.keys.map { source ->
                async {
                    try {
                        updateMangaInSource(source)
                    } catch (e: Exception) {
                        Logger.e(e) { "Unable to update manga" }
                        false
                    }
                }
            }
            val results = list.awaitAll()
            if (!hasDownloads) {
                hasDownloads = results.any { it }
            }
            finishUpdates()
        }
    }

    /**
     * Method that updates the details of the given list of manga. It's called in a background
     * thread, so it's safe to do heavy operations or network calls here.
     *
     * @param mangaToUpdate the list to update
     */
    private suspend fun updateDetails(mangaToUpdate: List<LibraryManga>) = coroutineScope {
        // Initialize the variables holding the progress of the updates.
        val count = AtomicInteger(0)
        val asyncList = mangaToUpdate.groupBy { it.manga.source }.values.map { list ->
            async {
                list.forEach { manga ->
                    ensureActive()
                    val source = sourceManager.get(manga.manga.source) as? CatalogueSource ?: return@async
                    notifier.showProgressNotification(
                        manga.manga,
                        count.andIncrement,
                        mangaToUpdate.size,
                    )
                    ensureActive()
                    val networkManga = requestSemaphore.withPermit {
                        try {
                            source.getMangaUpdate(
                                manga = manga.manga.copy(),
                                chapters = emptyList(),
                                fetchDetails = true,
                                fetchChapters = false,
                            ).manga
                        } catch (e: java.lang.Exception) {
                            Logger.e(e)
                            null
                        }
                    }
                    if (networkManga != null) {
                        manga.manga.prepareCoverUpdate(coverCache, networkManga, false)
                        val thumbnailUrl = manga.manga.thumbnail_url
                        manga.manga.copyFrom(networkManga)
                        manga.manga.initialized = true
                        val request: ImageRequest =
                            if (thumbnailUrl != manga.manga.thumbnail_url) {
                                // load new covers in background
                                ImageRequest.Builder(context).data(manga.manga.cover())
                                    .memoryCachePolicy(CachePolicy.DISABLED).build()
                            } else {
                                ImageRequest.Builder(context).data(manga.manga.cover())
                                    .memoryCachePolicy(CachePolicy.DISABLED)
                                    .diskCachePolicy(CachePolicy.WRITE_ONLY)
                                    .build()
                            }
                        context.imageLoader.execute(request)
                        updateManga.await(manga.manga.toMangaUpdate())
                    }
                }
            }
        }
        asyncList.awaitAll()
        notifier.cancelProgressNotification()
    }

    /**
     * Method that updates the metadata of the connected tracking services. It's called in a
     * background thread, so it's safe to do heavy operations or network calls here.
     */
    private suspend fun updateTrackings(mangaToUpdate: List<LibraryManga>) {
        // Initialize the variables holding the progress of the updates.
        var count = 0

        val loggedServices = trackManager.services.filter { it.isLogged }

        mangaToUpdate.forEach { manga ->
            notifier.showProgressNotification(manga.manga, count++, mangaToUpdate.size)

            val tracks = getTrack.awaitAllByMangaId(manga.manga.id!!)

            tracks.forEach { track ->
                val service = trackManager.getService(track.sync_id)
                if (service != null && service in loggedServices) {
                    try {
                        val newTrack = service.refresh(track)
                        insertTrack.await(newTrack)

                        syncChaptersWithTrackServiceTwoWay(getChapter.awaitAll(manga.manga.id!!, false), track, service)
                    } catch (e: Exception) {
                        Logger.e(e)
                    }
                }
            }
        }
        notifier.cancelProgressNotification()
    }

    private suspend fun finishUpdates(wasStopped: Boolean = false) {
        if (!wasStopped && !isStopped) {
            extraDeferredJobs.awaitAll()
        }
        if (newUpdates.isNotEmpty()) {
            notifier.showResultNotification(newUpdates)
            if (!wasStopped && preferences.refreshCoversToo().get() && !isStopped) {
                updateDetails(newUpdates.keys.toList())
                notifier.cancelProgressNotification()
                if (downloadNew && hasDownloads) {
                    DownloadJob.start(context, runExtensionUpdatesAfter)
                    runExtensionUpdatesAfter = false
                }
            } else if (downloadNew && hasDownloads) {
                DownloadJob.start(applicationContext, runExtensionUpdatesAfter)
                runExtensionUpdatesAfter = false
            }
        }
        newUpdates.clear()
        if (skippedUpdates.isNotEmpty() && Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_LIBRARY_SKIPPED)) {
            val skippedFile = writeErrorFile(
                skippedUpdates,
                "skipped",
                context.getString(MR.strings.learn_why) + " - " + LibraryUpdateNotifier.HELP_SKIPPED_URL,
            ).getUriCompat(context)
            notifier.showUpdateSkippedNotification(skippedUpdates.map { it.key.title }, skippedFile)
        }
        if (failedUpdates.isNotEmpty() && Notifications.isNotificationChannelEnabled(context, Notifications.CHANNEL_LIBRARY_ERROR)) {
            val errorFile = writeErrorFile(failedUpdates).getUriCompat(context)
            // Asked once, at the end of a run that already did hundreds of network calls, so the
            // cost is noise — and it is the only way to know whether the panel has anything in it.
            val hasBrokenSources = runCatching { getBrokenSources.await().isNotEmpty() }
                .getOrDefault(false)
            notifier.showUpdateErrorNotification(
                failedUpdates.map { it.key.title },
                errorFile,
                hasBrokenSources,
            )
        }
        mangaShortcutManager.updateShortcuts(context)
        // The run just moved every estimate it touched, which is exactly what the release widget
        // draws. Nothing else would redraw it until the next hourly tick.
        with(TachiyomiWidgetManager()) { context.refreshReleases() }
        failedUpdates.clear()
        notifier.cancelProgressNotification()
        if (runExtensionUpdatesAfter && !DownloadJob.isRunning(context)) {
            ExtensionUpdateJob.runJobAgain(context, NetworkType.CONNECTED)
            runExtensionUpdatesAfter = false
        }
    }

    private fun checkIfMassiveUpdate() {
        val largestSourceSize = mangaToUpdate
            .groupBy { it.manga.source }
            .filterKeys { sourceManager.get(it) !is UnmeteredSource }
            .maxOfOrNull { it.value.size } ?: 0
        if (largestSourceSize > MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD) {
            notifier.showQueueSizeWarningNotification()
        }
    }

    /**
     * Updates every manga of one source, one at a time, holding a permit only while a manga is
     * actually being fetched.
     *
     * Each source gets its own coroutine and they all run: the permit caps how many requests are
     * in flight across the whole library, not how many sources are allowed to make progress. The
     * old shape gave a permit to a whole source for the length of its queue, so a library with
     * three sources never had more than three requests going no matter how many manga it held,
     * and one source with hundreds of entries decided how long the run took.
     */
    private suspend fun updateMangaInSource(source: Long): Boolean {
        if (mangaToUpdateMap[source] == null) return false
        var count = 0
        var hasDownloads = false
        val sourceObj = sourceManager.get(source) as? CatalogueSource ?: return false
        while (count < mangaToUpdateMap[source]!!.size) {
            val manga = mangaToUpdateMap[source]!![count]
            val shouldDownload = manga.manga.shouldDownloadNewChapters(preferences)
            val updated = requestSemaphore.withPermit {
                updateMangaChapters(manga, this.count.andIncrement, sourceObj, shouldDownload)
            }
            if (updated) {
                hasDownloads = true
            }
            count++
        }
        mangaToUpdateMap[source] = emptyList()
        return hasDownloads
    }

    private suspend fun updateMangaChapters(
        manga: LibraryManga,
        progress: Int,
        source: CatalogueSource,
        shouldDownload: Boolean,
    ): Boolean = coroutineScope {
        try {
            var hasDownloads = false
            ensureActive()
            notifier.showProgressNotification(manga.manga, progress, mangaToUpdate.size)
            // Merged sources live on their own non-favourite rows, which this job never
            // walks, so refresh them alongside the manga they belong to.
            val mergedChapters = mergedSourceSync.await(manga.manga.id!!)

            val knownChapters = getChapter.awaitAll(manga.manga.id!!, false)
            val fetchedChapters = source.getMangaUpdate(
                manga = manga.manga.copy(),
                chapters = knownChapters,
                fetchDetails = false,
                fetchChapters = true,
            ).chapters

            val newChapters = if (fetchedChapters.isNotEmpty()) {
                syncChaptersWithSource(fetchedChapters, manga.manga, source)
            } else {
                // A source that returns nothing is the case merging exists for, so the merged
                // sources' chapters are still handled below.
                emptyList<Chapter>() to emptyList()
            }

            // The estimate is refreshed on every successful fetch, so it stays current for a user
            // who never lets the schedule run. Read back from the database rather than from what
            // the source just returned: that list is this manga's own rows only, and for a merged
            // entry the rhythm the user sees is the merged one. The rows also carry `date_fetch`,
            // already written as each chapter arrived, which is the fallback for sources that
            // report no upload date.
            manga.manga.id?.let { id ->
                val chapters = getChapter.awaitAll(id, false)
                fetchInterval.record(id, chapters.map { it.date_upload }, chapters.map { it.date_fetch })
            }

            // Chapters a merged source brought in show up in this manga's list, so they are
            // new chapters of this manga as far as the user is concerned. They download under
            // this manga's folder and their pages are fetched from the source they came from,
            // which `MergedSourceFallback` resolves.
            val added = (newChapters.first + mergedChapters).sortedBy { it.chapter_number }
            if (added.isNotEmpty()) {
                if (shouldDownload) {
                    downloadChapters(manga.manga, added)
                    hasDownloads = true
                }
                newUpdates[manga] = added.toTypedArray()
            }
            if (deleteRemoved && newChapters.second.isNotEmpty()) {
                val removedChapters = newChapters.second.filter {
                    downloadManager.isChapterDownloaded(it, manga.manga) &&
                        newChapters.first.none { newChapter ->
                            newChapter.chapter_number == it.chapter_number && it.scanlator.isNullOrBlank()
                        }
                }
                if (removedChapters.isNotEmpty()) {
                    downloadManager.deleteChapters(removedChapters, manga.manga, source)
                }
            }
            if (added.isNotEmpty() || newChapters.second.isNotEmpty()) {
                sendUpdate(manga.manga.id)
            }
            // Reaching here means the source answered, which is what makes the failure count
            // below a count of *consecutive* failures.
            manga.manga.id?.let { updateFailures.clear(it) }
            return@coroutineScope hasDownloads
        } catch (e: Exception) {
            if (e !is CancellationException) {
                failedUpdates[manga.manga] = e.message.orEmpty()
                manga.manga.id?.let {
                    updateFailures.record(it, e, context.isOnline())
                    // The estimate is only rewritten on success, so without this a manga on a
                    // broken source would stay permanently due and the watcher would ask about
                    // it every single run until the source came back.
                    fetchInterval.backOff(it, (failureCounts[it] ?: 0) + 1)
                }
                // A source whose response no longer fits the extension's parser is almost always
                // fixed by a newer extension, and this run has just proven the network works.
                // The completion block already knows how to kick that job off.
                if (causeOf(e.message) == FailureCause.OUTDATED_EXTENSION) {
                    runExtensionUpdatesAfterJob()
                }
                Logger.e { "Failed updating: ${manga.manga.title}: $e" }
            }
            return@coroutineScope false
        }
    }

    private fun downloadChapters(manga: Manga, chapters: List<Chapter>) {
        // We don't want to start downloading while the library is updating, because websites
        // may don't like it and they could ban the user.
        downloadManager.downloadChapters(manga, chapters, false)
    }

    private fun filterMangaToUpdate(mangaToAdd: List<LibraryManga>): List<LibraryManga> {
        val restrictions = preferences.libraryUpdateMangaRestriction().get()
        val now = Date().time
        return mangaToAdd.filter { manga ->

            if (tags.contains(WORK_NAME_AUTO) && manga.manga.isLocal()) {
                // This prevents data loss if files are temporarily moved when a background job runs.
                 return@filter false
            }

            if (!tags.contains(WORK_NAME_AUTO) && manga.manga.isLocal()) {
                return@filter true
            }

            // Not in its release window yet. Deliberately not reported as skipped: this is the
            // normal state of most of the library on most runs, and a notification listing
            // hundreds of titles that are simply not due yet is noise, not information.
            val dueAt = manga.manga.id?.let { dueDates[it] }
            if (dueAt != null && dueAt > now) {
                return@filter false
            }

            val reason = restrictedBy(manga, restrictions) ?: return@filter true
            skippedUpdates[manga.manga] = context.getString(reason)
            return@filter false
        }
    }

    /**
     * Drops manga outside the categories the release schedule covers.
     *
     * Skipping is the only thing this scoping guards. The estimate itself is built for every
     * manga that gets fetched, whatever category it is in — it is a by-product of a fetch that
     * was happening anyway, and it costs nothing to keep. What the selection decides is where
     * the app is allowed to *act* on that estimate.
     */
    private suspend fun Map<Long, Long>.restrictToScheduledCategories(): Map<Long, Long> {
        val selected = preferences.releaseScheduleCategories().get().map(String::toInt)
        if (selected.isEmpty() || isEmpty()) return this
        val scheduled = getLibraryManga.await()
            .filter { it.category in selected }
            .mapNotNull { it.manga.id }
            .toSet()
        return filterKeys { it in scheduled }
    }

    private suspend fun getMangaToUpdate(): List<LibraryManga> {
        val categoryId = inputData.getInt(KEY_CATEGORY, -1)
        return getMangaToUpdate(categoryId)
    }

    /**
     * Returns the list of manga to be updated.
     *
     * @param categoryId the category to update
     * @return a list of manga to update
     */
    private suspend fun getMangaToUpdate(categoryId: Int): List<LibraryManga> {
        val libraryManga = getLibraryManga.await()

        val listToUpdate = if (categoryId != -1) {
            categoryIds.add(categoryId)
            libraryManga.filter { it.category == categoryId }
        } else {
            val categoriesToUpdate =
                preferences.libraryUpdateCategories().get().map(String::toInt)
            if (categoriesToUpdate.isNotEmpty()) {
                categoryIds.addAll(categoriesToUpdate)
                libraryManga.filter { it.category in categoriesToUpdate }.distinctBy { it.manga.id }
            } else {
                categoryIds.addAll(getCategories.await().mapNotNull { it.id } + 0)
                libraryManga.distinctBy { it.manga.id }
            }
        }

        val categoriesToExclude =
            preferences.libraryUpdateCategoriesExclude().get().map(String::toInt)
        val listToExclude = if (categoriesToExclude.isNotEmpty() && categoryId == -1) {
            libraryManga.filter { it.category in categoriesToExclude }.toSet()
        } else {
            emptySet()
        }

        return listToUpdate.minus(listToExclude)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = notifier.progressNotificationBuilder.build()
        val id = Notifications.ID_LIBRARY_PROGRESS
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id, notification)
        }
    }

    /**
     * Writes basic file of update errors to cache dir.
     */
    private fun writeErrorFile(errors: Map<Manga, String?>, fileName: String = "errors", additionalInfo: String? = null): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("tachiyomi_update_$fileName.txt")
                file.bufferedWriter().use { out ->
                    additionalInfo?.let { out.write("$it\n\n") }
                    // Error file format:
                    // ! Error
                    //   # Source
                    //     - Manga
                    errors.toList().groupBy({ it.second }, { it.first }).forEach { (error, mangas) ->
                        out.write("! ${error}\n")
                        mangas.groupBy { it.source }.forEach { (srcId, mangas) ->
                            val source = sourceManager.getOrStub(srcId)
                            out.write("  # $source\n")
                            mangas.forEach {
                                out.write("    - ${it.title}\n")
                            }
                        }
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }

    private fun addMangaToQueue(categoryId: Int, manga: List<LibraryManga>) {
        val mangas = filterMangaToUpdate(manga).sortedBy { it.manga.title }
        categoryIds.add(categoryId)
        addManga(mangas)
    }

    private fun addCategory(categoryId: Int) {
        val mangas = filterMangaToUpdate(runBlocking { getMangaToUpdate(categoryId) }).sortedBy { it.manga.title }
        categoryIds.add(categoryId)
        addManga(mangas)
    }

    private fun addManga(mangaToAdd: List<LibraryManga>) {
        val distinctManga = mangaToAdd.filter { it !in mangaToUpdate }
        mangaToUpdate.addAll(distinctManga)
        checkIfMassiveUpdate()
        distinctManga.groupBy { it.manga.source }.forEach {
            // if added queue items is a new source not in the async list or an async list has
            // finished running
            if (mangaToUpdateMap[it.key].isNullOrEmpty()) {
                mangaToUpdateMap[it.key] = it.value
                extraScope.launch {
                    extraDeferredJobs.add(
                        async(Dispatchers.IO) {
                            val hasDLs = try {
                                updateMangaInSource(it.key)
                            } catch (e: Exception) {
                                false
                            }
                            if (!hasDownloads) {
                                hasDownloads = hasDLs
                            }
                        },
                    )
                }
            } else {
                val list = mangaToUpdateMap[it.key] ?: emptyList()
                mangaToUpdateMap[it.key] = (list + it.value)
            }
        }
    }

    enum class Target {

        CHAPTERS, // Manga chapters

        DETAILS, // Manga metadata

        TRACKING, // Tracking metadata
    }

    companion object {
        private const val TAG = "LibraryUpdate"
        private const val WORK_NAME_AUTO = "LibraryUpdate-auto"
        private const val WORK_NAME_MANUAL = "LibraryUpdate-manual"

        private const val ERROR_LOG_HELP_URL = "https://tachiyomi.org/help/guides/troubleshooting"

        private const val MANGA_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 60

        /**
         * Key for category to update.
         */
        private const val KEY_CATEGORY = "category"
        const val STARTING_UPDATE_SOURCE = -5L

        /**
         * Key that defines what should be updated.
         */
        private const val KEY_TARGET = "target"

        private const val KEY_MANGAS = "mangas"

        private var instance: WeakReference<LibraryUpdateJob>? = null

        private var extraManga = emptyList<Long>()

        val updateMutableFlow = MutableSharedFlow<Long?>(
            extraBufferCapacity = 10,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )
        val updateFlow = updateMutableFlow.asSharedFlow()

        private var runExtensionUpdatesAfter = false

        fun runExtensionUpdatesAfterJob() { runExtensionUpdatesAfter = true }

        fun setupTask(context: Context, prefInterval: Int? = null) {
            val preferences = Injekt.get<PreferencesHelper>()
            val interval = prefInterval ?: preferences.libraryUpdateInterval().get()
            if (interval > 0) {
                val restrictions = preferences.libraryUpdateDeviceRestriction().get()

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresCharging(DEVICE_CHARGING in restrictions)
                    .setRequiresBatteryNotLow(DEVICE_BATTERY_NOT_LOW in restrictions)
                    .build()

                val request = PeriodicWorkRequestBuilder<LibraryUpdateJob>(
                    interval.toLong(),
                    TimeUnit.HOURS,
                    10,
                    TimeUnit.MINUTES,
                )
                    .addTag(TAG)
                    .addTag(WORK_NAME_AUTO)
                    .setConstraints(constraints)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME_AUTO,
                    ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE,
                    request,
                )
            } else {
                WorkManager.getInstance(context).cancelAllWorkByTag(WORK_NAME_AUTO)
            }
            // Wired here rather than at every call site, so background updating is turned on and
            // off in one place and the two jobs can never disagree about whether it is enabled.
            ReleaseWatchJob.setupTask(context, interval)
            // Scheduled from here for the same reason, but deliberately not gated on [interval]:
            // the digest only reads estimates the app already has and never goes online, so
            // "manual updates only" is not a statement about it. Its own hour is its off switch.
            ReleaseDigestJob.setupTask(context)
        }

        fun cancelAllWorks(context: Context) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        }

        fun isRunningFlow(context: Context): Flow<Boolean> {
            return WorkManager.getInstance(context).getWorkInfosByTagFlow(TAG).map { list ->
                list.any { it.state == WorkInfo.State.RUNNING }
            }
        }

        fun isRunning(context: Context): Boolean {
            val list = WorkManager.getInstance(context).getWorkInfosByTag(TAG).get()
            return list.any { it.state == WorkInfo.State.RUNNING }
        }

        fun categoryInQueue(id: Int?) = instance?.get()?.categoryIds?.contains(id) ?: false

        fun startNow(
            context: Context,
            category: Category? = null,
            target: Target = Target.CHAPTERS,
            mangaToUse: List<LibraryManga>? = null,
        ): Boolean {
            if (isRunning(context)) {
                if (target == Target.CHAPTERS) {
                    category?.id?.let {
                        if (mangaToUse != null) {
                            instance?.get()?.addMangaToQueue(it, mangaToUse)
                        } else {
                            instance?.get()?.addCategory(it)
                        }
                    }
                }
                // Already running either as a scheduled or manual job
                return false
            }

            val builder = Data.Builder()
            builder.putString(KEY_TARGET, target.name)
            category?.id?.let { builder.putInt(KEY_CATEGORY, it) }
            // Independent of the category: an explicit list is the whole instruction on its own,
            // which is how the release watcher hands over the manga whose window just opened.
            if (mangaToUse != null) {
                builder.putLongArray(
                    KEY_MANGAS,
                    mangaToUse.firstOrNull()?.manga?.id?.let { longArrayOf(it) } ?: longArrayOf(),
                )
                extraManga = mangaToUse.drop(1).mapNotNull { it.manga.id }
            }
            val inputData = builder.build()
            val request = OneTimeWorkRequestBuilder<LibraryUpdateJob>()
                .addTag(TAG)
                .addTag(WORK_NAME_MANUAL)
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME_MANUAL, ExistingWorkPolicy.KEEP, request)

            return true
        }

        fun stop(context: Context) {
            val wm = WorkManager.getInstance(context)
            val workQuery = WorkQuery.Builder.fromTags(listOf(TAG))
                .addStates(listOf(WorkInfo.State.RUNNING))
                .build()
            wm.getWorkInfos(workQuery).get()
                // Should only return one work but just in case
                .forEach {
                    wm.cancelWorkById(it.id)

                    // Re-enqueue cancelled scheduled work
                    if (it.tags.contains(WORK_NAME_AUTO)) {
                        setupTask(context)
                    }
                }
        }
    }
}
