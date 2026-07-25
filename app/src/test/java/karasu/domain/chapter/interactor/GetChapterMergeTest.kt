package karasu.domain.chapter.interactor

import eu.kanade.tachiyomi.data.database.models.Chapter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GetChapterMergeTest {

    @Test
    fun `adds only the chapters the primary source does not have`() {
        val own = chapters("a", 0..15)
        val other = chapters("b", 0..20)

        val merged = mergeChapters(own, listOf(0 to other))

        assertEquals(21, merged.size, "one row per chapter number, no duplicates")
        // 0-15 stay on the primary, only 16-20 are borrowed.
        assertEquals(
            (0..15).map { "a/$it" }.toSet(),
            merged.filter { it.chapter_number <= 15f }.map { it.url }.toSet(),
        )
        assertEquals(
            (16..20).map { "b/$it" }.toSet(),
            merged.filter { it.chapter_number > 15f }.map { it.url }.toSet(),
        )
    }

    @Test
    fun `keeps a chapter read when only the other source's row was read`() {
        val own = chapters("a", 5..5)
        val other = chapters("b", 5..5).onEach { it.read = true; it.bookmark = true }

        val merged = mergeChapters(own, listOf(0 to other))

        assertEquals(1, merged.size)
        assertTrue(merged.single().read, "read must survive whichever row wins")
        assertTrue(merged.single().bookmark, "bookmark must survive whichever row wins")
        assertEquals("a/5", merged.single().url, "the primary's row still wins")
    }

    @Test
    fun `read state survives reordering the sources`() {
        val own = chapters("a", 5..5).onEach { it.read = true }
        val other = chapters("b", 5..5)

        // Whoever wins the tie, the chapter stays read.
        assertTrue(mergeChapters(own, listOf(0 to other)).single().read)
        assertTrue(mergeChapters(other, listOf(0 to own)).single().read)
    }

    @Test
    fun `lower priority number wins between two merged sources`() {
        val own = chapters("a", 0..0)
        val second = chapters("b", 7..7)
        val third = chapters("c", 7..7)

        val merged = mergeChapters(own, listOf(2 to third, 1 to second))

        assertEquals("b/7", merged.first { it.chapter_number == 7f }.url)
    }

    @Test
    fun `drops unnumbered chapters coming from merged sources but keeps its own`() {
        val own = chapters("a", 1..1) + listOf(chapter("a/extra", -1f))
        val other = chapters("b", 1..1) + listOf(chapter("b/extra", -1f))

        val merged = mergeChapters(own, listOf(0 to other))

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.url == "a/extra" }, "own unnumbered chapters are kept")
        assertFalse(merged.any { it.url == "b/extra" }, "unnumbered rows cannot be deduplicated")
    }

    @Test
    fun `matches decimal chapters that do not survive a float round trip`() {
        val own = listOf(chapter("a/11.1", 11.1f))
        val other = listOf(chapter("b/11.1", 11.1.toFloat()))

        assertEquals(1, mergeChapters(own, listOf(0 to other)).size)
    }

    @Test
    fun `returns the original list untouched when nothing is merged`() {
        val own = chapters("a", 0..3)
        val merged = mergeChapters(own, emptyList())

        assertSame(own, merged, "unmerged manga must not pay for copies or sorting")
    }

    @Test
    fun `does not mutate the stored rows it merges`() {
        val own = chapters("a", 5..5)
        val other = chapters("b", 5..5).onEach { it.read = true }
        val ownRow = own.single()
        val ownOrder = ownRow.source_order

        mergeChapters(own, listOf(0 to other))

        assertFalse(ownRow.read, "the database row must not be flipped by a read-time merge")
        assertEquals(ownOrder, ownRow.source_order)
    }

    private fun chapters(prefix: String, range: IntRange): List<Chapter> =
        range.map { chapter("$prefix/$it", it.toFloat()) }

    private fun chapter(url: String, number: Float): Chapter = Chapter.create().apply {
        this.url = url
        this.name = "Chapter $number"
        this.chapter_number = number
    }
}
