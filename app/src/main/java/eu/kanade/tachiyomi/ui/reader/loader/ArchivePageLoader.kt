package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.ImageUtil
import karasu.core.archive.ArchiveReader
import karasu.translation.ChapterTranslator
import karasu.translation.model.PageTranslation

/**
 * Loader used to load a chapter from an archive file.
 */
internal class ArchivePageLoader(
    private val reader: ArchiveReader,
    private val translations: Map<String, PageTranslation> = emptyMap(),
) : PageLoader() {

    override val isLocal: Boolean = true

    /**
     * Recycles this loader and the open archive.
     */
    override fun recycle() {
        super.recycle()
        reader.close()
    }

    /**
     * Returns the pages found on this archive ordered with a natural comparator.
     */
    override suspend fun getPages(): List<ReaderPage> = reader.useEntries { entries ->
        entries
            .filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
            .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }
            .mapIndexed { i, entry ->
                ReaderPage(i).apply {
                    stream = { reader.getInputStream(entry.name)!! }
                    // By name, or by position for a chapter translated before it was downloaded.
                    translation = translations[entry.name]
                        ?: translations[ChapterTranslator.onlinePageKey(i)]
                    translationKey = if (entry.name in translations || translation == null) entry.name else ChapterTranslator.onlinePageKey(i)
                    status = Page.State.Ready
                }
            }
            .toList()
    }

    /**
     * No additional action required to load the page
     */
    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
    }
}
