package karasu.translation.model

import kotlin.math.max
import kotlin.math.min

/**
 * The bands a page is cut into before it is sent to a model that reads images, top and bottom in
 * the page's own pixels.
 *
 * An ordinary page goes whole. A long strip does not: a model scales every image to a fixed
 * budget, and a webtoon chapter tens of thousands of pixels tall would arrive with its lettering a
 * few pixels high. Bands are [SLICE_ASPECT] times as tall as the page is wide and overlap by a
 * quarter, so a balloon on a seam is whole in at least one of them unless it is taller than the
 * overlap; the duplicate it leaves is removed by [dropOverlappingBlocks].
 */
fun pageSlices(width: Int, height: Int): List<IntRange> {
    if (width <= 0 || height <= 0) return emptyList()
    val sliceHeight = (width * SLICE_ASPECT).toInt()
    if (height <= width * WHOLE_PAGE_ASPECT) return listOf(0 until height)
    val step = sliceHeight - sliceHeight / OVERLAP_DIVISOR
    val slices = mutableListOf<IntRange>()
    var top = 0
    while (top + sliceHeight < height) {
        slices += top until top + sliceHeight
        top += step
    }
    // The last band is aligned to the bottom rather than left short: a sliver a few pixels tall
    // is an image the model has to be paid for and can read nothing in.
    slices += max(0, height - sliceHeight) until height
    return slices
}

/** How much a band is scaled down so its longer side fits what a model reads well. */
fun sliceScale(width: Int, sliceHeight: Int): Float =
    min(1f, MAX_SLICE_SIDE.toFloat() / max(width, sliceHeight))

/**
 * Keeps one of every pair of blocks that cover the same text, which is what the overlap between
 * two bands leaves behind. The longer reading wins: the band that cut the balloon off saw less of
 * it.
 */
fun dropOverlappingBlocks(blocks: List<TranslationBlock>): MutableList<TranslationBlock> {
    val kept = mutableListOf<TranslationBlock>()
    for (block in blocks) {
        val twin = kept.indexOfFirst { overlapRatio(it, block) > SAME_TEXT_OVERLAP }
        when {
            twin < 0 -> kept += block
            block.text.length > kept[twin].text.length -> kept[twin] = block
        }
    }
    return kept
}

/** Shared area over the smaller block's area. */
private fun overlapRatio(a: TranslationBlock, b: TranslationBlock): Float {
    val w = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
    val h = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
    if (w <= 0f || h <= 0f) return 0f
    val smaller = min(a.width * a.height, b.width * b.height)
    return if (smaller <= 0f) 0f else w * h / smaller
}

/** Anything up to this tall for its width is a page, not a strip, and goes whole. */
private const val WHOLE_PAGE_ASPECT = 2.2f
private const val SLICE_ASPECT = 2f
private const val OVERLAP_DIVISOR = 4

/**
 * Longest side of an image sent. Gemini 3 charges a fixed token count per image whatever its size
 * and downsamples past this anyway, so larger is only a bigger upload.
 */
private const val MAX_SLICE_SIDE = 1600
private const val SAME_TEXT_OVERLAP = 0.5f
