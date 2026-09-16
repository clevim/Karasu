package karasu.domain.manga.interactor

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.MangaImpl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GetLibraryMangaRecountTest {

    @Test
    fun `counts the chapters a merged source lent, which library_view cannot see`() {
        // library_view only ever saw the primary's 10 chapters, all read.
        val row = LibraryManga(manga = MangaImpl(id = 1L), totalChapters = 10, read = 10, unread = 0)
        val merged = (1..10).map { chapter(it.toFloat(), read = true) } +
            (11..13).map { chapter(it.toFloat(), read = false) }

        val recounted = row.recount(merged)

        assertEquals(13, recounted.totalChapters)
        assertEquals(3, recounted.unread, "the merged source's chapters are unread chapters")
        assertEquals(10, recounted.read)
    }

    @Test
    fun `keeps the view's dates when the chapters carry none`() {
        val row = LibraryManga(manga = MangaImpl(id = 1L), latestUpdate = 500L, lastFetch = 600L)

        val recounted = row.recount(emptyList())

        assertEquals(500L, recounted.latestUpdate)
        assertEquals(600L, recounted.lastFetch)
        assertEquals(0, recounted.unread)
    }

    private fun chapter(number: Float, read: Boolean): Chapter = Chapter.create().apply {
        url = "c/$number"
        name = "Chapter $number"
        chapter_number = number
        this.read = read
    }
}
