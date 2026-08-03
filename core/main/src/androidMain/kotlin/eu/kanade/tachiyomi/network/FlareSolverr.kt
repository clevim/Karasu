package eu.kanade.tachiyomi.network

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.core.preference.Preference
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Minimal client for the FlareSolverr v1 API, used as a fallback when the WebView cannot clear a
 * Cloudflare challenge.
 *
 * FlareSolverr solves the challenge in its own browser and the API offers no way to make it use
 * our User-Agent, so the `cf_clearance` cookie it returns is only valid *together with the browser's
 * User-Agent*. Every caller therefore has to adopt the returned agent for that host, which is why
 * [solve] answers with it and records it in [userAgents].
 */
class FlareSolverr(
    private val cookieJar: AndroidCookieJar,
    private val endpointProvider: () -> String,
    val userAgents: HostUserAgentStore,
) {

    /** Not the app's shared client: that one carries [interceptor.CloudflareInterceptor], and
     * letting a failing FlareSolverr response re-enter it would recurse. */
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            // Has to outlive FlareSolverr's own maxTimeout below, or we abort a solve in progress.
            .readTimeout(MAX_TIMEOUT_MS + 30_000L, TimeUnit.MILLISECONDS)
            .build()
    }

    private val locks = ConcurrentHashMap<String, Any>()
    private val solvedAt = ConcurrentHashMap<String, Long>()

    val isEnabled: Boolean
        get() = endpointProvider().isNotBlank()

    /**
     * Whether [host] has been cleared by FlareSolverr before, and so is worth going straight to.
     *
     * [userAgents] only ever gains a host after a solve that produced real cookies, and it is
     * persisted, so it doubles as the list of hosts the WebView is known to fail on.
     */
    fun hasSolved(host: String): Boolean = isEnabled && userAgents[host] != null

    /**
     * Asks FlareSolverr to clear the challenge for [url], storing the resulting cookies.
     *
     * @return the User-Agent the caller must use for [url] from now on, or null if unavailable,
     *   disabled, or unable to solve.
     */
    fun solve(url: HttpUrl): String? {
        val host = url.host
        if (endpointProvider().isBlank()) return null

        // Opening a source fires a burst of parallel requests (listing plus every cover), so one
        // challenge 403s all of them and each lands here. Solving per request would queue N solves
        // against FlareSolverr's single browser, ~40s apiece, until every call times out. Collapse
        // the burst: one solve per host, and whoever arrives right after it reuses the result.
        return synchronized(locks.computeIfAbsent(host) { Any() }) {
            val since = solvedAt[host]?.let { System.nanoTime() - it }
            if (since != null && since < REUSE_WINDOW_NANOS) {
                userAgents[host]
            } else {
                solveNow(url, host)
            }
        }
    }

    private fun solveNow(url: HttpUrl, host: String): String? {
        val endpoint = endpointProvider().takeIf { it.isNotBlank() } ?: return null

        val solution = try {
            request(endpoint, url)
        } catch (e: IOException) {
            Logger.e(e) { "FlareSolverr request failed" }
            return null
        } catch (e: Exception) {
            Logger.e(e) { "Unable to read FlareSolverr response" }
            return null
        } ?: return null

        // No agent means no usable cf_clearance, so treat it as a failure rather than storing
        // cookies that the very next request would be rejected with.
        val userAgent = solution.userAgent.takeIf { it.isNotBlank() } ?: run {
            Logger.e { "FlareSolverr returned no User-Agent for $host" }
            return null
        }

        // A solve that came back without a single usable cookie cleared nothing. Pinning the agent
        // anyway would send a desktop User-Agent backed by no clearance to that host for good —
        // [userAgents] is persisted — and hand the WebView a fingerprint it cannot back up either.
        val cookies = solution.toCookies(url)
        if (cookies.isEmpty()) {
            Logger.e { "FlareSolverr returned no usable cookies for $host" }
            return null
        }

        cookieJar.saveFromResponse(url, cookies)
        userAgents[host] = userAgent
        solvedAt[host] = System.nanoTime()
        return userAgent
    }

    private fun request(endpoint: String, url: HttpUrl): Solution? {
        val target = endpoint.trim().trimEnd('/').let { if (it.endsWith("/v1")) it else "$it/v1" }
            .toHttpUrlOrNull() ?: run {
                Logger.e { "FlareSolverr endpoint is not a valid URL: $endpoint" }
                return null
            }

        val payload = json.encodeToString(
            SolveRequest.serializer(),
            SolveRequest(url = url.toString(), maxTimeout = MAX_TIMEOUT_MS),
        )

        val body = client.newCall(
            Request.Builder()
                .url(target)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build(),
        ).execute().use { response ->
            // FlareSolverr answers 500 with a JSON body describing the failure, so read either way.
            response.body?.string().orEmpty()
        }

        val parsed = json.decodeFromString(SolveResponse.serializer(), body)
        if (parsed.status != "ok" || parsed.solution == null) {
            Logger.e { "FlareSolverr could not solve ${url.host}: ${parsed.message}" }
            return null
        }
        return parsed.solution
    }

    @Serializable
    private data class SolveRequest(
        val url: String,
        val maxTimeout: Long,
        val cmd: String = "request.get",
    )

    @Serializable
    data class SolveResponse(
        val status: String = "",
        val message: String = "",
        val solution: Solution? = null,
    )

    @Serializable
    data class Solution(
        val userAgent: String = "",
        val cookies: List<SolvedCookie> = emptyList(),
    ) {
        fun toCookies(url: HttpUrl): List<Cookie> = cookies.mapNotNull { it.toCookie(url) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class SolvedCookie(
        val name: String,
        val value: String,
        val domain: String = "",
        val path: String = "/",
        // Epoch *seconds*, or <= 0 for a session cookie. FlareSolverr 3.x speaks Selenium's cookie
        // format and calls this `expiry`; `expires` is the Chrome DevTools name. Reading only one
        // of them meant every cookie parsed as a session cookie, so the cf_clearance that cost a
        // 40s solve died with the process while the User-Agent pinned to it was kept forever.
        @JsonNames("expiry")
        val expires: Double = -1.0,
        val httpOnly: Boolean = false,
        val secure: Boolean = false,
    ) {
        fun toCookie(url: HttpUrl): Cookie? = try {
            Cookie.Builder()
                .name(name)
                .value(value)
                // Cloudflare hands back ".example.com"; okhttp canonicalises hosts and rejects
                // the leading dot, so strip it and keep the cookie domain-wide (not host-only)
                // so subdomains still match.
                .domain(domain.removePrefix(".").ifBlank { url.host })
                .path(path.ifBlank { "/" })
                .apply {
                    if (expires > 0) expiresAt((expires * 1000).toLong())
                    if (httpOnly) httpOnly()
                    if (secure) secure()
                }
                .build()
        } catch (e: IllegalArgumentException) {
            Logger.w(e) { "Discarding malformed FlareSolverr cookie: $name" }
            null
        }
    }

    companion object {
        // The shared client caps a whole call at 2 min, and that budget already paid for the
        // WebView's 30s latch before we got here. 45s leaves room for the retried request instead
        // of letting FlareSolverr's 60s default eat the call timeout.
        private const val MAX_TIMEOUT_MS = 45_000L

        /** How long a fresh solve answers for the rest of the burst that triggered it. */
        private val REUSE_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(20)

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Remembers which User-Agent cleared the challenge for a host.
 *
 * Persisted on purpose: the `cf_clearance` cookie lives in Android's [android.webkit.CookieManager]
 * and outlives the process, so dropping the pairing on restart would make the first request to
 * every solved host 403 and pay for another (~40s) solve.
 */
class HostUserAgentStore(private val pref: Preference<String>) {

    private val agents: MutableMap<String, String> by lazy {
        val stored = pref.get()
        if (stored.isBlank()) {
            mutableMapOf()
        } else {
            try {
                FlareSolverr.json.decodeFromString<Map<String, String>>(stored).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
        }
    }

    @Synchronized
    operator fun get(host: String): String? = agents[host]

    @Synchronized
    operator fun set(host: String, userAgent: String) {
        if (agents.put(host, userAgent) == userAgent) return
        pref.set(FlareSolverr.json.encodeToString(agents.toMap()))
    }

    @Synchronized
    fun clear() {
        agents.clear()
        pref.delete()
    }
}
