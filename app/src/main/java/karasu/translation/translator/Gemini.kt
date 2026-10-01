package karasu.translation.translator

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/*
 * What the two Gemini paths share: the text translator ([OpenAiChatTranslator] with `gemini`)
 * and the one that reads the pages itself ([GeminiPageReader]).
 *
 * Google's own `generateContent`, not its OpenAI-compatible endpoint: only this one takes safety
 * settings, and a manga is fights and blood. With the defaults a page of it comes back blocked.
 */

/**
 * The batch could not be answered as sent, and half of it probably can: Google blocked something
 * in it, or the reply ran out of room before the JSON closed. The caller splits and retries,
 * which is what manga-image-translator does — the alternative is losing the whole chapter to one
 * page.
 */
class RetryInHalvesException(message: String) : IllegalStateException(message)

internal fun geminiRequest(apiKey: String, model: String, body: JsonObject): Request = Request.Builder()
    .url("$GEMINI_MODELS_URL$model:generateContent")
    .header("x-goog-api-key", apiKey)
    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
    .build()

/**
 * Generation settings and safety settings, the parts every Gemini request carries.
 *
 * @param schema a response schema, when the reply has a fixed shape. JSON mode alone is "a strong
 *   hint"; a schema is what makes the model unable to rename the keys.
 */
internal fun JsonObjectBuilder.putGeminiConfig(model: String, maxTokens: Int, schema: JsonObject? = null) {
    putJsonObject("generationConfig") {
        put("temperature", 0.3)
        // Thinking counts against this cap on Gemini, so it is sized for the reply plus whatever
        // the model spends deliberating first.
        put("maxOutputTokens", maxTokens)
        put("responseMimeType", "application/json")
        schema?.let { put("responseSchema", it) }
        // Gemini 3 cannot switch thinking off, only turn it down; every 3.x accepts "low".
        // ponytail: 2.5 and Gemma left on their defaults, the token cap absorbs it.
        if (model.startsWith("gemini-3")) {
            putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
        }
    }
    putJsonArray("safetySettings") {
        SAFETY_CATEGORIES.forEach { category ->
            addJsonObject {
                put("category", category)
                put("threshold", "BLOCK_NONE")
            }
        }
    }
}

/** The reply's text, past any thought parts. Throws [RetryInHalvesException] when splitting can help. */
internal fun geminiText(reply: JsonObject, providerName: String): String {
    Logger.d { "$providerName used ${reply["modelVersion"]?.jsonPrimitive?.contentOrNull}" }
    val candidate = reply["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        ?: throw RetryInHalvesException(
            "$providerName refused this batch: " +
                "${reply["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull}",
        )
    val finish = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
    val text = candidate["content"]?.jsonObject?.get("parts")?.jsonArray.orEmpty()
        .map { it.jsonObject }
        .filterNot { it["thought"]?.jsonPrimitive?.contentOrNull == "true" }
        .mapNotNull { it["text"]?.jsonPrimitive?.contentOrNull }
        .joinToString("")
    // A reply cut short is unparseable however much of it arrived, and half the batch fits.
    if (finish == "MAX_TOKENS") throw RetryInHalvesException("$providerName ran out of room for the reply")
    if (text.isBlank()) {
        if (finish in SPLITTABLE_FINISH) throw RetryInHalvesException("$providerName refused this batch: $finish")
        error("$providerName returned no text ($finish)")
    }
    return text
}

/**
 * The shared client gives up after two minutes, which is right for fetching a page and wrong
 * for this: a free model generating a batch of translations at six tokens a second was being
 * cut off mid-answer.
 */
internal fun slowClient(network: NetworkHelper): OkHttpClient = network.client.newBuilder()
    .readTimeout(TRANSLATE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    .callTimeout(TRANSLATE_TIMEOUT_MINUTES, TimeUnit.MINUTES)
    .build()

/**
 * Retries a rate limited request, waiting a little longer each time.
 *
 * Only 429 is retried: a bad key or a retired model slug fails the same way every time, and
 * retrying it just delays the error the user needs to see.
 *
 * @param onRateLimited told about every 429, for a provider that keeps its own tally.
 */
internal suspend fun <T> withRateLimitRetry(
    providerName: String,
    modelName: String,
    onRateLimited: () -> Unit = {},
    block: suspend () -> T,
): T {
    repeat(RATE_LIMIT_RETRIES) { attempt ->
        try {
            return block()
        } catch (e: HttpException) {
            if (e.code != HTTP_TOO_MANY_REQUESTS) throw e.orUnknownModel(providerName, modelName)
            // Google says which limit ran out. The per-minute one is worth waiting for; the
            // daily one is not, and two retries later the reader would get "HTTP error 429".
            if (e.body?.contains("PerDay") == true) {
                throw IllegalStateException(
                    "$providerName's free requests for \"$modelName\" ran out for today. " +
                        "Pick another model in the settings or wait for the daily reset.",
                )
            }
            onRateLimited()
            Logger.w { "$providerName rate limited, retrying in ${RETRY_DELAY_MS * (attempt + 1)}ms" }
            delay(RETRY_DELAY_MS * (attempt + 1))
        }
    }
    return try {
        block()
    } catch (e: HttpException) {
        throw e.orUnknownModel(providerName, modelName)
    }
}

/**
 * Runs [items] through [request], splitting any batch that comes back as [RetryInHalvesException]
 * until it goes through or is down to one item.
 *
 * @return why the last batch that never went through failed, or null when every one did.
 */
internal suspend fun <T> inHalves(
    items: List<T>,
    request: suspend (List<T>) -> Unit,
): Throwable? {
    if (items.isEmpty()) return null
    return try {
        request(items)
        null
    } catch (e: RetryInHalvesException) {
        if (items.size == 1) return e
        Logger.w { "${e.message}; retrying as two batches of ${items.size / 2}" }
        val first = inHalves(items.subList(0, items.size / 2), request)
        val second = inHalves(items.subList(items.size / 2, items.size), request)
        second ?: first
    }
}

/**
 * A model slug that was retired, or mistyped into the settings field, is the one failure here
 * the reader can actually fix — and "HTTP error 400" does not tell them that.
 */
private fun HttpException.orUnknownModel(providerName: String, modelName: String): Throwable =
    if (code in UNKNOWN_MODEL_CODES && body?.contains("model", ignoreCase = true) == true) {
        IllegalStateException(
            "$providerName does not have the model \"$modelName\". Pick another one in the settings.",
        )
    } else {
        this
    }

private const val GEMINI_MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models/"

/** Every category that can be turned down. Anything left out stays at Google's default. */
private val SAFETY_CATEGORIES = listOf(
    "HARM_CATEGORY_HARASSMENT",
    "HARM_CATEGORY_HATE_SPEECH",
    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
    "HARM_CATEGORY_DANGEROUS_CONTENT",
)

/** Finish reasons that are about the content, where a smaller batch may well get through. */
private val SPLITTABLE_FINISH = setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "RECITATION", "IMAGE_SAFETY", "OTHER")

internal val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

private const val TRANSLATE_TIMEOUT_MINUTES = 3L
private const val RATE_LIMIT_RETRIES = 2
private const val RETRY_DELAY_MS = 20_000L
private const val HTTP_TOO_MANY_REQUESTS = 429

/** What a provider answers when it does not have the model it was asked for. */
private val UNKNOWN_MODEL_CODES = setOf(400, 404)
