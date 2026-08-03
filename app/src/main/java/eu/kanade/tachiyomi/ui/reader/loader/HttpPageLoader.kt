package eu.kanade.tachiyomi.ui.reader.loader

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.cache.ChapterCache
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.MergedSourceFallback
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.withIOContext
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Loader used to load chapters from an online source.
 */
class HttpPageLoader(
    private val chapter: ReaderChapter,
    private val source: HttpSource,
    private val chapterCache: ChapterCache = Injekt.get(),
    private val preferences: PreferencesHelper = Injekt.get(),
    private val mergedSourceFallback: MergedSourceFallback = Injekt.get(),
) : PageLoader() {

    override val isLocal: Boolean = false

    private val _servingSource = MutableStateFlow(source)

    /**
     * The source the pages currently in hand came from, which is the one their images are
     * fetched against. Starts as [source] and moves on as sources fail — see [switchSource].
     *
     * A flow rather than a plain field because the reader shows it, and the switch can happen
     * several pages into a chapter that is already on screen.
     */
    val servingSource: StateFlow<HttpSource> = _servingSource.asStateFlow()

    private var activeSource: HttpSource
        get() = _servingSource.value
        set(value) {
            _servingSource.value = value
        }

    /** Sources already known to be broken for this chapter, so [switchSource] never retries one. */
    private val triedSources = mutableSetOf<Long>()

    /**
     * Whether this manga is merged, and so has somewhere else to be read from.
     *
     * Drives whether the error view offers to switch: on an unmerged manga the button would never
     * have anywhere to go. Known only once [getPages] has run, which is before anything can fail.
     */
    var canSwitchSource: Boolean = false
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * A queue used to manage requests one by one while allowing priorities.
     */
    private val queue = PriorityBlockingQueue<PriorityPage>()

    private val preloadSize = preferences.preloadSize().get()

    init {
        scope.launchIO {
            flow {
                while (true) {
                    emit(runInterruptible { queue.take() }.page)
                }
            }
                .filter { it.status is Page.State.Queue }
                .collect {
                    _loadPage(it)
                }
        }
    }

    /**
     * Recycles this loader and the active subscriptions and queue.
     */
    override fun recycle() {
        super.recycle()
        scope.cancel()
        queue.clear()

        // Cache current page list progress for online chapters to allow a faster reopen
        chapter.pages?.let { pages ->
            launchIO {
                try {
                    // Convert to pages without reader information
                    val pagesToSave = pages.map { Page(it.index, it.url, it.imageUrl) }
                    chapterCache.putPageListToCache(chapter.chapter, pagesToSave)
                } catch (e: Throwable) {
                    if (e is CancellationException) {
                        throw e
                    }
                }
            }
        }
    }

    /**
     * Returns the page list for a chapter. It tries to return the page list from the local cache,
     * otherwise fallbacks to network.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val pages = try {
            chapterCache.getPageListFromCache(chapter.chapter)
        } catch (e: Throwable) {
            if (e is CancellationException) {
                throw e
            }
            when (val mangaId = chapter.chapter.manga_id) {
                // Never persisted, so it can't have merged sources to fall back to.
                null -> source.getPageList(chapter.chapter)
                else -> mergedSourceFallback.getPages(mangaId, chapter.chapter, source)
                    .also { activeSource = it.source }
                    .pages
            }
        }
        triedSources += activeSource.id
        canSwitchSource = chapter.chapter.manga_id?.let { mergedSourceFallback.hasAlternates(it) } == true
        return pages.mapIndexed { index, page ->
            // Don't trust sources and use our own indexing
            ReaderPage(index, page.url, page.imageUrl)
        }
    }

    /**
     * Loads a page through the queue. Handles re-enqueueing pages if they were evicted from the cache.
     */
    override suspend fun loadPage(page: ReaderPage) = withIOContext {
        val imageUrl = page.imageUrl

        // Check if the image has been deleted
        if (page.status is Page.State.Ready && imageUrl != null && !chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.Queue
        }

        // Automatically retry failed pages when subscribed to this page
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }

        val queuedPages = mutableListOf<PriorityPage>()
        if (page.status is Page.State.Queue) {
            queuedPages += PriorityPage(page, 1).also { queue.offer(it) }
        }
        queuedPages += preloadNextPages(page, preloadSize)

        suspendCancellableCoroutine<Nothing> { continuation ->
            continuation.invokeOnCancellation {
                queuedPages.forEach {
                    if (it.page.status is Page.State.Queue) {
                        queue.remove(it)
                    }
                }
            }
        }
    }

    /**
     * Preloads the given [amount] of pages after the [currentPage] with a lower priority.
     * @return a list of [PriorityPage] that were added to the [queue]
     */
    private fun preloadNextPages(currentPage: ReaderPage, amount: Int): List<PriorityPage> {
        val pageIndex = currentPage.index
        val pages = currentPage.chapter.pages ?: return emptyList()
        if (pageIndex == pages.lastIndex) return emptyList()

        return pages
            .subList(pageIndex + 1, min(pageIndex + 1 + amount, pages.size))
            .mapNotNull {
                if (it.status is Page.State.Queue) {
                    PriorityPage(it, 0).apply { queue.offer(this) }
                } else {
                    null
                }
            }
    }

    /**
     * Retries a page. This method is only called from user interaction on the viewer.
     */
    override fun retryPage(page: ReaderPage) {
        if (page.status is Page.State.Error) {
            page.status = Page.State.Queue
        }
        queue.offer(PriorityPage(page, 2))
    }

    /**
     * Data class used to keep ordering of pages in order to maintain priority.
     */
    private class PriorityPage(
        val page: ReaderPage,
        val priority: Int,
    ) : Comparable<PriorityPage> {
        companion object {
            private val idGenerator = AtomicInteger()
        }

        private val identifier = idGenerator.incrementAndGet()

        override fun compareTo(other: PriorityPage): Int {
            val p = other.priority.compareTo(priority)
            return if (p != 0) p else identifier.compareTo(other.identifier)
        }
    }

    /**
     * Loads the page, retrieving the image URL and downloading the image if necessary.
     * Downloaded images are stored in the chapter cache.
     *
     * A source that can hand out a page list and then fail on the images it points at is the
     * common case this guards against — a dead image host, hotlink protection, an error page
     * served with a 200. Anything that goes wrong here moves the whole chapter onto the next
     * source in the same language rather than leaving the reader on a broken page.
     *
     * @param page the page whose source image has to be downloaded.
     */
    private suspend fun _loadPage(page: ReaderPage) {
        while (true) {
            try {
                loadPageFrom(activeSource, page)
                return
            } catch (e: CancellationException) {
                page.status = Page.State.Error
                throw e
            } catch (e: Throwable) {
                Logger.w(e) { "Page ${page.number} failed on ${activeSource.name}" }
                if (!switchSource()) {
                    page.status = Page.State.Error
                    return
                }
            }
        }
    }

    private suspend fun loadPageFrom(source: HttpSource, page: ReaderPage) {
        if (page.imageUrl.isNullOrEmpty()) {
            page.status = Page.State.LoadPage
            page.imageUrl = source.getImageUrl(page)
        }
        val imageUrl = page.imageUrl!!

        if (!chapterCache.isImageInCache(imageUrl)) {
            page.status = Page.State.DownloadImage
            val imageResponse = source.getImage(page)
            chapterCache.putImageToCache(imageUrl, imageResponse)
        }

        page.stream = { chapterCache.getImageFile(imageUrl).inputStream() }
        page.status = Page.State.Ready
    }

    /**
     * Switches source because the user asked, not because a page threw.
     *
     * The automatic switch only fires on an error, which leaves the case where the source answers
     * fine but serves the wrong thing — pages out of order, a watermark over everything, a scan so
     * bad it's unreadable. Nothing here can detect that; the user can.
     *
     * @return the source now being read, or null when there was nowhere else to go.
     */
    suspend fun switchSourceManually(page: ReaderPage): HttpSource? {
        if (!switchSource()) return null
        retryPage(page)
        return activeSource
    }

    /** Moves this chapter onto the next source that can serve it, and says whether there was one. */
    private suspend fun switchSource(): Boolean {
        val mangaId = chapter.chapter.manga_id ?: return false
        val pages = chapter.pages ?: return false
        val next = mergedSourceFallback
            .switchSource(mangaId, chapter.chapter, source, pages, triedSources)
            ?: return false
        activeSource = next
        return true
    }
}
