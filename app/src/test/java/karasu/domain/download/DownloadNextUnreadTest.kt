package karasu.domain.download

import eu.kanade.tachiyomi.data.database.models.Chapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DownloadNextUnreadTest {

    private fun chapter(id: Long, read: Boolean) = Chapter.create().also { it.id = id; it.read = read; it.url = "c$id"; it.name = "c$id" }

    @Test
    fun `keeps the last two read and deletes every older read one`() {
        // 3 was skipped, 5 is being finished now and is not marked read in the database yet.
        val chapters = listOf(chapter(1, true), chapter(2, true), chapter(3, false), chapter(4, true), chapter(5, false), chapter(6, true))
        assertEquals(listOf(1L, 2L), readChaptersToRemove(chapters, currentId = 5, keep = 2).map { it.id })
        assertEquals(listOf(1L, 2L, 4L, 5L), readChaptersToRemove(chapters, currentId = 5, keep = 0).map { it.id })
        assertEquals(emptyList<Long>(), readChaptersToRemove(chapters, currentId = 99, keep = 2).map { it.id })
    }

    @Test
    fun `downloads the next unread after the last read, skipping what is in hand`() {
        val chapters = listOf(chapter(1, false), chapter(2, true), chapter(3, false), chapter(4, false), chapter(5, false))
        // 1 was skipped long ago and is not "next"; 3 is already downloaded.
        assertEquals(listOf(4L), nextUnreadToDownload(chapters, ahead = 2) { it.id == 3L }.map { it.id })
        // Finishing 3 moves the window one chapter on.
        assertEquals(listOf(4L, 5L), nextUnreadToDownload(chapters, ahead = 2, justRead = 3) { false }.map { it.id })
        // Nothing read yet: from the start.
        assertEquals(listOf(1L, 2L), nextUnreadToDownload(chapters.map { chapter(it.id!!, false) }, ahead = 2) { false }.map { it.id })
    }
}
