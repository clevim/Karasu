package karasu.domain.download

import eu.kanade.tachiyomi.data.database.models.Chapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CleanOldDownloadsTest {

    private fun chapter(id: Long, read: Boolean) = Chapter.create().also { it.id = id; it.read = read; it.url = "c$id"; it.name = "c$id" }

    @Test
    fun `only read chapters with a known, old enough read date go`() {
        val chapters = listOf(chapter(1, true), chapter(2, true), chapter(3, false), chapter(4, true))
        val readAt = mapOf(1L to 100L, 2L to 900L, 3L to 100L) // 4 was marked read by hand: no date
        assertEquals(listOf(1L), readLongAgo(chapters, readAt, cutoff = 500L).map { it.id })
    }
}
