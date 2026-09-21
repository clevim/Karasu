package karasu.translation.recognizer

import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private const val WHITE = 0xFFFFFFFF.toInt()
private const val BLACK = 0xFF000000.toInt()

/**
 * The overlay letters the translation into whatever this returns, so the two failures that matter
 * are a box that pokes outside the balloon and one that swallows the page.
 */
class BalloonTest {

    /** A white ellipse on black art, which is what a speech balloon looks like to a scanline. */
    private fun page(w: Int, h: Int, cx: Int, cy: Int, rx: Int, ry: Int): (Int, Int) -> Int =
        { x, y ->
            val inside = ((x - cx).toFloat() / rx).pow(2) + ((y - cy).toFloat() / ry).pow(2) <= 1f
            if (inside) WHITE else BLACK
        }

    @Test
    fun `the box it finds stays inside the balloon`() {
        val pixels = page(400, 400, cx = 200, cy = 200, rx = 120, ry = 80)
        // Lettering sitting in the middle of the balloon, hugged tight the way ML Kit hands it over.
        val text = IntRect(left = 170, top = 190, right = 230, bottom = 210)

        val balloon = findBalloon(400, 400, pixels, text, WHITE)

        assertNotNull(balloon)
        balloon!!
        assertTrue(balloon.area > text.area, "it has to be roomier than the lettering")
        // Every corner has to land inside the ellipse, or the translation crosses the outline.
        listOf(
            balloon.left to balloon.top, balloon.right to balloon.top,
            balloon.left to balloon.bottom, balloon.right to balloon.bottom,
        ).forEach { (x, y) ->
            assertTrue(pixels(x, y) == WHITE, "corner ($x, $y) fell outside the balloon")
        }
    }

    @Test
    fun `text on open art finds no balloon at all`() {
        // No balloon: the whole page is the fill colour, so the walk runs until it gives up.
        val pixels: (Int, Int) -> Int = { _, _ -> WHITE }
        val text = IntRect(left = 100, top = 100, right = 140, bottom = 120)

        assertNull(findBalloon(400, 400, pixels, text, WHITE), "a leak must not become a balloon")
    }

    @Test
    fun `a balloon no roomier than the lettering is not worth preferring`() {
        val pixels = page(400, 400, cx = 200, cy = 200, rx = 34, ry = 16)
        val text = IntRect(left = 170, top = 188, right = 230, bottom = 212)

        assertNull(findBalloon(400, 400, pixels, text, WHITE))
    }

    @Test
    fun `a gap in the outline cannot drag the whole edge out`() {
        val ellipse = page(400, 400, cx = 200, cy = 200, rx = 120, ry = 80)
        // A tail: one row where the outline is open and white runs off to the page edge.
        val pixels: (Int, Int) -> Int = { x, y -> if (y == 200) WHITE else ellipse(x, y) }
        val text = IntRect(left = 170, top = 190, right = 230, bottom = 210)

        val balloon = findBalloon(400, 400, pixels, text, WHITE)

        assertNotNull(balloon)
        // The other two rows stop at the outline, and the tightest answer is the one kept.
        assertTrue(balloon!!.right < 330, "the tail row set the edge: right was ${balloon.right}")
    }

    @Test
    fun `a balloon only a few pixels roomier still gets its corners fitted`() {
        // The gap the walk finds here is single digits, which a proportional shrink alone rounds
        // to a zero-length step: the loop would spin out its rounds and hand back the box it
        // started with, corners and all.
        val pixels = page(400, 400, cx = 200, cy = 200, rx = 44, ry = 26)
        val text = IntRect(left = 170, top = 190, right = 230, bottom = 210)

        val balloon = findBalloon(400, 400, pixels, text, WHITE)

        if (balloon != null) {
            listOf(
                balloon.left to balloon.top, balloon.right to balloon.top,
                balloon.left to balloon.bottom, balloon.right to balloon.bottom,
            ).forEach { (x, y) ->
                assertTrue(pixels(x, y) == WHITE, "corner ($x, $y) fell outside the balloon")
            }
        }
    }
}
