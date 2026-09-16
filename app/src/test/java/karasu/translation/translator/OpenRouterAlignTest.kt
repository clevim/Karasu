package karasu.translation.translator

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

class OpenRouterAlignTest {

    private fun align(reply: String, size: Int) =
        alignToBatch(Json.parseToJsonElement(reply), size)

    @Test
    fun `the keys that were asked for are used`() {
        assertEquals(
            listOf("um", "dois", "tres"),
            align("{\"t0\":\"um\",\"t1\":\"dois\",\"t2\":\"tres\"}", 3),
        )
    }

    @Test
    fun `a dropped key leaves its own bubble alone instead of shifting the rest`() {
        // The whole reason for keys: with a bare array this reply would put "tres" on bubble 1.
        assertEquals(
            listOf("um", null, "tres"),
            align("{\"t0\":\"um\",\"t2\":\"tres\"}", 3),
        )
    }

    @Test
    fun `a model that answered with a plain array still lines up`() {
        assertEquals(listOf("um", "dois"), align("[\"um\",\"dois\"]", 2))
    }

    @Test
    fun `a model that wrapped the answer under a name of its own still lines up`() {
        assertEquals(listOf("um", "dois"), align("{\"translations\":[\"um\",\"dois\"]}", 2))
        assertEquals(
            listOf("um", "dois"),
            align("{\"translations\":{\"t0\":\"um\",\"t1\":\"dois\"}}", 2),
        )
    }

    @Test
    fun `a model that renamed every key falls back to their order`() {
        assertEquals(listOf("um", "dois"), align("{\"1\":\"um\",\"2\":\"dois\"}", 2))
    }

    @Test
    fun `a reply that fits nothing fails instead of coming back blank`() {
        // Blank would be written back as the original text, which reads on screen as the engine
        // having translated English into English.
        assertThrows<IllegalStateException> { align("{\"a\":\"um\",\"b\":\"dois\",\"c\":\"tres\"}", 2) }
        assertThrows<IllegalStateException> { align("[\"um\",\"dois\",\"tres\"]", 2) }
    }

    @Test
    fun `an array reply survives being pulled out of a chatty answer`() {
        // This step used to demand a brace, so it threw on every array reply before the code that
        // knows how to line an array up ever ran — and a failed batch fails the whole chapter.
        assertEquals("[\"um\",\"dois\"]", extractJson("Sure! ```json\n[\"um\",\"dois\"]\n```"))
        assertEquals("{\"t0\":\"um\"}", extractJson("Here you go: {\"t0\":\"um\"} hope that helps"))
    }

    @Test
    fun `a reply cut short says so rather than parsing into nonsense`() {
        assertThrows<IllegalArgumentException> { extractJson("{\"t0\":\"um\",\"t1\":\"do") }
        assertThrows<IllegalArgumentException> { extractJson("I cannot help with that.") }
    }
}

class OpenRouterBatchTest {

    private val translator = OpenRouterTranslator(
        fromLang = karasu.translation.recognizer.OcrLanguage.ENGLISH,
        toLang = "pt-BR",
        apiKey = "k",
        modelName = "m",
    )

    @Test
    fun `a batch is capped by how many lines it holds, not only by their length`() {
        // Ninety short bubbles used to go out as one request, because they fit the character
        // budget. The model then had minutes of generating to do and the call timed out.
        val batches = translator.batches(List(90) { "line $it" })

        assertTrue(batches.all { it.size <= 20 }, "sizes were ${batches.map { it.size }}")
        assertEquals(90, batches.sumOf { it.size }, "no line may be lost or repeated")
        assertEquals(List(90) { "line $it" }, batches.flatten(), "reading order must survive")
    }

    @Test
    fun `one line longer than the whole budget still goes out on its own`() {
        val batches = translator.batches(listOf("a".repeat(5000), "short"))

        assertEquals(2, batches.size)
        assertEquals(5000, batches.first().single().length)
    }
}
