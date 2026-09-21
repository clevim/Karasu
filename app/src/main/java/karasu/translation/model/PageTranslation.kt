package karasu.translation.model

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Translated text blocks for a single page, in the page's own pixel coordinates.
 */
@Serializable
data class PageTranslation(
    var blocks: MutableList<TranslationBlock> = mutableListOf(),
    val imgWidth: Float = 0f,
    val imgHeight: Float = 0f,
) {
    companion object {
        val EMPTY = PageTranslation()
    }
}

@Serializable
data class TranslationBlock(
    val text: String,
    var translation: String = "",
    val width: Float,
    val height: Float,
    val x: Float,
    val y: Float,
    /** Bounds of the first recognized symbol, used to pad the box to the original text size. */
    val symWidth: Float,
    val symHeight: Float,
    val angle: Float,
    /**
     * The page's own colour just outside this block, packed ARGB.
     *
     * Sampled when the page is read, because that is the last moment its pixels exist: the reader
     * draws the overlay over a page it decodes separately. Defaulted so translations written
     * before this field existed still load — they just paint white, as they always did.
     */
    val background: Int = WHITE,
    /**
     * The speech balloon this text sits in, in the page's own pixels, when one was found.
     *
     * The overlay letters into this instead of guessing the balloon from the glyph box. Null for
     * text with no balloon around it — a caption, lettering straight onto the art — and for every
     * translation written before this field existed, both of which fall back to the guess.
     */
    val balloon: BalloonBox? = null,
)

/** A balloon's bounds in page pixels. */
@Serializable
data class BalloonBox(val x: Float, val y: Float, val width: Float, val height: Float)

/** Opaque white, the fill for everything that was never sampled. */
const val WHITE: Int = 0xFFFFFFFF.toInt()

/**
 * Perceived brightness of a packed ARGB colour, 0 to 1.
 *
 * Decides whether the translation is lettered in black or white. A caption on a black gutter used
 * to get black text on a white patch stamped over the art; matching the page means the text has
 * to follow it.
 */
fun luminance(argb: Int): Float {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    return 0.299f * r + 0.587f * g + 0.114f * b
}

/**
 * The block as a translator should read it: one flat sentence, not the lettering's line breaks.
 *
 * Comic lettering is set in all caps and broken mid-word to fit the bubble. Fed as-is, both
 * engines translate a shouted fragment at a time and the result reads like nonsense.
 */
val TranslationBlock.sourceText: String
    get() {
        val joined = fixOcrConfusions(text.replace(HYPHEN_BREAK, "").replace('\n', ' ').trim())
        return if (joined.none { it.isLowerCase() }) joined.lowercase() else joined
    }

/**
 * Undoes the OCR misreadings that can be undone without guessing.
 *
 * Comic lettering is stylised, all caps and often outlined, and the recognizer is trained on
 * ordinary text, so a handful of confusions come back over and over: "because" as "8ecause",
 * "good" as "go0d", "heard" as "hear0", "what do I do" as "what do | do".
 *
 * Only the safe direction is undone. A digit that sits *inside a word*, with a letter beside it
 * and no other digit, cannot be a number — "go0d" is not a quantity, while "Episode 13" and
 * "4 grades" have spaces around them and are left alone. A pipe is not a letter in any script
 * this reads, so it is always the capital I it was drawn as.
 *
 * ponytail: the most common confusion of all, "?" read as "p" ("teacherp", "because of mep"),
 * is deliberately *not* here. A "p" is a real letter and an "r" gets misread as "p" too, so any
 * rule would maul more words than it saved. That one is left to the translator, which has the
 * sentence and can tell — the system prompt names it.
 */
internal fun fixOcrConfusions(text: String): String {
    val chars = text.toCharArray()
    for (i in chars.indices) {
        val lower = when (chars[i]) {
            '|' -> 'i'
            '0' -> 'o'
            '8' -> 'b'
            else -> continue
        }
        val before = chars.getOrNull(i - 1)
        val after = chars.getOrNull(i + 1)
        if (chars[i] != '|') {
            // A digit next to another digit is part of a number, whatever else is around it.
            if (before?.isDigit() == true || after?.isDigit() == true) continue
            if (before?.isLetter() != true && after?.isLetter() != true) continue
        }
        // Follow the case of whichever letter it is attached to, so all-caps lettering stays
        // all caps and the sentence is still recognised as such further down.
        val neighbour = before?.takeIf { it.isLetter() } ?: after?.takeIf { it.isLetter() }
        chars[i] = if (neighbour?.isUpperCase() != false) lower.uppercaseChar() else lower
    }
    // Two dots is an ellipsis the recognizer dropped a dot from; comics have no other use for it.
    return String(chars).replace(REPEATED_DOTS, "...")
}

private val REPEATED_DOTS = Regex("\\.{2,}")

/**
 * Drops bubbles read twice because they sat inside the overlap of two slices. The same bubble
 * comes back character for character from both, at nearly the same spot.
 */
fun dropDuplicateBlocks(blocks: List<TranslationBlock>): List<TranslationBlock> {
    val kept = mutableListOf<TranslationBlock>()
    for (block in blocks) {
        if (kept.none { it.text == block.text && abs(it.y - block.y) < it.height }) kept += block
    }
    return kept
}

/**
 * ML Kit splits a speech bubble into one block per line group. Blocks that sit directly below
 * each other with a similar width belong to the same bubble, so join them before translating —
 * a whole sentence translates better than its fragments.
 *
 * @param rtl the page is read right to left, which decides the order of bubbles sharing a row.
 */
fun mergeStackedBlocks(blocks: List<TranslationBlock>, rtl: Boolean = false): MutableList<TranslationBlock> {
    val merged = mutableListOf<TranslationBlock>()
    // Sorted top down first: ML Kit hands blocks back in whatever order it found them, and the
    // merge below only ever appends a line under the block it joins.
    for (block in blocks.sortedWith(compareBy({ it.y }, { it.x }))) {
        // Searched against every bubble kept so far, not just the previous one. Two bubbles side
        // by side interleave in a top-down list — bubble A line 1, bubble B line 1, A line 2 —
        // so a chain that only looks back one step merges neither of them, and the translator
        // gets four fragments instead of two sentences.
        // ponytail: O(n^2) over the bubbles of one page, which is tens. Index by row if a page
        // ever holds hundreds.
        val target = merged.indexOfLast { shouldMerge(it, block) }
        if (target >= 0) {
            merged[target] = merge(merged[target], block)
        } else {
            merged.add(block)
        }
    }
    return readingOrder(merged, rtl)
}

/**
 * Blocks in the order a reader meets them, which is the order the translator needs to follow a
 * conversation across a page.
 *
 * A plain top-down sort puts two bubbles that share a row in left-to-right order, which is
 * backwards for manga and splits an exchange down the middle either way. Here a block that
 * overlaps one already placed is on the same row, so its horizontal position decides; anything
 * clear of it keeps the vertical order. Ported from comic-translate's `sort_blk_list`.
 */
internal fun readingOrder(blocks: List<TranslationBlock>, rtl: Boolean): MutableList<TranslationBlock> {
    val ordered = mutableListOf<TranslationBlock>()
    for (block in blocks.sortedBy { it.centerY }) {
        var inserted = false
        for (i in ordered.indices) {
            val placed = ordered[i]
            // Below the placed block entirely: it is read later than that one, keep looking.
            if (block.centerY > placed.y + placed.height) continue
            // Clear above it, which only happens when the placed block is a tall one that
            // reaches further down. Read after it rather than splitting it.
            if (block.centerY < placed.y) {
                ordered.add(i + 1, block)
                inserted = true
                break
            }
            // Overlapping vertically, so they share a row and the side decides.
            val readFirst = if (rtl) block.centerX > placed.centerX else block.centerX < placed.centerX
            if (readFirst) {
                ordered.add(i, block)
                inserted = true
                break
            }
        }
        if (!inserted) ordered.add(block)
    }
    return ordered
}

private val TranslationBlock.centerX get() = x + width / 2f
private val TranslationBlock.centerY get() = y + height / 2f

private fun shouldMerge(a: TranslationBlock, b: TranslationBlock): Boolean {
    // Measured in glyph heights, not pixels: the gap between two lines of one bubble is ~30 px
    // on a 900 px scan and ~90 px on a 2400 px one, so a fixed pixel budget only ever fits one
    // page size — it splits bubbles apart on big scans and welds separate ones together on small.
    val em = max(a.symHeight, b.symHeight).takeIf { it > 0f } ?: DEFAULT_EM
    val widthSimilar = b.width < a.width || abs(a.width - b.width) < em * WIDTH_FACTOR
    val xClose = abs(a.x - b.x) < em * X_FACTOR
    val yClose = (b.y - (a.y + a.height)) < em * Y_FACTOR
    return widthSimilar && xClose && yClose && sameLettering(a, b)
}

/**
 * Whether two blocks are set in the same size of type.
 *
 * Lettering inside one bubble is one size. A caption box, a sound effect or the next bubble's
 * shout sitting just under a line of dialogue passes every geometric test and then drags a
 * completely unrelated phrase into the middle of the sentence. Comparing glyph heights is what
 * manga-image-translator uses to reject exactly that (`font_size_ratio_tol`).
 */
private fun sameLettering(a: TranslationBlock, b: TranslationBlock): Boolean {
    val small = min(a.symHeight, b.symHeight)
    val large = max(a.symHeight, b.symHeight)
    // A block whose symbol bounds came back empty proves nothing either way.
    if (small <= 0f) return true
    return large / small < FONT_SIZE_RATIO
}

private fun merge(a: TranslationBlock, b: TranslationBlock): TranslationBlock {
    val x = min(a.x, b.x)
    val y = a.y
    return a.copy(
        text = "${a.text}\n${b.text}",
        x = x,
        y = y,
        width = max(a.x + a.width, b.x + b.width) - x,
        height = max(a.y + a.height, b.y + b.height) - y,
    )
}

private const val WIDTH_FACTOR = 2f

/** How far two blocks' glyph heights may differ and still count as the same lettering. */
private const val FONT_SIZE_RATIO = 1.5f
private const val X_FACTOR = 1f
private const val Y_FACTOR = 1f

/** Stand-in glyph height for the rare block whose symbol bounds came back empty. */
private const val DEFAULT_EM = 30f

private val HYPHEN_BREAK = Regex("-\\s*\\n")
