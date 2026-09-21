package karasu.translation.recognizer

import karasu.translation.model.luminance
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The speech balloon a block of text sits in, found by walking outward until the page stops
 * looking like the balloon.
 *
 * ML Kit hands back a box that hugs the glyphs, and the overlay has to letter a translation into
 * the *balloon*, which is always roomier. Padding the glyph box by a fixed fraction was the stand
 * in for that: too little on a round balloon with air around the text, too much on a caption box,
 * and it is why a long translation could end up at 6pt inside a balloon with room to spare.
 *
 * A balloon is a near-uniform light region with a dark outline, so it is found the way the eye
 * finds it: from the text, walk out in each direction until the colour stops matching, and take
 * the box those walks agree on. Rows and columns are sampled at three places each and the
 * *tightest* answer wins, so a balloon that narrows towards its top and bottom gives a rectangle
 * that fits inside it rather than one that pokes out at the corners.
 *
 * Returns null when the result cannot be trusted — the walk escaped into the page, or it found
 * nothing worth more than the padding already applied — and the caller keeps what it had.
 *
 * ponytail: nine scanlines, not a segmentation model. A neural mask ([bubble-pop] is 2 MB of
 * weights on top of a LiteRT runtime) would also handle balloons over busy art and coloured
 * webtoon bubbles, which this deliberately gives up on. Worth it the day flat balloons stop being
 * the common case.
 */
internal fun findBalloon(
    width: Int,
    height: Int,
    pixelAt: (Int, Int) -> Int,
    text: IntRect,
    fill: Int,
): IntRect? {
    if (text.width <= 0 || text.height <= 0) return null

    val target = luminance(fill)
    fun matches(x: Int, y: Int): Boolean =
        x in 0 until width && y in 0 until height && abs(luminance(pixelAt(x, y)) - target) <= TOLERANCE

    // A walk that runs this far without meeting an outline is not in a balloon. Sized against the
    // page rather than against the text box: how far the outline is depends on the balloon, not on
    // how much was written in it, and a two-word box in a big balloon is perfectly ordinary.
    val reachX = width / REACH_DIVISOR
    val reachY = height / REACH_DIVISOR

    /** Steps to the balloon's edge, or [LEAKED] if this line never met one. */
    fun walk(fromX: Int, fromY: Int, stepX: Int, stepY: Int, limit: Int): Int {
        var steps = 0
        while (steps < limit) {
            val x = fromX + stepX * (steps + 1)
            val y = fromY + stepY * (steps + 1)
            // Off the page is not an edge: a balloon that reaches the paper's border is a page
            // with no balloon on it.
            if (x !in 0 until width || y !in 0 until height) return LEAKED
            if (!matches(x, y)) return steps
            steps++
        }
        return LEAKED
    }

    /**
     * The tightest of three scans, ignoring the ones that leaked.
     *
     * One line can slip through a gap in the outline — a balloon tail, two balloons that touch —
     * and it must not be allowed to set the whole edge. If every line leaked there is no balloon
     * on this side, and the caller is better off with its padding.
     */
    fun tightest(scans: List<Int>): Int? = scans.filter { it != LEAKED }.minOrNull()

    val rows = listOf(text.top, text.centerY, text.bottom)
    val columns = listOf(text.left, text.centerX, text.right)

    val leftGap = tightest(rows.map { walk(text.left, it, -1, 0, reachX) }) ?: return null
    val rightGap = tightest(rows.map { walk(text.right, it, 1, 0, reachX) }) ?: return null
    val topGap = tightest(columns.map { walk(it, text.top, 0, -1, reachY) }) ?: return null
    val bottomGap = tightest(columns.map { walk(it, text.bottom, 0, 1, reachY) }) ?: return null

    // Each edge is as far out as its own axis allows, which on a round balloon is a box drawn
    // *around* it: the left edge comes from a row near the middle, where the balloon is widest,
    // and the top edge from a column near the middle, where it is tallest — so the corner between
    // them sits outside the outline. Pull the edges back in until all four corners land on the
    // balloon, which is the box that can actually be lettered into.
    var balloon = IntRect(
        left = text.left - leftGap,
        top = text.top - topGap,
        right = text.right + rightGap,
        bottom = text.bottom + bottomGap,
    )
    repeat(SHRINK_STEPS) {
        val corners = listOf(
            balloon.left to balloon.top, balloon.right to balloon.top,
            balloon.left to balloon.bottom, balloon.right to balloon.bottom,
        )
        if (corners.all { (x, y) -> matches(x, y) }) return@repeat
        // Towards the text rather than towards the centre: the lettering is the one part that has
        // to stay inside whatever is left.
        balloon = IntRect(
            left = balloon.left + step(text.left - balloon.left),
            top = balloon.top + step(text.top - balloon.top),
            right = balloon.right - step(balloon.right - text.right),
            bottom = balloon.bottom - step(balloon.bottom - text.bottom),
        )
    }

    // Nothing found worth having: the fixed padding the caller already applies does as well.
    if (balloon.area < text.area * MIN_GAIN) return null
    // Backstop for a page whose art happens to close a walk on every side: nothing this large is
    // a speech balloon.
    if (balloon.area > width.toLong() * height * MAX_PAGE_SHARE) return null
    return balloon
}

/** A rectangle in page pixels. [right] and [bottom] are inclusive, as a scanline sees them. */
internal data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2
    val centerY get() = (top + bottom) / 2
    val area get() = width.toLong() * height
}

/**
 * How far a pixel may drift from the balloon's own colour and still count as balloon.
 *
 * Generous on purpose: scans are noisy, balloons are rarely pure white, and the thing that stops
 * the walk is the outline, which is nothing like the fill. Screentone inside a balloon will still
 * halt it early — that is what the [MIN_GAIN] check is for.
 */
private const val TOLERANCE = 0.22f

/** A scan that met no outline. Not a distance, so it never takes part in the tightest answer. */
private const val LEAKED = Int.MAX_VALUE

/** Walk limit as a fraction of the page, so a leak costs a bounded scan instead of a full row. */
private const val REACH_DIVISOR = 3

/** Below this the balloon is barely bigger than the lettering, and not worth preferring. */
private const val MIN_GAIN = 1.2f

/** No speech balloon takes this much of a page. */
private const val MAX_PAGE_SHARE = 0.4f

/**
  * How far one edge moves in a round of corner fitting, given its [gap] to the lettering.
  *
  * At least a pixel while there is any gap left: a proportional step alone rounds to zero once the
  * gap is under nine pixels, and the loop then spins out its rounds without the box ever moving —
  * returning corners that still sit outside the balloon, which is the one thing it exists to stop.
  */
private fun step(gap: Int): Int = if (gap <= 0) 0 else max(1, (gap * SHRINK).toInt())

/** How much of the gap to the lettering each edge gives up per round of corner fitting. */
private const val SHRINK = 0.12f

/** Enough rounds for the gap to close to under a tenth, which is tighter than a balloon outline. */
private const val SHRINK_STEPS = 20
