package karasu.domain.chapter.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterGapsTest {

    @Test
    fun `a complete run has no gaps`() {
        assertEquals(emptyList<ChapterGap>(), findChapterGaps(floats(1, 2, 3, 4, 5)))
    }

    @Test
    fun `one missing chapter`() {
        assertEquals(listOf(ChapterGap(23, 23)), findChapterGaps(floats(22, 24)))
    }

    @Test
    fun `a run of missing chapters is one gap`() {
        assertEquals(listOf(ChapterGap(47, 49)), findChapterGaps(floats(46, 50)))
        assertEquals(3, findChapterGaps(floats(46, 50)).single().size)
    }

    @Test
    fun `several gaps are reported in order`() {
        assertEquals(
            listOf(ChapterGap(2, 2), ChapterGap(5, 6)),
            findChapterGaps(floats(1, 3, 4, 7)),
        )
    }

    @Test
    fun `extras do not create gaps and do not fill them`() {
        // 11.5 belongs to 11; 13 is still missing.
        assertEquals(listOf(ChapterGap(13, 13)), findChapterGaps(listOf(11f, 11.5f, 12f, 14f)))
    }

    @Test
    fun `not starting at chapter one is not a gap`() {
        assertEquals(emptyList<ChapterGap>(), findChapterGaps(floats(40, 41, 42)))
    }

    @Test
    fun `unrecognised numbers are ignored`() {
        assertEquals(emptyList<ChapterGap>(), findChapterGaps(listOf(-1f, 1f, 2f)))
    }

    @Test
    fun `a single chapter cannot have gaps`() {
        assertEquals(emptyList<ChapterGap>(), findChapterGaps(floats(7)))
        assertEquals(emptyList<ChapterGap>(), findChapterGaps(emptyList<Int>().map { it.toFloat() }))
    }

    @Test
    fun `duplicates from merged sources do not confuse it`() {
        assertEquals(listOf(ChapterGap(2, 2)), findChapterGaps(floats(1, 1, 3, 3)))
    }

    private fun floats(vararg numbers: Int) = numbers.map { it.toFloat() }
}
