package karasu.translation.translator

import karasu.translation.model.TranslationBlock
import karasu.translation.model.dropOverlappingBlocks
import karasu.translation.model.pageSlices
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GeminiPageReaderTest {

    private fun parse(reply: String, count: Int) = parsePageReply(Json.parseToJsonElement(reply).jsonObject, count)

    @Test
    fun `entries land on the image they name`() {
        val found = parse(
            """{"pages":[{"i":1,"b":[{"box":[10,20,30,40],"src":"Hi","tr":"Oi"}]}]}""",
            count = 2,
        )
        assertEquals(emptyList<FoundText>(), found[0])
        assertEquals(listOf(FoundText(listOf(10, 20, 30, 40), "Hi", "Oi")), found[1])
    }

    @Test
    fun `a box that cannot be drawn is dropped, one outside the image is clamped`() {
        val found = parse(
            """{"pages":[{"i":0,"b":[
                {"box":[50,50,10,90],"src":"a","tr":"x"},
                {"box":[1,2,3],"src":"b","tr":"y"},
                {"box":[-5,0,1200,500],"src":"c","tr":"z"},
                {"box":[1,1,2,2],"src":"d","tr":"  "}
            ]},{"i":7,"b":[{"box":[1,1,2,2],"src":"e","tr":"w"}]}]}""",
            count = 1,
        )
        assertEquals(listOf(FoundText(listOf(0, 0, 1000, 500), "c", "z")), found[0])
    }

    @Test
    fun `an ordinary page goes whole, a strip in overlapping bands that cover it`() {
        assertEquals(listOf(0 until 1600), pageSlices(1100, 1600))

        val bands = pageSlices(800, 12000)
        assertEquals(0, bands.first().first)
        assertEquals(11999, bands.last().last)
        assertTrue(bands.all { it.last - it.first + 1 == 1600 }, "bands were $bands")
        bands.zipWithNext().forEach { (a, b) -> assertTrue(b.first <= a.last, "gap between $a and $b") }
    }

    @Test
    fun `the overlap's duplicate keeps the fuller reading`() {
        val cut = block("I don't", y = 100f)
        val whole = block("I don't know", y = 105f)
        val elsewhere = block("Who?", y = 900f)
        assertEquals(listOf(whole, elsewhere), dropOverlappingBlocks(listOf(cut, whole, elsewhere)))
    }

    @Test
    fun `a blocked batch is split until the bad item is alone`() = runBlocking {
        val sent = mutableListOf<List<Int>>()
        val failure = inHalves((1..4).toList()) { part ->
            sent += part
            if (3 in part) throw RetryInHalvesException("blocked")
        }
        assertEquals(listOf(listOf(1, 2, 3, 4), listOf(1, 2), listOf(3, 4), listOf(3), listOf(4)), sent)
        assertTrue(failure is RetryInHalvesException)
        assertNull(inHalves(listOf(1)) { })
    }

    private fun block(text: String, y: Float) = TranslationBlock(
        text = text, width = 200f, height = 60f, x = 50f, y = y, symWidth = 20f, symHeight = 20f, angle = 0f,
    )
}
