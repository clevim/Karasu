package karasu.domain.koreader.models

import eu.kanade.tachiyomi.data.database.models.Chapter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class KoreaderShelfEntryTest {

    private val entry = KoreaderShelfEntry(
        chapterId = 4821L,
        chapterUrl = "/manga/one-piece/chapter-1090",
        read = true,
    )

    private fun chapter(url: String, read: Boolean = false): Chapter = Chapter.create().apply {
        this.url = url
        this.read = read
    }

    @Test
    fun `a finished chapter the shelf still recognises is marked read`() {
        assertTrue(entry.canMarkRead(chapter(entry.chapterUrl)))
    }

    @Test
    fun `a restore that reuses the id must not mark an unrelated chapter read`() {
        // Same row id, completely different chapter: this is what a backup restore looks like.
        assertFalse(entry.canMarkRead(chapter("/manga/naruto/chapter-3")))
    }

    @Test
    fun `an entry the plugin has not finished changes nothing`() {
        assertFalse(entry.copy(read = false).canMarkRead(chapter(entry.chapterUrl)))
    }

    @Test
    fun `a chapter already read is not updated again`() {
        assertFalse(entry.canMarkRead(chapter(entry.chapterUrl, read = true)))
    }

    @Test
    fun `an entry whose chapter is gone is skipped instead of crashing`() {
        assertFalse(entry.canMarkRead(null))
    }
}
