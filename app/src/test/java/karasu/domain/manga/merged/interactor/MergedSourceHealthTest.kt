package karasu.domain.manga.merged.interactor

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MergedSourceHealthTest {

    @Test
    fun `same manga on two sources shares chapter numbers`() {
        assertFalse(looksLikeWrongManga(own = (1..15).floats(), theirs = (1..20).floats()))
    }

    @Test
    fun `a source carrying only the newer chapters is not wrong, just useful`() {
        assertFalse(looksLikeWrongManga(own = (1..15).floats(), theirs = (16..20).floats()))
    }

    @Test
    fun `overlapping ranges with no shared number is the wrong entry`() {
        // A spin-off numbered 1-10 merged onto a manga whose chapters are 1_5, 2_5, 3_5...
        assertTrue(looksLikeWrongManga(own = listOf(1.5f, 2.5f, 3.5f), theirs = listOf(1f, 2f, 3f)))
    }

    @Test
    fun `one shared number is enough to believe it`() {
        assertFalse(looksLikeWrongManga(own = listOf(1.5f, 2.5f, 3f), theirs = listOf(1f, 2f, 3f)))
    }

    @Test
    fun `decimals that do not survive a float round trip still match`() {
        val stored = 11.1.toFloat() // as it comes back from the REAL column
        assertFalse(looksLikeWrongManga(own = listOf(11.1f), theirs = listOf(stored)))
    }

    @Test
    fun `an empty side proves nothing`() {
        assertFalse(looksLikeWrongManga(own = emptyList(), theirs = listOf(1f)))
        assertFalse(looksLikeWrongManga(own = listOf(1f), theirs = emptyList()))
    }

    private fun IntRange.floats() = map { it.toFloat() }
}
