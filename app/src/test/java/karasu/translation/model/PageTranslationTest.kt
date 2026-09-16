package karasu.translation.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageTranslationTest {

    private fun block(
        text: String,
        x: Float,
        y: Float,
        width: Float = 100f,
        height: Float = 20f,
    ) = TranslationBlock(
        text = text,
        width = width,
        height = height,
        x = x,
        y = y,
        symWidth = 10f,
        symHeight = 20f,
        angle = 0f,
    )

    @Test
    fun `stacked lines of one bubble merge into a single block`() {
        val merged = mergeStackedBlocks(
            listOf(
                block("Hello there,", x = 100f, y = 100f),
                block("how are you?", x = 105f, y = 125f),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals("Hello there,\nhow are you?", merged.first().text)
        // The merged box covers both lines.
        assertEquals(100f, merged.first().x)
        assertEquals(100f, merged.first().y)
        assertEquals(45f, merged.first().height)
    }

    @Test
    fun `bubbles far apart stay separate`() {
        val farBelow = mergeStackedBlocks(
            listOf(
                block("Top bubble", x = 100f, y = 100f),
                block("Bottom bubble", x = 100f, y = 400f),
            ),
        )
        assertEquals(2, farBelow.size)

        val farRight = mergeStackedBlocks(
            listOf(
                block("Left bubble", x = 100f, y = 100f),
                block("Right bubble", x = 500f, y = 125f),
            ),
        )
        assertEquals(2, farRight.size)
    }

    @Test
    fun `a page with no text yields no blocks`() {
        assertEquals(0, mergeStackedBlocks(emptyList()).size)
    }

    @Test
    fun `the same bubble merges whatever the scan resolution is`() {
        // Same layout, three times the pixels. Fixed pixel thresholds passed the small one and
        // split the large one into two bubbles.
        fun bubble(scale: Float) = mergeStackedBlocks(
            listOf(
                block("Hello there,", x = 100f * scale, y = 100f * scale, width = 100f * scale, height = 20f * scale)
                    .copy(symHeight = 20f * scale),
                block("how are you?", x = 105f * scale, y = 125f * scale, width = 100f * scale, height = 20f * scale)
                    .copy(symHeight = 20f * scale),
            ),
        )

        assertEquals(1, bubble(1f).size)
        assertEquals(1, bubble(3f).size)
    }

    @Test
    fun `blocks found out of order still merge`() {
        val merged = mergeStackedBlocks(
            listOf(
                block("how are you?", x = 105f, y = 125f),
                block("Hello there,", x = 100f, y = 100f),
            ),
        )

        assertEquals(1, merged.size)
        assertEquals("Hello there,\nhow are you?", merged.first().text)
    }

    @Test
    fun `a bubble read by two overlapping slices is kept once`() {
        val blocks = dropDuplicateBlocks(
            listOf(
                block("Same bubble", x = 100f, y = 4000f),
                // The second slice starts higher up, so it reads the bubble again a few pixels off.
                block("Same bubble", x = 100f, y = 4003f),
                block("A different one", x = 100f, y = 4002f),
            ),
        )

        assertEquals(2, blocks.size)
        assertEquals(listOf("Same bubble", "A different one"), blocks.map { it.text })
    }

    @Test
    fun `the same words in two bubbles both survive`() {
        val blocks = dropDuplicateBlocks(
            listOf(
                block("What?!", x = 100f, y = 100f),
                block("What?!", x = 100f, y = 900f),
            ),
        )
        assertEquals(2, blocks.size)
    }

    @Test
    fun `two bubbles side by side each merge their own lines`() {
        // The lines interleave in a top-down list: left line 1, right line 1, left line 2...
        // A merge that only looks back one block merged none of them.
        val merged = mergeStackedBlocks(
            listOf(
                block("Left one,", x = 100f, y = 100f),
                block("Right one,", x = 500f, y = 105f),
                block("second line.", x = 100f, y = 125f),
                block("second line too.", x = 500f, y = 130f),
            ),
        )

        assertEquals(2, merged.size)
        assertEquals(
            setOf("Left one,\nsecond line.", "Right one,\nsecond line too."),
            merged.map { it.text }.toSet(),
        )
    }

    @Test
    fun `a caption in another size is not welded onto the dialogue`() {
        val merged = mergeStackedBlocks(
            listOf(
                block("What did you say?", x = 100f, y = 100f),
                // Directly below and the same width, but set in half the type: a caption box,
                // not the second line of the bubble.
                block("Three days earlier", x = 100f, y = 125f).copy(symHeight = 8f),
            ),
        )

        assertEquals(2, merged.size)
    }

    @Test
    fun `bubbles sharing a row are ordered by the page's reading direction`() {
        val row = listOf(
            block("first on the left", x = 100f, y = 100f),
            block("first on the right", x = 600f, y = 105f),
        )

        assertEquals(
            listOf("first on the left", "first on the right"),
            mergeStackedBlocks(row, rtl = false).map { it.text },
        )
        assertEquals(
            listOf("first on the right", "first on the left"),
            mergeStackedBlocks(row, rtl = true).map { it.text },
        )
    }

    @Test
    fun `a bubble further down is read after the row above it whatever the direction`() {
        val page = listOf(
            block("top left", x = 100f, y = 100f),
            block("top right", x = 600f, y = 100f),
            block("below", x = 300f, y = 400f),
        )

        assertEquals(
            listOf("top right", "top left", "below"),
            mergeStackedBlocks(page, rtl = true).map { it.text },
        )
        assertEquals(
            listOf("top left", "top right", "below"),
            mergeStackedBlocks(page, rtl = false).map { it.text },
        )
    }

    @Test
    fun `luminance decides black or white lettering`() {
        // The two that matter: a white bubble and a black gutter caption.
        assertTrue(luminance(0xFFFFFFFF.toInt()) > 0.5f, "white page takes black text")
        assertTrue(luminance(0xFF000000.toInt()) < 0.5f, "black page takes white text")
        // Green reads far brighter than blue at the same value, which is the whole point of
        // weighting the channels instead of averaging them.
        assertTrue(luminance(0xFF00FF00.toInt()) > luminance(0xFF0000FF.toInt()))
    }

    @Test
    fun `a block from a translation written before backgrounds existed still paints white`() {
        assertEquals(WHITE, block("old", x = 0f, y = 0f).background)
    }

    @Test
    fun `lettering is flattened into one sentence for the translator`() {
        // All caps, broken mid-word to fit the bubble: what comic lettering actually looks like.
        assertEquals(
            "i can't believe you did something like that",
            block("I CAN'T BELIEVE\nYOU DID SOME-\nTHING LIKE THAT", x = 0f, y = 0f).sourceText,
        )
    }

    @Test
    fun `the OCR confusions that can be undone are undone`() {
        // Every one of these came off a real page, out of a request the app actually sent.
        assertEquals("because of mep", block(".8ecause of mep", 0f, 0f).sourceText.removePrefix("."))
        assertEquals("was a good", block("was a go0d", 0f, 0f).sourceText)
        assertEquals("your homeroom teacher.", block("your homer0om teacher.", 0f, 0f).sourceText)
        // "heard": here the 0 stood for a d, not an o. The rule has to pick one letter and o is
        // far and away the common case, so this one only gets halfway — but "hearo" still reads
        // as a word to the translator, which a digit never does.
        assertEquals("i hearo there was two", block("i hear0 there was two", 0f, 0f).sourceText)
        // The pipe becomes the capital I it was drawn as, and the two dots become an ellipsis.
        assertEquals("what do I do...!", block("what do | do..!", 0f, 0f).sourceText)
    }

    @Test
    fun `real numbers are left alone`() {
        // The rule only fires on a digit wedged into a word. These are quantities.
        // Mixed case, so the all-caps flattening does not apply and the title is untouched.
        assertEquals("[Season 3] Episode 13: Partner", block("[Season 3] Episode 13: Partner", 0f, 0f).sourceText)
        assertEquals("there are a total of 4 grades", block("there are a total of 4 grades", 0f, 0f).sourceText)
        assertEquals("in 2026 and 80 more", block("in 2026 and 80 more", 0f, 0f).sourceText)
    }

    @Test
    fun `all caps lettering stays all caps so it is still flattened`() {
        // A lower-case repair here would hide the caps and skip the lowercasing below it.
        assertEquals("good news", block("G0OD NEWS", 0f, 0f).sourceText)
    }

    @Test
    fun `mixed case text keeps its capitals`() {
        assertEquals("Hello there, how are you?", block("Hello there,\nhow are you?", x = 0f, y = 0f).sourceText)
    }
}
