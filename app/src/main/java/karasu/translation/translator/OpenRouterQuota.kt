package karasu.translation.translator

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import karasu.domain.translation.TranslationPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request
import uy.kohesive.injekt.injectLazy
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * How many free-model requests are left today.
 *
 * Counted here rather than asked for, because OpenRouter cannot answer it. `/api/v1/key` reports
 * *credits*, and a free model costs nothing — `usage_daily` sits at zero however many requests
 * you made. The daily allowance for free models is a request count OpenRouter enforces but does
 * not publish, so the only way to know is to keep the tally.
 *
 * The one thing the endpoint does settle is which allowance applies: `is_free_tier` is false once
 * the account has ever bought credits, and that permanently raises the cap from 50 a day to 1000.
 *
 * ponytail: the tally is per install. A key used from somewhere else too will read low here, and
 * OpenRouter's own 429 stays the authority. Good enough to stop a chapter before it starts.
 */
class OpenRouterQuota(private val preferences: TranslationPreferences) {

    private val network: NetworkHelper by injectLazy()
    private val json = Json { ignoreUnknownKeys = true }

    /** Requests already made today, zero once the UTC day has rolled over. */
    fun used(today: String = utcDay()): Int =
        if (preferences.quotaDay().get() == today) preferences.quotaUsed().get() else 0

    fun cap(): Int = preferences.quotaCap().get()

    fun remaining(): Int = (cap() - used()).coerceAtLeast(0)

    fun record(requests: Int = 1) {
        val today = utcDay()
        val used = used(today)
        preferences.quotaDay().set(today)
        preferences.quotaUsed().set(used + requests)
    }

    /** OpenRouter says the day is spent, whatever this install counted. Its word wins. */
    fun exhaustToday() {
        preferences.quotaDay().set(utcDay())
        preferences.quotaUsed().set(cap())
    }

    /**
     * Asks OpenRouter which allowance this key gets. Failures are ignored on purpose: not knowing
     * the cap is no reason to refuse to translate, and the previous answer is still the best guess.
     */
    suspend fun refreshCap(apiKey: String) {
        if (apiKey.isBlank()) return
        runCatching {
            val request = Request.Builder()
                .url(KEY_ENDPOINT)
                .header("Authorization", "Bearer $apiKey")
                .build()
            val data = network.client.newCall(request).awaitSuccess().use { response ->
                json.parseToJsonElement(response.body.string()).jsonObject["data"]!!.jsonObject
            }
            val freeTier = data["is_free_tier"]?.jsonPrimitive?.boolean ?: true
            preferences.quotaCap().set(if (freeTier) FREE_DAILY else PAID_DAILY)
        }.onFailure { Logger.w(it) { "Could not read the OpenRouter key's allowance" } }
    }

    companion object {
        private const val KEY_ENDPOINT = "https://openrouter.ai/api/v1/key"

        /** Free models, on an account that has never bought credits. */
        const val FREE_DAILY = 50

        /** Free models, once the account has ever bought 10 credits. Permanent. */
        const val PAID_DAILY = 1000

        fun utcDay(): String = LocalDate.now(ZoneOffset.UTC).toString()
    }
}
