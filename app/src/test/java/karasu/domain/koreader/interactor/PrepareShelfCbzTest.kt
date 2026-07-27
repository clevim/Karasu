package karasu.domain.koreader.interactor

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PrepareShelfCbzTest {

    @Test
    fun `a page-shaped image is still cut into at least two pieces`() {
        // Only reached for images past the tall threshold, so one piece would mean no split at all.
        assertEquals(1, evenCuts(800, 2100).size)
    }

    @Test
    fun `a long strip becomes pages of roughly the screen's shape`() {
        val height = 12000
        val cuts = evenCuts(800, height)
        val pages = (listOf(0) + cuts + listOf(height)).zipWithNext()

        assertEquals(11, pages.size, "12000px at ~1120px per page")
        pages.forEach { (top, bottom) ->
            val ratio = (bottom - top) / 800f
            assertTrue(ratio in 0.9f..1.5f, "page ratio $ratio should be readable on a screen")
        }
    }

    @Test
    fun `cuts are strictly increasing and inside the image`() {
        val cuts = evenCuts(1080, 30000)
        assertEquals(cuts.sorted(), cuts)
        assertEquals(cuts.distinct().size, cuts.size)
        assertTrue(cuts.all { it in 1 until 30000 })
    }

    @Test
    fun `the gutter wins over the middle of the window`() {
        // A flat row three rows off centre beats busy rows everywhere else.
        val spreads = IntArray(21) { 200 }
        spreads[13] = 0

        assertEquals(13, flattestRow(spreads))
    }

    @Test
    fun `an evenly busy window falls back to its middle`() {
        assertEquals(10, flattestRow(IntArray(21) { 200 }))
    }

    @Test
    fun `distance only decides between rows that are equally flat`() {
        val spreads = IntArray(41) { 200 }
        // Two equally clean rows: the one nearer the even split is the one to cut on.
        spreads[12] = 0
        spreads[35] = 0

        assertEquals(12, flattestRow(spreads))
    }
}
