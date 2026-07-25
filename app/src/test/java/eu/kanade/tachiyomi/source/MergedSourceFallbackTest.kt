package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.SChapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class MergedSourceFallbackTest {

    @Test
    fun `finds the same chapter number on another source`() {
        val candidates = listOf(
            chapter("Chapter 10", "/other/10"),
            chapter("Chapter 11", "/other/11"),
        )
        assertEquals("/other/11", matchChapter(candidates, 11f, "Some Manga")?.url)
    }

    @Test
    fun `matches even though the other source names its chapters differently`() {
        val candidates = listOf(chapter("Some Manga Ch. 11 - The Duel", "/other/11"))
        assertEquals("/other/11", matchChapter(candidates, 11f, "Some Manga")?.url)
    }

    @Test
    fun `honours the number the source declares over the one in the name`() {
        val candidates = listOf(chapter("Untitled", "/other/11", declaredNumber = 11f))
        assertEquals("/other/11", matchChapter(candidates, 11f, "Some Manga")?.url)
    }

    @Test
    fun `matches decimal chapters that do not survive a float round trip`() {
        val candidates = listOf(chapter("Chapter 11.1", "/other/11.1"))
        val target = 11.1.toFloat() // as it comes back from the REAL column
        assertEquals("/other/11.1", matchChapter(candidates, target, "Some Manga")?.url)
    }

    @Test
    fun `returns null when the other source does not have the chapter`() {
        val candidates = listOf(chapter("Chapter 10", "/other/10"))
        assertNull(matchChapter(candidates, 11f, "Some Manga"))
    }

    @Test
    fun `refuses to guess when the chapter number is unrecognised`() {
        val candidates = listOf(chapter("Chapter 10", "/other/10"))
        assertNull(matchChapter(candidates, -1f, "Some Manga"))
    }

    @Test
    fun `returns null when the other source has no chapters at all`() {
        assertNull(matchChapter(emptyList(), 11f, "Some Manga"))
    }

    private fun chapter(
        name: String,
        url: String,
        declaredNumber: Float = -1f,
    ): SChapter = SChapter.create().apply {
        this.name = name
        this.url = url
        this.chapter_number = declaredNumber
    }
}
