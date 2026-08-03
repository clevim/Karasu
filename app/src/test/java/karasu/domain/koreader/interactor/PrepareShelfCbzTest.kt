package karasu.domain.koreader.interactor

import eu.kanade.tachiyomi.util.system.gutterRow
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
    fun `a real gutter beats a lone flat row nearer the split`() {
        // A single uniform row is as likely to be the inside of a speech balloon as a gap, so a
        // band thick enough to be a gap between panels wins even from further away.
        val (shades, noise) = art(21).flat(10..10, WHITE).flat(14..17, WHITE)

        assertEquals(15, gutterRow(shades, noise, WIDTH, 10))
    }

    @Test
    fun `the gutter band nearest the even split wins`() {
        val (shades, noise) = art(21).flat(4..7, WHITE).flat(14..18, WHITE)

        assertEquals(16, gutterRow(shades, noise, WIDTH, 15))
    }

    @Test
    fun `a gap between panels on a dark strip is still a gutter`() {
        val (shades, noise) = art(21).flat(8..12, BLACK)

        assertEquals(10, gutterRow(shades, noise, WIDTH, 10))
    }

    @Test
    fun `flat rows of different shades are separate bands`() {
        // A panel edge: white above, black below. Four flat rows touching, but no four-row gap, so
        // the real gutter further down is the one to cut on.
        val (shades, noise) = art(21).flat(8..9, WHITE).flat(10..11, BLACK).flat(15..18, WHITE)

        assertEquals(16, gutterRow(shades, noise, WIDTH, 10))
    }

    @Test
    fun `a gutter with a speck of dirt in it is still a gutter`() {
        // The reason cuts kept landing in the art: a watermark, a page number or a JPEG ring along
        // one panel border was enough to disqualify a gap that is otherwise entirely empty.
        val (shades, noise) = art(21).flat(8..12, WHITE, dirt = WIDTH / 100)

        assertEquals(10, gutterRow(shades, noise, WIDTH, 10))
    }

    @Test
    fun `with no gutter anywhere the emptiest row is cut instead`() {
        // No gap wide enough to be a gutter, but row 13 is background with a corner of panel in it,
        // and clipping that corner beats cutting row 10 straight through a face.
        val (shades, noise) = art(21).flat(13..13, WHITE, dirt = WIDTH / 20)

        assertEquals(13, gutterRow(shades, noise, WIDTH, 10))
    }

    @Test
    fun `an entirely busy window has no gutter to offer`() {
        val (shades, noise) = art(21)

        assertEquals(-1, gutterRow(shades, noise, WIDTH, 10))
    }

    @Test
    fun `pages are cut to the shape of the chosen screen`() {
        // A Paperwhite 5 is 1236x1648, so its pages are its own shape rather than a generic 1_4.
        val cuts = evenCuts(1236, 8000, 1648f / 1236f)

        assertEquals(listOf(1600, 3200, 4800, 6400), cuts)
    }

    @Test
    fun `a small screen gets pages that fit it rather than generic ones`() {
        // A 600x800 Kindle. Cut to the generic 1.4 every page arrives taller than the screen and
        // KOReader shrinks the lot, which is the "low quality" that started all of this.
        val height = 12000
        val pages = (listOf(0) + evenCuts(800, height, 800f / 600f) + listOf(height)).zipWithNext()

        pages.forEach { (top, bottom) ->
            val ratio = (bottom - top) / 800f
            assertTrue(ratio <= 800f / 600f, "page ratio $ratio overflows a 1.33 screen")
        }
    }

    @Test
    fun `the margin around a page is cropped away`() {
        val page = Page(100, 100, WHITE).ink(20, 30, 80, 70)

        // Capped at a tenth per side: the ink starts further in than that, and the rest of the gap
        // stays because a wide margin can be part of the composition.
        assertEquals(Box(10, 10, 90, 90), page.box())
    }

    @Test
    fun `a page that barely has a margin keeps it`() {
        val page = Page(100, 100, WHITE).ink(5, 8, 95, 92)

        assertEquals(Box(5, 8, 95, 92), page.box())
    }

    @Test
    fun `a speck of dirt on the edge does not stop the crop`() {
        val page = Page(100, 100, WHITE).ink(30, 30, 70, 70).ink(1, 1, 2, 2)

        // Without the edge check that lone dark pixel pins the box at 1 and nothing is cropped.
        assertEquals(Box(10, 10, 90, 90), page.box())
    }

    @Test
    fun `a page drawn on black is cropped to its ink and not its border`() {
        val page = Page(100, 100, BLACK).ink(20, 30, 80, 70, WHITE)

        assertEquals(false, isLightBackground(page.pixels, 100, 100))
        assertEquals(Box(10, 10, 90, 90), page.box())
    }

    @Test
    fun `a blank page is left whole`() {
        val page = Page(100, 100, WHITE)

        assertEquals(Box(0, 0, 100, 100), page.box())
    }

    @Test
    fun `a washed-out scan is stretched back to the full range`() {
        val stretch = contrastStretch(Page(10, 10, 40).ink(0, 0, 10, 5, 200).pixels)!!

        assertEquals(255f / 160f, stretch[0], 0.001f)
        assertEquals(-40f * (255f / 160f), stretch[1], 0.001f)
    }

    @Test
    fun `a page already using the full range is left alone`() {
        assertEquals(null, contrastStretch(Page(10, 10, 0).ink(0, 0, 10, 5, 255).pixels))
    }

    @Test
    fun `a deliberately flat page is left alone`() {
        // 100 to 150 is far too narrow to be a scanning accident.
        assertEquals(null, contrastStretch(Page(10, 10, 100).ink(0, 0, 10, 5, 150).pixels))
    }

    private class Page(val width: Int, val height: Int, background: Int) {
        val pixels = IntArray(width * height) { gray(background) }

        fun ink(left: Int, top: Int, right: Int, bottom: Int, level: Int = BLACK) = also {
            for (y in top until bottom) {
                for (x in left until right) pixels[y * width + x] = gray(level)
            }
        }

        fun box() = contentBox(pixels, width, height, isLightBackground(pixels, width, height))
    }

    private companion object {
        const val WHITE = 250
        const val BLACK = 5
        const val WIDTH = 1000

        fun gray(level: Int) = (0xFF shl 24) or (level shl 16) or (level shl 8) or level

        /** A scanned strip where every row is half ink and half page, as artwork is. */
        fun art(rows: Int) = IntArray(rows) { WHITE } to IntArray(rows) { WIDTH / 2 }

        /** Turns rows into empty space of one shade, give or take [dirt] pixels of something else. */
        fun Pair<IntArray, IntArray>.flat(rows: IntRange, level: Int, dirt: Int = 0) = also {
            rows.forEach { first[it] = level; second[it] = dirt }
        }
    }
}
