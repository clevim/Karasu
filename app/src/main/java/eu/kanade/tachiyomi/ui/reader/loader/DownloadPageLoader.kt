package eu.kanade.tachiyomi.ui.reader.loader

import android.app.Application
import android.net.Uri
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import uy.kohesive.injekt.injectLazy
import karasu.core.archive.util.archiveReader
import karasu.translation.ChapterTranslator
import karasu.translation.TranslationManager
import karasu.translation.model.PageTranslation

/**
 * Loader used to load a chapter from the downloaded chapters.
 */
class DownloadPageLoader(
    private val chapter: ReaderChapter,
    private val manga: Manga,
    private val source: Source,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
) : PageLoader() {

    override val isLocal: Boolean = true

    // Needed to open input streams
    private val context: Application by injectLazy()

    private val translationManager: TranslationManager by injectLazy()

    private var archivePageLoader: ArchivePageLoader? = null

    override fun recycle() {
        super.recycle()
        archivePageLoader?.recycle()
    }

    /**
     * Returns the pages found on this downloaded chapter.
     */
    override suspend fun getPages(): List<ReaderPage> {
        val dbChapter = chapter.chapter
        val chapterPath = downloadProvider.findChapterDir(dbChapter, manga, source)
        val translations = translationManager.getChapterTranslation(manga, dbChapter, source)
        return if (chapterPath?.isFile == true) {
            getPagesFromArchive(chapterPath, translations)
        } else {
            getPagesFromDirectory(translations)
        }
    }

    private suspend fun getPagesFromArchive(
        chapterPath: UniFile,
        translations: Map<String, PageTranslation>,
    ): List<ReaderPage> {
        val loader = ArchivePageLoader(chapterPath.archiveReader(context), translations)
            .also { archivePageLoader = it }
        return loader.getPages()
    }

    private fun getPagesFromDirectory(translations: Map<String, PageTranslation>): List<ReaderPage> {
        val pages = downloadManager.buildPageList(source, manga, chapter.chapter)
        return pages.map { page ->
            ReaderPage(page.index, page.url, page.imageUrl, stream = {
                context.contentResolver.openInputStream(page.uri ?: Uri.EMPTY)!!
            },).apply {
                // On SAF storage lastPathSegment is the whole document path ("primary:Download/
                // manga/001.jpg"), not just the file name the translation is keyed by.
                // By name, or by position for a chapter translated before it was downloaded.
                val name = page.uri?.lastPathSegment?.substringAfterLast('/')
                translationKey = if (name in translations || name == null) name else ChapterTranslator.onlinePageKey(index)
                translation = translations[name] ?: translations[ChapterTranslator.onlinePageKey(index)]
                status = Page.State.Ready
            }
        }
    }

    override suspend fun loadPage(page: ReaderPage) {
        archivePageLoader?.loadPage(page)
    }
}
