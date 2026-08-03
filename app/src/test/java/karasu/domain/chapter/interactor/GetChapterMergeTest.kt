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

        val merged = mergeChapters(own, "en", listOf(MergedChapters(0, "en", other)))

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

        val merged = mergeChapters(own, "en", listOf(MergedChapters(0, "en", other)))

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
        assertTrue(mergeChapters(own, "en", listOf(MergedChapters(0, "en", other))).single().read)
        assertTrue(mergeChapters(other, "en", listOf(MergedChapters(0, "en", own))).single().read)
    }

    @Test
    fun `lower priority number wins between two merged sources`() {
        val own = chapters("a", 0..0)
        val second = chapters("b", 7..7)
        val third = chapters("c", 7..7)

        val merged = mergeChapters(
            own,
            "en",
            listOf(MergedChapters(2, "en", third), MergedChapters(1, "en", second)),
        )

        assertEquals("b/7", merged.first { it.chapter_number == 7f }.url)
    }

    @Test
    fun `collapses another language into one row that keeps both translations`() {
        val own = chapters("a", 1..3)
        val other = chapters("b", 1..3)

        val merged = mergeChapters(own, "en", listOf(MergedChapters(0, "pt-BR", other)))

        assertEquals(3, merged.size, "the same chapter number is one row whoever translated it")
        val first = merged.first { it.chapter_number == 1f }
        assertEquals("a/1", first.url, "the manga's own language wins the row")
        assertEquals(
            listOf("a/1", "b/1"),
            first.alternates.map { it.url },
            "both translations stay reachable, own language first",
        )
    }

    @Test
    fun `does not offer a choice between two sources in the same language`() {
        val own = chapters("a", 5..5)
        val other = chapters("b", 5..5)

        val merged = mergeChapters(own, "en", listOf(MergedChapters(0, "en", other)))

        assertTrue(merged.single().alternates.isEmpty(), "same language is the same read")
    }

    @Test
    fun `a chapter only one source has offers no choice`() {
        val own = chapters("a", 1..1)
        val other = chapters("b", 2..2)

        val merged = mergeChapters(own, "en", listOf(MergedChapters(0, "pt-BR", other)))

        assertTrue(merged.all { it.alternates.isEmpty() })
    }

    @Test
    fun `read state carries across languages`() {
        val own = chapters("a", 5..5)
        val other = chapters("b", 5..5).onEach { it.read = true }

        val merged = mergeChapters(own, "en", listOf(MergedChapters(0, "pt-BR", other)))

        assertTrue(merged.single().read, "reading the chapter in any language reads the chapter")
    }

    @Test
    fun `drops unnumbered chapters coming from merged sources but keeps its own`() {
        val own = chapters("a", 1..1) + listOf(chapter("a/extra", -1f))
        val other = chapters("b", 1..1) + listOf(chapter("b/extra", -1f))

        val merged = mergeChapters(own, "en", listOf(MergedChapters(0, "en", other)))

        assertEquals(2, merged.size)
        assertTrue(merged.any { it.url == "a/extra" }, "own unnumbered chapters are kept")
        assertFalse(merged.any { it.url == "b/extra" }, "unnumbered rows cannot be deduplicated")
    }

    @Test
    fun `matches decimal chapters that do not survive a float round trip`() {
        val own = listOf(chapter("a/11.1", 11.1f))
        val other = listOf(chapter("b/11.1", 11.1.toFloat()))

        assertEquals(1, mergeChapters(own, "en", listOf(MergedChapters(0, "en", other))).size)
    }

    @Test
    fun `returns the original list untouched when nothing is merged`() {
        val own = chapters("a", 0..3)
        val merged = mergeChapters(own, "en", emptyList())

        assertSame(own, merged, "unmerged manga must not pay for copies or sorting")
    }

    @Test
    fun `does not mutate the stored rows it merges`() {
        val own = chapters("a", 5..5)
        val other = chapters("b", 5..5).onEach { it.read = true }
        val ownRow = own.single()
        val ownOrder = ownRow.source_order

        mergeChapters(own, "en", listOf(MergedChapters(0, "en", other)))

        assertFalse(ownRow.read, "the database row must not be flipped by a read-time merge")
        assertEquals(ownOrder, ownRow.source_order)
    }

    @Test
    fun `opening another language swaps that row in without moving it`() {
        val own = chapters("a", 1..2).onEachIndexed { i, it -> it.id = i.toLong() }
        val other = chapters("b", 1..2).onEachIndexed { i, it -> it.id = 10L + i }

        val merged = mergeChapters(own, "en", listOf(MergedChapters(0, "pt-BR", other)))
        val swapped = merged.withAlternate(11L)

        assertEquals(merged.size, swapped.size)
        assertEquals("b/2", swapped.first { it.chapter_number == 2f }.url, "the picked translation")
        assertEquals("a/1", swapped.first { it.chapter_number == 1f }.url, "the other rows stay")
        assertEquals(
            merged.map { it.source_order },
            swapped.map { it.source_order },
            "the row keeps its place in the list",
        )
        assertSame(merged, merged.withAlternate(0L), "a chapter already in the list costs nothing")
    }

    private fun chapters(prefix: String, range: IntRange): List<Chapter> =
        range.map { chapter("$prefix/$it", it.toFloat()) }

    private fun chapter(url: String, number: Float): Chapter = Chapter.create().apply {
        this.url = url
        this.name = "Chapter $number"
        this.chapter_number = number
    }
}
