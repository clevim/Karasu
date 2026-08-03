package eu.kanade.tachiyomi.data.download

import android.content.Context
import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.koreader.KoreaderSyncJob
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.MergedSourceFallback
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.UnmeteredSource
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.chapter.ChapterUtil.Companion.preferredChapterName
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.DiskUtil.NOMEDIA_FILE
import eu.kanade.tachiyomi.util.storage.saveTo
import eu.kanade.tachiyomi.util.system.ImageUtil
import eu.kanade.tachiyomi.util.system.e
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.launchNow
import eu.kanade.tachiyomi.util.system.withIOContext
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.system.writeText
import java.io.File
import java.io.IOException
import java.util.*
import java.util.zip.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import nl.adaptivity.xmlutil.serialization.XML
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import karasu.core.archive.ZipWriter
import karasu.core.metadata.COMIC_INFO_FILE
import karasu.core.metadata.ComicInfo
import karasu.core.metadata.getComicInfo
import karasu.domain.category.interactor.GetCategories
import karasu.domain.download.DownloadPreferences
import karasu.i18n.MR
import karasu.util.lang.getString

/**
 * This class is the one in charge of downloading chapters.
 *
 * Its queue contains the list of chapters to download.
 */
class Downloader(
    private val context: Context,
    private val provider: DownloadProvider = Injekt.get(),
    private val cache: DownloadCache = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) {
    private val preferences: PreferencesHelper by injectLazy()
    private val downloadPreferences: DownloadPreferences by injectLazy()
    private val chapterCache: ChapterCache by injectLazy()
    private val xml: XML by injectLazy()
    private val getCategories: GetCategories by injectLazy()
    private val mergedSourceFallback: MergedSourceFallback by injectLazy()

    /**
     * Store for persisting downloads across restarts.
     */
    private val store = DownloadStore(context, sourceManager)

    /**
     * Queue where active downloads are kept.
     */
    private val _queueState = MutableStateFlow<List<Download>>(emptyList())
    val queueState = _queueState.asStateFlow()

    /**
     * Notifier for the downloader state and progress.
     */
    private val notifier by lazy { DownloadNotifier(context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloaderJob: Job? = null

    /**
     * Whether the downloader is running.
     */
    val isRunning: Boolean
        get() = downloaderJob?.isActive ?: false

    /**
     * Whether the downloader is paused
     */
    @Volatile
    var isPaused: Boolean = false

    init {
        launchNow {
            val chapters = async { store.restore() }
            addAllToQueue(chapters.await())
        }
    }

    /**
     * Starts the downloader. It doesn't do anything if it's already running or there isn't anything
     * to download.
     *
     * @return true if the downloader is started, false otherwise.
     */
    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) {
            return false
        }

        val pending = queueState.value.filter { it.status != Download.State.DOWNLOADED }
        pending.forEach { if (it.status != Download.State.QUEUE) it.status = Download.State.QUEUE }

        isPaused = false
        notifier.dismissPaused()

        launchDownloaderJob()

        return pending.isNotEmpty()
    }

    /**
     * Stops the downloader.
     */
    fun stop(reason: String? = null) {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.ERROR }

        if (reason != null) {
            notifier.onWarning(reason)
            return
        }

        if (isPaused && queueState.value.isNotEmpty()) {
            notifier.onDownloadPaused()
        } else {
            notifier.dismiss()
        }

        isPaused = false

        DownloadJob.stop(context)
    }

    /**
     * Pauses the downloader
     */
    fun pause() {
        cancelDownloaderJob()
        queueState.value
            .filter { it.status == Download.State.DOWNLOADING }
            .forEach { it.status = Download.State.QUEUE }
        isPaused = true
    }

    fun clearQueue() {
        cancelDownloaderJob()

        internalClearQueue()
        notifier.dismiss()
    }

    private fun launchDownloaderJob() {
        if (isRunning) return

        downloaderJob = scope.launch {
            val activeDownloadsFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeDownloads = queue.asSequence()
                        // Ignore completed downloads, leave them in the queue
                        .filter {
                            val statusValue = it.status.value
                            Download.State.NOT_DOWNLOADED.value <= statusValue && statusValue <= Download.State.DOWNLOADING.value
                        }
                        .groupBy { it.source }
                        .toList()
                        // Concurrently download from 5 different sources
                        .take(5)
                        .map { (_, downloads) -> downloads.first() }
                    emit(activeDownloads)

                    if (activeDownloads.isEmpty()) break
                    // Suspend until a download enters the ERROR state
                    val activeDownloadsErroredFlow =
                        combine(activeDownloads.map(Download::statusFlow)) { states ->
                            states.contains(Download.State.ERROR)
                        }.filter { it }
                    activeDownloadsErroredFlow.first()
                }
            }.distinctUntilChanged()

            // Use supervisorScope to cancel child jobs when the downloader job is cancelled
            supervisorScope {
                val downloadJobs = mutableMapOf<Download, Job>()

                activeDownloadsFlow.collectLatest { activeDownloads ->
                    val downloadJobsToStop = downloadJobs.filter { it.key !in activeDownloads }
                    downloadJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        downloadJobs.remove(download)
                    }

                    val downloadsToStart = activeDownloads.filter { it !in downloadJobs }
                    downloadsToStart.forEach { download ->
                        downloadJobs[download] = launchDownloadJob(download)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchDownloadJob(download: Download) = launchIO {
        try {
            downloadChapter(download)

            // Remove successful download from queue
            if (download.status == Download.State.DOWNLOADED) {
                removeFromQueue(download)
            }
            if (areAllDownloadsFinished()) {
                stop()
                // The shelf only uploads chapters that are already on disk, and it has just become
                // true of these. Waiting for the next scheduled run is what made a fresh chapter
                // take hours to reach the device.
                KoreaderSyncJob.startIfAutomatic(context)
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            Logger.e(e)
            notifier.onError(e.message)
            stop()
        }
    }

    /**
     * Destroys the downloader subscriptions.
     */
    private fun cancelDownloaderJob() {
        downloaderJob?.cancel()
        downloaderJob = null
    }

    /**
     * Creates a download object for every chapter and adds them to the downloads queue.
     *
     * @param manga the manga of the chapters to download.
     * @param chapters the list of chapters to download.
     * @param autoStart whether to start the downloader after enqueing the chapters.
     */
    fun queueChapters(manga: Manga, chapters: List<Chapter>, autoStart: Boolean) = launchIO {
        if (chapters.isEmpty()) {
            return@launchIO
        }

        val source = sourceManager.get(manga.source) as? HttpSource ?: return@launchIO
        val wasEmpty = queueState.value.isEmpty()
        // Called in background thread, the operation can be slow with SAF.
        val chaptersWithoutDir = async {
            chapters
                // Filter out those already downloaded.
                .filter { provider.findChapterDir(it, manga, source) == null }
                // Add chapters to queue from the start.
                .sortedByDescending { it.source_order }
        }

        // Runs in main thread (synchronization needed).
        val chaptersToQueue = chaptersWithoutDir.await()
            // Filter out those already enqueued.
            .filter { chapter -> queueState.value.none { it.chapter.id == chapter.id } }
            // Create a download for each one.
            .map { Download(source, manga, it) }

        if (chaptersToQueue.isNotEmpty()) {
            addAllToQueue(chaptersToQueue)

            // Start downloader if needed
            if (autoStart && wasEmpty) {
                val queuedDownloads = queueState.value.count { it.source !is UnmeteredSource }
                val maxDownloadsFromSource = queueState.value
                    .groupBy { it.source }
                    .filterKeys { it !is UnmeteredSource }
                    .maxOfOrNull { it.value.size } ?: 0
                if (
                    queuedDownloads > DOWNLOADS_QUEUED_WARNING_THRESHOLD ||
                    maxDownloadsFromSource > CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD
                ) {
                    withUIContext {
                        notifier.massDownloadWarning()
                    }
                }
                DownloadJob.start(context)
            } else if (!isRunning && !LibraryUpdateJob.isRunning(context)) {
                notifier.onDownloadPaused()
            }
        }
    }

    /**
     * Downloads a chapter.
     *
     * @param download the chapter to be downloaded.
     */
    private suspend fun downloadChapter(download: Download) {
        val mangaDir = provider.getMangaDir(download.manga, download.source)
        // A chapter borrowed from a merged source can only be fetched with that source: its urls
        // and headers are the ones the images are served against. The directory above stays on
        // the manga being read, so a merged chapter downloads and deletes like any other.
        // Reassigned below when the fallback ends up serving the pages from a different source,
        // because that source's client is the one its image urls answer to.
        var source = download.manga.id
            ?.let { mergedSourceFallback.ownerOf(it, download.chapter) }
            ?.let { sourceManager.get(it.source) as? HttpSource }
            ?: download.source

        val availSpace = DiskUtil.getAvailableStorageSpace(mangaDir)
        val chapName = download.chapter.preferredChapterName(context, download.manga, preferences)
        if (availSpace != -1L && availSpace < MIN_DISK_SPACE) {
            download.status = Download.State.ERROR
            notifier.onError(context.getString(MR.strings.couldnt_download_low_space), chapName)
            return
        }
        val chapterDirname = provider.getChapterDirName(download.chapter, includeId = downloadPreferences.downloadWithId().get())
        val tmpDir = mangaDir.createDirectory(chapterDirname + TMP_DIR_SUFFIX)!!

        try {
            // If the page list already exists, start from the file
            val pageList = download.pages ?: run {
                // Otherwise, pull page list from network and add them to download object
                val pages = when (val mangaId = download.manga.id) {
                    // Never persisted, so it can't have merged sources to fall back to.
                    null -> source.getPageList(download.chapter)
                    else -> mergedSourceFallback.getPages(mangaId, download.chapter, source)
                        .also { source = it.source }
                        .pages
                }

                if (pages.isEmpty()) {
                    throw Exception(context.getString(MR.strings.no_pages_found))
                }
                // Don't trust index from source
                val reIndexedPages = pages.mapIndexed { index, page ->
                    Page(
                        index,
                        page.url,
                        page.imageUrl,
                        page.uri,
                    )
                }
                download.pages = reIndexedPages
                reIndexedPages
            }

            // Delete all temporary (unfinished) files
            tmpDir.listFiles().orEmpty()
                .filter { it.name.orEmpty().endsWith(".tmp") }
                .forEach { it.delete() }

            download.status = Download.State.DOWNLOADING

            // Same-language source fallback as the reader has: a source can serve a good page list
            // and then fail on the images it points at. The page list came from `source`, so that
            // is the one already tried, and the pages that did download are kept across the switch.
            val triedSources = mutableSetOf(source.id)
            while (true) {
                downloadPages(pageList, download, source, tmpDir)
                if (isDownloadSuccessful(download, tmpDir)) break

                val mangaId = download.manga.id ?: break
                source = mergedSourceFallback
                    .switchSource(mangaId, download.chapter, source, pageList, triedSources)
                    ?: break
                Logger.i { "Retrying ${download.chapter.name} from ${source.name}" }
            }

            // Do after download completes

            if (!isDownloadSuccessful(download, tmpDir)) {
                download.status = Download.State.ERROR
                return
            }

            createComicInfoFile(
                tmpDir,
                download.manga,
                download.chapter,
                source,
            )

            // Only rename the directory if it's downloaded
            if (preferences.saveChaptersAsCBZ().get()) {
                archiveChapter(mangaDir, chapterDirname, tmpDir)
            } else {
                tmpDir.renameTo(chapterDirname)
            }
            cache.addChapter(chapterDirname, mangaDir, download.manga)

            DiskUtil.createNoMediaFile(tmpDir, context)

            download.status = Download.State.DOWNLOADED
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            // If the page list threw, it will resume here
            Logger.e(error)
            download.status = Download.State.ERROR
            notifier.onError(error.message, chapName, download.manga.title)
        }
    }

    /**
     * Resolves any missing image urls against [source] and downloads every page that isn't on disk.
     *
     * Called once per source the chapter is attempted from, so it has to be safe to repeat: a page
     * already downloaded is found in [tmpDir] and skipped, and an image url is only asked for when
     * the page hasn't got one — which, after a source switch, is exactly the pages that failed.
     */
    private suspend fun downloadPages(
        pageList: List<Page>,
        download: Download,
        source: HttpSource,
        tmpDir: UniFile,
    ) {
        // Get all the URLs to the source images, fetch pages if necessary
        pageList.filter { it.imageUrl.isNullOrEmpty() }.forEach { page ->
            page.status = Page.State.LoadPage
            try {
                page.imageUrl = source.getImageUrl(page)
            } catch (e: Throwable) {
                page.status = Page.State.Error
            }
        }

        // Start downloading images, consider we can have downloaded images already
        // Concurrently do 2 pages at a time
        pageList.asFlow()
            .flatMapMerge(concurrency = 2) { page ->
                flow {
                    withIOContext { getOrDownloadImage(page, download, source, tmpDir) }
                    emit(page)
                }.flowOn(Dispatchers.IO)
            }
            .collect {
                // Do when page is downloaded.
                notifier.onProgressChange(download)
            }
    }

    private fun isDownloadSuccessful(
        download: Download,
        tmpDir: UniFile,
    ): Boolean {
        // Page list hasn't been initialized
        val downloadPageCount = download.pages?.size ?: return false

        // Ensure that all pages has been downloaded
        if (download.downloadedImages != downloadPageCount) return false

        // Ensure that the chapter folder has all the pages
        val downloadedImagesCount = tmpDir.listFiles().orEmpty().count {
            val fileName = it.name.orEmpty()
            when {
                fileName in listOf(COMIC_INFO_FILE, NOMEDIA_FILE) -> false
                fileName.endsWith(".tmp") -> false
                // Only count the first split page and not the others
                fileName.contains("__") && !fileName.endsWith("__001.jpg") -> false
                else -> true
            }
        }

        return downloadedImagesCount == downloadPageCount
    }

    /**
     * Returns the observable which gets the image from the filesystem if it exists or downloads it
     * otherwise.
     *
     * @param page the page to download.
     * @param download the download of the page.
     * @param tmpDir the temporary directory of the download.
     */
    private suspend fun getOrDownloadImage(
        page: Page,
        download: Download,
        source: HttpSource,
        tmpDir: UniFile,
    ) {
        // If the image URL is empty, do nothing
        if (page.imageUrl == null) {
            return
        }

        val digitCount = (download.pages?.size ?: 0).toString().length.coerceAtLeast(3)
        val filename = String.format("%0${digitCount}d", page.number)
        val tmpFile = tmpDir.findFile("$filename.tmp")

        // Delete temp file if it exists
        tmpFile?.delete()

        // Try to find the image file
        val imageFile = tmpDir.listFiles().orEmpty().find { it.name.orEmpty().startsWith("$filename.") || it.name.orEmpty().startsWith("${filename}__001") }

        val chapName = download.chapter.preferredChapterName(context, download.manga, preferences)
        try {
            // If the image is already downloaded, do nothing. Otherwise download from network
            val file = when {
                imageFile != null -> imageFile
                chapterCache.isImageInCache(page.imageUrl!!) -> moveImageFromCache(
                    chapterCache.getImageFile(
                        page.imageUrl!!,
                    ),
                    tmpDir,
                    filename,
                )
                else -> downloadImage(page, source, tmpDir, filename)
            }

            // When the page is ready, set page path, progress (just in case) and status
            splitTallImageIfNeeded(filename, tmpDir)

            page.uri = file.uri
            page.progress = 100
            page.status = Page.State.Ready
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            // Mark this page as error and allow to download the remaining
            page.progress = 0
            page.status = Page.State.Error
            notifier.onError(e.message, chapName, download.manga.title)
        }
    }

    /**
     * Downloads the image from network to a file in tmpDir.
     *
     * @param page the page to download.
     * @param source the source of the page.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private suspend fun downloadImage(
        page: Page,
        source: HttpSource,
        tmpDir: UniFile,
        filename: String,
    ): UniFile {
        page.status = Page.State.DownloadImage
        page.progress = 0
        return flow {
            val response = source.getImage(page)
            val file = tmpDir.createFile("$filename.tmp")
            try {
                response.body.source().saveTo(file!!.openOutputStream())
                // Thrown rather than stored: the flow below retries three times, which is what
                // rides out a source that is briefly handing out error pages, and a page that
                // still fails stays in Error so the chapter is never counted as downloaded.
                val extension = getImageExtension(file)
                    ?: throw IOException(context.getString(MR.strings.download_notifier_not_an_image))
                file.renameTo("$filename.$extension")
            } catch (e: Exception) {
                response.close()
                file?.delete()
                throw e
            }
            emit(file)
        }
            // Retry 3 times, waiting 2, 4 and 8 seconds between attempts.
            .retryWhen { _, attempt ->
                if (attempt < 3) {
                    delay((2L shl attempt.toInt()) * 1000)
                    true
                } else {
                    false
                }
            }
            .first()
    }

    /**
     * Copies the image from cache to file in tmpDir.
     *
     * @param cacheFile the file from cache.
     * @param tmpDir the temporary directory of the download.
     * @param filename the filename of the image.
     */
    private fun moveImageFromCache(cacheFile: File, tmpDir: UniFile, filename: String): UniFile {
        val tmpFile = tmpDir.createFile("$filename.tmp")!!
        cacheFile.inputStream().use { input ->
            tmpFile.openOutputStream().use { output ->
                input.copyTo(output)
            }
        }
        val extension = ImageUtil.findImageType(cacheFile.inputStream()) ?: return tmpFile
        tmpFile.renameTo("$filename.${extension.extension}")
        cacheFile.delete()
        return tmpFile
    }

    /**
     * The extension for a page that was just downloaded, or null when its bytes are not an image.
     *
     * The bytes decide, and nothing else. A source under load answers a page request with its own
     * error page — HTML, HTTP 200 — and assuming a jpg when nothing recognised the content is how
     * one of those ends up stored as a page, counted as downloaded, and carried into the CBZ that
     * goes to the device, where there is nothing left to display. The declared Content-Type is no
     * help either: it named the error page as html and was ignored for exactly the same reason.
     *
     * Types the decoder cannot name are types the reader cannot draw, so refusing them loses
     * nothing that used to work.
     *
     * @param file the file where the image is already downloaded.
     */
    private fun getImageExtension(file: UniFile): String? =
        ImageUtil.findImageType { file.openInputStream() }?.extension

    /** @param fileName the page's zero-padded name, without extension, as it was downloaded. */
    private fun splitTallImageIfNeeded(fileName: String, tmpDir: UniFile) {
        if (!preferences.splitTallImages().get()) return

        try {
            // Anchored on "." or "__" rather than a bare prefix: with four-digit names "001" also
            // prefixes "0010.jpg", and page 1 would be split from page 10's image.
            val imageFile = tmpDir.listFiles()?.firstOrNull {
                val name = it.name.orEmpty()
                name.startsWith("$fileName.") || name.startsWith("${fileName}__")
            }
                ?: throw Error(context.getString(MR.strings.download_notifier_split_page_not_found, fileName.toInt()))

            // Check if the original page was previously split before then skip.
            if (imageFile.name.orEmpty().startsWith("${fileName}__")) return

            ImageUtil.splitTallImage(tmpDir, imageFile, fileName)
        } catch (e: Exception) {
            Logger.e(e) { "Failed to split downloaded image"}
        }
    }

    /**
     * Archive the chapter pages as a CBZ.
     */
    private fun archiveChapter(
        mangaDir: UniFile,
        dirname: String,
        tmpDir: UniFile,
    ) {
        val zip = mangaDir.createFile("$dirname.cbz$TMP_DIR_SUFFIX")
        if (zip?.isFile != true) throw Exception("Failed to create CBZ file for downloaded chapter")
        ZipWriter(context, zip!!).use { writer ->
            // listFiles() hands back whatever order the filesystem stored them in, and a reader
            // that trusts entry order instead of sorting names then shows the chapter shuffled.
            // Page names are zero-padded to a fixed width, so sorting by name is the page order.
            tmpDir.listFiles()?.sortedBy { it.name.orEmpty() }?.forEach { file ->
                writer.write(file)
            }
        }
        zip.renameTo("$dirname.cbz")
        tmpDir.delete()
    }

    /**
     * Creates a ComicInfo.xml file inside the given directory.
     *
     * @param dir the directory in which the ComicInfo file will be generated.
     * @param manga the manga.
     * @param chapter the chapter.
     * @param chapterUrl the resolved URL for the chapter.
     */
    private fun createComicInfoFile(
        dir: UniFile,
        manga: Manga,
        chapter: Chapter,
        source: HttpSource,
    ) {
        val categories = manga.id?.let { mangaId ->
            // FIXME: Don't do blocking
            runBlocking {
                getCategories.awaitByMangaId(mangaId)
            }
        }
            .orEmpty()
            .map { it.name.trim() }
            .takeUnless { it.isEmpty() }
        val url = try { source.getChapterUrl(chapter) } catch (_: Exception) { null }
            ?: source.getChapterUrl(manga, chapter).takeIf { !it.isNullOrBlank() }  // FIXME: Not sure if this is necessary

        val comicInfo = getComicInfo(
            manga,
            chapter,
            url?.let { listOf(it) } ?: listOf(),
            categories,
            source.name,
            source.lang,
        )

        // Remove the old file
        dir.findFile(COMIC_INFO_FILE)?.delete()
        dir.createFile(COMIC_INFO_FILE)?.writeText(xml.encodeToString(ComicInfo.serializer(), comicInfo))
    }

    /**
     * Returns true if all the queued downloads are in DOWNLOADED or ERROR state.
     */
    private fun areAllDownloadsFinished(): Boolean {
        return queueState.value.none { it.status <= Download.State.DOWNLOADING }
    }

    private fun addAllToQueue(downloads: List<Download>) {
        _queueState.update {
            downloads.forEach { download ->
                download.status = Download.State.QUEUE
            }
            store.addAll(downloads)
            it + downloads
        }
    }

    fun removeFromQueue(download: Download) {
        _queueState.update {
            store.remove(download)
            if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                download.status = Download.State.NOT_DOWNLOADED
            }
            it - download
        }
    }

    private inline fun removeFromQueueIf(predicate: (Download) -> Boolean) {
        _queueState.update { queue ->
            val downloads = queue.filter { predicate(it) }
            store.removeAll(downloads)
            downloads.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            queue - downloads
        }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    fun removeFromQueue(chapters: List<Chapter>) {
        removeFromQueueIf { it.chapter.id in chapters.map { it.id } }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { download ->
                if (download.status == Download.State.DOWNLOADING || download.status == Download.State.QUEUE) {
                    download.status = Download.State.NOT_DOWNLOADED
                }
            }
            store.clear()
            emptyList()
        }
    }

    fun updateQueue(downloads: List<Download>) {
        val wasRunning = isRunning

        if (downloads.isEmpty()) {
            clearQueue()
            DownloadJob.stop(context)
            return
        }

        pause()
        internalClearQueue()
        addAllToQueue(downloads)

        if (wasRunning) {
            start()
        }
    }

    companion object {
        const val TMP_DIR_SUFFIX = "_tmp"
        const val CHAPTERS_PER_SOURCE_QUEUE_WARNING_THRESHOLD = 15
        private const val DOWNLOADS_QUEUED_WARNING_THRESHOLD = 30

        // Arbitrary minimum required space to start a download: 200 MB
        const val MIN_DISK_SPACE = 200 * 1024 * 1024
    }
}
