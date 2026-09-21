package karasu.translation.translator

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import karasu.translation.model.PageTranslation
import karasu.translation.model.SeriesNotes
import karasu.translation.model.TranslationBlock
import karasu.translation.model.sourceText
import karasu.translation.recognizer.OcrLanguage
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.TimeUnit

/**
 * Translates through an LLM, so it can use the surrounding bubbles as context.
 * Needs an API key from openrouter.ai.
 *
 * Bubbles are sent in batches of consecutive pages rather than one request per page. OpenRouter's
 * free tier allows 20 requests a minute and 50 a day on an account that has never bought credits,
 * so a request per page burned a whole day's quota on one chapter and every page after the first
 * few came back rate limited — which looked exactly like the engine doing nothing, because a
 * failed page silently kept its original text.
 *
 * The batch is capped by source characters, not by page count: what overruns a model's reply
 * limit is how much text there is, and a truncated reply is unparseable JSON that loses the whole
 * batch.
 */
class OpenAiChatTranslator(
    override val fromLang: OcrLanguage,
    override val toLang: String,
    private val apiKey: String,
    private val modelName: String,
    /** What is known about this series; the parts of it each batch needs go in its prompt. */
    private val context: SeriesNotes = SeriesNotes(),
    /** Chat-completions endpoint. Every provider here speaks the same OpenAI-shaped protocol. */
    private val endpoint: String = OPENROUTER_ENDPOINT,
    /** The provider's name, for the messages the user reads when it fails. */
    private val providerName: String = "OpenRouter",
    /**
     * OpenRouter attributes free-tier traffic to whoever sends these, and caps it per day — so it
     * is also the only provider with an allowance to check before starting. A paid provider has
     * neither, and passing null is what says so.
     */
    private val quota: OpenRouterQuota? = null,
) : TextTranslator {

    private val network: NetworkHelper by injectLazy()

    private val json = Json { ignoreUnknownKeys = true }

    private val _learned = linkedMapOf<String, String>()

    override val learned: Map<String, String> get() = _learned

    /**
     * The shared client gives up after two minutes, which is right for fetching a page and wrong
     * for this: a free model generating a batch of translations at six tokens a second was being
     * cut off mid-answer, and OpenRouter logged the generation as cancelled.
     */
    private val slowClient by lazy {
        network.client.newBuilder()
            .readTimeout(TRANSLATE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(TRANSLATE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (apiKey.isBlank()) error("$providerName API key is not set")

        // Page order is the reading order, and the map keeps insertion order, so the model sees
        // the bubbles in the order a reader would — which is the whole point of an LLM here.
        val blocks = pages.values.flatMap { it.blocks }
        if (blocks.isEmpty()) return

        // One line per distinct thing said, not per bubble. A chapter repeats itself constantly
        // — the same shout, the same sound effect, a site watermark stamped on every page — and
        // every repeat used to cost tokens twice, once in the request and once in the reply.
        // Grouping also makes the result consistent: the same line cannot come back worded two
        // ways in the same chapter.
        val byText = blocks.groupBy { it.sourceText }
        val texts = byText.keys.toList()
        val work = batches(texts)
        Logger.d {
            "$providerName: ${blocks.size} bubbles, ${texts.size} distinct lines, ${work.size} requests"
        }

        // Checked before the first request, not discovered on the twentieth. Free models are
        // capped by request count per day, and starting a chapter there is no allowance to finish
        // spends what is left on a chapter that comes out half translated anyway.
        quota?.let {
            it.refreshCap(apiKey)
            val remaining = it.remaining()
            require(remaining >= work.size) {
                "Not enough free OpenRouter requests left today: this chapter needs ${work.size}, " +
                    "$remaining of ${it.cap()} remain. They reset at midnight UTC."
            }
        }

        var failure: Throwable? = null
        var translatedAny = false
        work.forEach { batch ->
            try {
                val translations = requestBatch(batch)
                // A reply nothing could be lined up with leaves every bubble blank, and a blank
                // bubble is dropped below. Say so instead: a whole batch that lined up with
                // nothing is a failure, not a chapter that happened to have no dialogue there.
                require(translations.any { !it.isNullOrBlank() }) {
                    "$providerName replied with nothing that matched the ${batch.size} lines sent"
                }
                translatedAny = true
                batch.forEachIndexed { i, text ->
                    val translated = translations.getOrNull(i).orEmpty()
                    byText[text]?.forEach { it.translation = translated }
                }
            } catch (e: CancellationException) {
                // runCatching used to be here, which swallows this one too — a translation the
                // user cancelled would have carried on to the next batch.
                throw e
            } catch (e: Throwable) {
                failure = e
                Logger.e(e) { "$providerName could not translate a batch of ${batch.size} lines" }
            }
        }

        // Nothing came back at all: say so instead of writing the source text into every bubble,
        // where a rate limit or a bad API key is indistinguishable from a page with no dialogue.
        failure?.let { if (!translatedAny) throw it }

        // A bubble the model skipped is dropped, not filled with its own source text. Painting
        // the English back over the English says "translated" while showing the reader nothing
        // they did not already have; leaving the bubble alone shows the art and is honest about
        // which bubbles got through.
        pages.values.forEach { page ->
            page.blocks = page.blocks
                .filterNot { it.translation.isBlank() || it.translation.contains(WATERMARK) }
                .toMutableList()
        }
    }

    /**
     * Consecutive blocks grouped so one request stays inside a small model's reply limit.
     *
     * Capped by bubble count as well as characters. A chapter used to go out as one request of
     * ninety-odd bubbles, and the free models this routes to answer at a handful of tokens a
     * second: the reply needed minutes, the call timed out at two, and the chapter came back
     * with the original text in every bubble. Small batches also mean a model that gives up
     * half way costs one batch, not the chapter.
     */
    internal fun batches(texts: List<String>): List<List<String>> {
        val batches = mutableListOf<MutableList<String>>()
        var budget = 0
        for (text in texts) {
            val last = batches.lastOrNull()
            if (last == null || last.size >= BATCH_BUBBLES || budget + text.length > BATCH_CHARS) {
                batches += mutableListOf(text)
                budget = text.length
            } else {
                last += text
                budget += text.length
            }
        }
        return batches
    }

    /** @return one entry per line in [batch], null where the reply had nothing for it. */
    private suspend fun requestBatch(batch: List<String>): List<String?> {
        val body = buildJsonObject {
            put("model", modelName)
            put("temperature", 0.3)
            put("max_tokens", MAX_TOKENS)
            // A reasoning model spends `max_tokens` thinking before it writes anything, so it
            // reaches the cap having produced no JSON at all — which is exactly what the free
            // router handed back when it picked one. There is nothing here worth reasoning about.
            //
            // OpenRouter's own extension to the protocol, so it is only sent there: a provider
            // that validates its request body rejects the whole call over a field it never
            // defined, and the ones that do not would ignore it anyway.
            if (quota != null) putJsonObject("reasoning") { put("enabled", false) }
            // Keyed object rather than a bare array: an array is positional, so a model that
            // dropped or merged one entry shifted every translation after it onto the wrong
            // bubble — a whole page of plausible sentences in the wrong balloons, which is worse
            // than no translation at all because nothing about it looks broken.
            putJsonObject("response_format") { put("type", "json_object") }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", systemPrompt(batch))
                }
                addJsonObject {
                    put("role", "user")
                    put("content", sourceObject(batch))
                }
            }
        }.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", "Bearer $apiKey")
            // Both are optional, and both are what OpenRouter attributes the traffic to; without
            // them the app is an anonymous key on a shared free pool.
            .header("HTTP-Referer", APP_URL)
            .header("X-Title", APP_NAME)
            .post(body)
            .build()

        val content = withRateLimitRetry {
            // Recorded before the call, and whatever it returns: a request that failed still
            // spent one of the day's allowance.
            quota?.record()
            slowClient.newCall(request).awaitSuccess().use { response ->
                val reply = json.parseToJsonElement(response.body.string()).jsonObject
                // Which model actually served this. `openrouter/free` picks a different one on
                // every request, so when a chapter comes back badly translated this is the only
                // way to tell whether it was the prompt or the model of the day.
                Logger.d { "OpenRouter used ${reply["model"]?.jsonPrimitive?.contentOrNull}" }
                reply["choices"]!!.jsonArray[0]
                    .jsonObject["message"]!!
                    .jsonObject["content"]!!.jsonPrimitive.content
            }
        }

        val reply = json.parseToJsonElement(content.extractJson())
        // Lifted out before alignment, so everything below sees exactly the shape it always saw.
        // `alignToBatch` falls back on entry *count* when a model renames the keys, and an extra
        // key would push that count off by one and fail a reply that used to line up.
        val translations = if (reply is JsonObject && reply.containsKey(GLOSSARY_KEY)) {
            remember(reply[GLOSSARY_KEY])
            JsonObject(reply.filterKeys { it != GLOSSARY_KEY })
        } else {
            reply
        }
        return alignToBatch(translations, batch.size)
    }

    /**
     * Keeps the terms the model says it settled on, for the manga's notes.
     *
     * First answer wins and the total is capped: the point is that chapter forty calls a
     * character what chapter one called them, so a term that is already decided must not be
     * redecided, and the notes must not grow without bound — they are resent with every batch.
     * Anything that is not a plain string pair is dropped; this is a model's free-form output.
     */
    private fun remember(glossary: JsonElement?) {
        val entries = (glossary as? JsonObject) ?: return
        entries.forEach { (term, value) ->
            if (_learned.size >= MAX_LEARNED_TERMS) return
            // The notes store one `term = value` per line, so a value with a line break in it
            // parses back as two entries and the term quietly loses half its translation.
            val translated = value.asText()?.replace('\n', ' ')?.replace('\r', ' ')?.trim().orEmpty()
            if (term.isBlank() || translated.isBlank() || term.length > MAX_TERM_CHARS) return@forEach
            if (translated.length > MAX_TERM_CHARS) return@forEach
            _learned.putIfAbsent(term.trim(), translated)
        }
    }

    /**
     * Retries a rate limited request, waiting as long as OpenRouter asks.
     *
     * Only 429 is retried: a bad key or a retired model slug fails the same way every time, and
     * retrying it just delays the error the user needs to see.
     */
    private suspend fun <T> withRateLimitRetry(block: suspend () -> T): T {
        repeat(RATE_LIMIT_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: HttpException) {
                if (e.code != HTTP_TOO_MANY_REQUESTS) throw e
                // The tally is per install, so a key used from somewhere else drifts. A 429 is
                // OpenRouter's own word on it and outranks whatever was counted here.
                quota?.exhaustToday()
                Logger.w { "$providerName rate limited, retrying in ${RETRY_DELAY_MS * (attempt + 1)}ms" }
                delay(RETRY_DELAY_MS * (attempt + 1))
            }
        }
        return block()
    }

    private fun sourceObject(batch: List<String>) = buildJsonObject {
        batch.forEachIndexed { i, text -> put(keyOf(i), text) }
    }.toString()

    /**
     * Deliberately short. It is resent with every batch, so each line of it is paid for once per
     * batch and a chapter is several batches — the prose version cost more tokens over a chapter
     * than the dialogue did. OpenRouter's prompt caching cannot pick this up either: its minimum
     * cacheable prefix is 1024 tokens, several times this.
     */
    private fun systemPrompt(batch: List<String>) = """
        Translate comic speech bubbles from ${TranslationLanguages.promptName(fromLang.code)}
        into ${TranslationLanguages.promptName(toLang)}.

        Input is a JSON object of bubbles in reading order, from consecutive pages of one chapter.
        Read all of it first: neighbouring entries are often one sentence split across bubbles.
        Keep names, pronouns and formality consistent.

        - Reply with only a JSON object, same keys (t0, t1...), one translation each. No fences.
        - Every value must be in ${TranslationLanguages.promptName(toLang)}. Returning the source
          text unchanged is never an answer.
        - The text is OCR: translate the obvious meaning, not the typos. Pass through sound
          effects and gibberish as they are.
        - Replace any site link or watermark with "$WATERMARK".
        - The bubble was drawn for the source text, so keep each translation about as long. A
          translation much longer than its source has to be shrunk to fit and stops being legible.
        - Add one more key, "$GLOSSARY_KEY": an object of the proper nouns and recurring terms
          you translated here, source as key, your translation as value. At most
          $GLOSSARY_PER_BATCH, only ones you are sure of. Leave it out if there are none.
    """.trimIndent() + seriesPrompt(batch)

    /**
     * The series context this batch needs, and no more.
     *
     * The notes are the reader's instructions and always apply. The glossary is filtered down to
     * the terms that actually occur in these lines: it is resent with every batch of every
     * chapter, so carrying forty entries to translate a page that mentions two of them is paying
     * for the other thirty over and over.
     */
    private fun seriesPrompt(batch: List<String>): String = buildString {
        if (context.notes.isNotBlank()) {
            append("\n\nNotes from the reader about this series, follow them:\n")
            append(context.notes)
        }
        val glossary = context.glossaryFor(batch)
        if (glossary.isNotEmpty()) {
            append("\n\nTerms this series has already used, reuse them exactly:\n")
            glossary.forEach { (term, translated) -> append("$term = $translated\n") }
        }
    }


    private fun String.extractJson(): String = extractJson(this)

    override fun close() = Unit

    companion object {
        const val OPENROUTER_ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

        /** DeepSeek's API is OpenAI-shaped, so the only thing that differs is the address. */
        const val DEEPSEEK_ENDPOINT = "https://api.deepseek.com/chat/completions"
        private const val APP_URL = "https://github.com/clevim/Karasu"
        private const val APP_NAME = "Karasu"
        private const val WATERMARK = "KARASU_WATERMARK"
        private const val MAX_TOKENS = 4096

        /** The reply key the glossary comes back under. Short: it is resent with every batch. */
        private const val GLOSSARY_KEY = "g"
        private const val GLOSSARY_PER_BATCH = 8

        /** Over a whole series, and resent with every batch — so it has to stay small. */
        private const val MAX_LEARNED_TERMS = 40
        private const val MAX_TERM_CHARS = 60

        /**
         * Source characters per request. Sized so the reply fits [MAX_TOKENS] with room to spare
         * even for a target language that expands, which for an ordinary chapter means one or two
         * requests instead of one per page.
         */
        private const val BATCH_CHARS = 1200

        /** Bubbles per request. Small enough that a slow free model finishes inside the timeout. */
        private const val BATCH_BUBBLES = 20

        private const val TRANSLATE_TIMEOUT_MINUTES = 3L
        private const val RATE_LIMIT_RETRIES = 2
        private const val RETRY_DELAY_MS = 20_000L
        private const val HTTP_TOO_MANY_REQUESTS = 429

    }
}

/**
 * The reply's translations, one per bubble sent, in the order they were sent.
 *
 * The request numbers the bubbles `t0`, `t1`… and asks for the same keys back, because a bare
 * array is positional: a model that drops one entry shifts every translation after it onto
 * the wrong bubble. Keys survive that. What keys do *not* survive is a model that renames
 * them — and `openrouter/free` routes to a different free model on every request, so some
 * fraction of replies come back as `{"translations": [...]}`, or `{"1": ...}`, or a plain
 * array. Every one of those used to line up with nothing, leaving all bubbles blank and the
 * original English text drawn over the page as though it were the translation.
 *
 * So: keys when they are there, position when they are not, and a hard failure when neither
 * fits — never a quiet blank.
 */
internal fun alignToBatch(reply: JsonElement, size: Int): List<String?> {
    when (reply) {
        is JsonArray -> if (reply.size == size) return reply.map { it.asText() }
        is JsonObject -> {
            val keyed = List(size) { reply[keyOf(it)]?.asText() }
            if (keyed.any { it != null }) return keyed
            // No key matched. A single wrapper value is the common shape — the model put the
            // answer under a name of its own — so look inside it before giving up.
            val only = reply.values.singleOrNull()
            if (only != null && only !is JsonPrimitive) return alignToBatch(only, size)
            if (reply.size == size) return reply.values.map { it.asText() }
        }
        else -> Unit
    }
    error("Could not line the reply up with the $size bubbles sent: ${reply.toString().take(200)}")
}

private fun JsonElement.asText(): String? = (this as? JsonPrimitive)?.contentOrNull

/** How the request numbers the bubbles it sends, and how [alignToBatch] looks them up again. */
internal fun keyOf(index: Int) = "t$index"

/**
 * The JSON value out of a chatty reply.
 *
 * Models wrap their answer in prose or ```json fences despite being told not to, and
 * `response_format` is only a request: `openrouter/free` routes to a different free model on
 * every call and not all of them honour it. Object *or* array, because [alignToBatch] can line up
 * either — an earlier version demanded a brace here and so threw away every array reply before
 * the part that understood arrays ever ran, failing whole chapters.
 */
internal fun extractJson(reply: String): String {
    val brace = reply.indexOf('{')
    val bracket = reply.indexOf('[')
    val start = listOf(brace, bracket).filter { it != -1 }.minOrNull()
    requireNotNull(start) { "No JSON in the reply: ${reply.take(200)}" }
    val end = reply.lastIndexOf(if (start == brace) '}' else ']')
    require(end > start) { "Unterminated JSON, the reply was probably cut short: ${reply.take(200)}" }
    return reply.substring(start, end + 1)
}
