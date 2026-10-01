package karasu.translation.translator

import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import karasu.translation.model.SeriesNotes
import karasu.translation.recognizer.OcrLanguage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import uy.kohesive.injekt.injectLazy
import java.util.Base64

/**
 * One piece of text Gemini found on an image.
 *
 * @param box `[ymin, xmin, ymax, xmax]` of the lettering, scaled 0–1000 to the image it is on —
 *   the convention Gemini is trained to answer boxes in, so it is asked for that and nothing else.
 */
data class FoundText(val box: List<Int>, val source: String, val translation: String)

/**
 * Reads and translates comic pages in one step: images in, boxes and translations out.
 *
 * On-device OCR is trained on ordinary print and reads lettering badly — stylised caps, outlined
 * text, vertical Japanese, a balloon split into one block per line. Gemini sees the page the way a
 * reader does, so it gets the balloon whole, reads what ML Kit misreads, and translates with the
 * art in view. It is what manga-image-translator's detector, OCR and LLM steps do, as one request
 * and with no server to run.
 *
 * Several images go in each request: the free tier is counted in requests a day, not tokens, and
 * the pages around a bubble are the context its translation needs anyway.
 */
class GeminiPageReader(
    private val fromLang: OcrLanguage,
    private val toLang: String,
    private val apiKey: String,
    private val modelName: String,
    private val context: SeriesNotes,
    /** The pages are read right to left, which the model needs to order the bubbles. */
    private val rtl: Boolean,
) {

    private val network: NetworkHelper by injectLazy()
    private val client by lazy { slowClient(network) }
    private val json = Json { ignoreUnknownKeys = true }

    private val _learned = linkedMapOf<String, String>()

    /** Terms settled on while reading, for the manga's notes. See [TextTranslator.learned]. */
    val learned: Map<String, String> get() = _learned

    /**
     * @param images JPEG bytes, in reading order.
     * @return what was found on each image, in the same order. Throws [RetryInHalvesException]
     *   when a smaller batch may go through.
     */
    suspend fun read(images: List<ByteArray>): List<List<FoundText>> {
        require(apiKey.isNotBlank()) { "$PROVIDER API key is not set" }
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") { addJsonObject { put("text", systemPrompt()) } }
            }
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        images.forEachIndexed { i, jpeg ->
                            // Labelled, because the reply refers to images by number and the
                            // model has nothing else to count them by.
                            addJsonObject { put("text", "Image $i") }
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/jpeg")
                                    put("data", Base64.getEncoder().encodeToString(jpeg))
                                }
                            }
                        }
                    }
                }
            }
            putGeminiConfig(modelName, MAX_TOKENS, REPLY_SCHEMA)
        }
        val content = withRateLimitRetry(PROVIDER, modelName) {
            client.newCall(geminiRequest(apiKey, modelName, body)).awaitSuccess().use { response ->
                geminiText(json.parseToJsonElement(response.body.string()).jsonObject, PROVIDER)
            }
        }
        val reply = runCatching { json.parseToJsonElement(extractJson(content)).jsonObject }
            .getOrElse { throw RetryInHalvesException("$PROVIDER replied with broken JSON: ${content.take(200)}") }
        remember(reply["g"])
        return parsePageReply(reply, images.size)
    }

    private fun systemPrompt(): String = """
        You read comic pages and translate them from ${TranslationLanguages.promptName(fromLang.code)}
        into ${TranslationLanguages.promptName(toLang)}.

        The images are consecutive pages (or slices of a long strip) of one chapter, in reading
        order, each after its label "Image N". Pages read ${if (rtl) "right to left" else "left to right"}.

        For every image, list each piece of text a reader needs: speech balloons, thought bubbles,
        narration boxes, signs and handwriting that matter to the story.
        - Skip sound effects drawn into the art, page numbers, and scanlator or website credits and
          watermarks.
        - One entry per balloon: all its lines are one entry, never one per line.
        - Entries in the order a reader meets them.
        - box: [ymin, xmin, ymax, xmax] tightly around the lettering, scaled 0-1000 to that image.
        - src: the text as written.
        - tr: the translation. Every tr must be in ${TranslationLanguages.promptName(toLang)}.
          Read the whole chapter first: a sentence split across balloons stays split, and names,
          pronouns and formality stay consistent. Keep each tr about as long as its src — it has
          to fit the same balloon.
        - Text cut off at the top or bottom edge of a slice: leave it out, the next slice has it.
        - g: proper nouns and recurring terms you translated, at most $GLOSSARY_PER_REQUEST, only
          ones you are sure of.
    """.trimIndent() + seriesPrompt()

    /**
     * The whole glossary, not filtered as the text engine does: what the pages say is not known
     * until they are read. It is capped at 40 terms, which is nothing next to one image.
     */
    private fun seriesPrompt(): String = buildString {
        if (context.notes.isNotBlank()) {
            append("\n\nNotes from the reader about this series, follow them:\n")
            append(context.notes)
        }
        if (context.glossary.isNotEmpty()) {
            append("\n\nTerms this series has already used, reuse them exactly:\n")
            context.glossary.forEach { (term, translated) -> append("$term = $translated\n") }
        }
    }

    /** First answer wins, capped, plain strings only. See [OpenAiChatTranslator] for why. */
    private fun remember(glossary: JsonElement?) {
        (glossary as? JsonArray)?.forEach { entry ->
            if (_learned.size >= MAX_LEARNED_TERMS) return
            val obj = entry as? JsonObject ?: return@forEach
            val term = obj["term"].text().trim()
            val translated = obj["tr"].text().replace('\n', ' ').replace('\r', ' ').trim()
            if (term.isBlank() || translated.isBlank()) return@forEach
            if (term.length > MAX_TERM_CHARS || translated.length > MAX_TERM_CHARS) return@forEach
            _learned.putIfAbsent(term, translated)
        }
    }

    companion object {
        private const val PROVIDER = "Gemini"

        /**
         * Images per request. A request is what the free tier counts; what limits it from above
         * is the reply, at roughly seventy tokens per balloon, and how much of a chapter one bad
         * page takes down with it before the batch is split.
         */
        const val IMAGES_PER_REQUEST = 16

        private const val MAX_TOKENS = 32768
        private const val GLOSSARY_PER_REQUEST = 10
        private const val MAX_LEARNED_TERMS = 40
        private const val MAX_TERM_CHARS = 60

        /**
         * The shape of the reply, enforced by Gemini rather than hoped for: without it the keys
         * are a suggestion, and a renamed one lines nothing up. Box before text before
         * translation, so the model has looked and read before it writes the translation.
         */
        private val REPLY_SCHEMA: JsonObject = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                putJsonObject("pages") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("i") { put("type", "INTEGER") }
                            putJsonObject("b") {
                                put("type", "ARRAY")
                                putJsonObject("items") {
                                    put("type", "OBJECT")
                                    putJsonObject("properties") {
                                        putJsonObject("box") {
                                            put("type", "ARRAY")
                                            putJsonObject("items") { put("type", "INTEGER") }
                                        }
                                        putJsonObject("src") { put("type", "STRING") }
                                        putJsonObject("tr") { put("type", "STRING") }
                                    }
                                    putJsonArray("required") { add("box"); add("src"); add("tr") }
                                    putJsonArray("propertyOrdering") { add("box"); add("src"); add("tr") }
                                }
                            }
                        }
                        putJsonArray("required") { add("i"); add("b") }
                        putJsonArray("propertyOrdering") { add("i"); add("b") }
                    }
                }
                putJsonObject("g") {
                    put("type", "ARRAY")
                    putJsonObject("items") {
                        put("type", "OBJECT")
                        putJsonObject("properties") {
                            putJsonObject("term") { put("type", "STRING") }
                            putJsonObject("tr") { put("type", "STRING") }
                        }
                        putJsonArray("required") { add("term"); add("tr") }
                    }
                }
            }
            putJsonArray("required") { add("pages") }
            putJsonArray("propertyOrdering") { add("pages"); add("g") }
        }
    }
}

/**
 * What the reply found on each of [count] images.
 *
 * Lenient where the model is sloppy and strict where a mistake would paint text in the wrong place:
 * an image it never mentions has no text, an entry with a malformed box is dropped, and a box is
 * clamped into the image rather than trusted to be inside it.
 */
internal fun parsePageReply(reply: JsonObject, count: Int): List<List<FoundText>> {
    val found = List(count) { mutableListOf<FoundText>() }
    (reply["pages"] as? JsonArray)?.forEach { page ->
        val obj = page as? JsonObject ?: return@forEach
        val index = (obj["i"] as? JsonPrimitive)?.intOrNull ?: return@forEach
        val target = found.getOrNull(index) ?: return@forEach
        (obj["b"] as? JsonArray)?.forEach blocks@{ block ->
            val b = block as? JsonObject ?: return@blocks
            val box = (b["box"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull?.coerceIn(0, BOX_SCALE) }
                ?.takeIf { it.size == 4 && it[0] < it[2] && it[1] < it[3] }
                ?: return@blocks
            val translation = b["tr"].text().trim()
            if (translation.isBlank()) return@blocks
            target += FoundText(box, b["src"].text().trim(), translation)
        }
    }
    return found
}

/** The scale of [FoundText.box]. */
const val BOX_SCALE = 1000

private fun JsonElement?.text(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()
