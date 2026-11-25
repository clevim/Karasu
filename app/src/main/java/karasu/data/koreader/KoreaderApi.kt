package karasu.data.koreader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.parseAs
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.koreader.models.KoreaderShelf
import karasu.domain.koreader.models.KoreaderShelfEntry
import karasu.domain.koreader.models.KoreaderUpload
import karasu.domain.koreader.models.RemoteBackupInfo
import karasu.domain.koreader.models.RemoteBackupListResponse
import karasu.domain.koreader.models.RemoteSeriesNotes
import karasu.domain.koreader.models.RemoteTranslateRequest
import karasu.domain.koreader.models.RemoteTranslateResponse
import karasu.domain.koreader.models.SyncPayload
import karasu.domain.koreader.models.YatagarasuHealth
import karasu.translation.model.PageTranslation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.sink
import okio.source
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Talks to the self-hosted Yatagarasu companion container.
 *
 * Supports Dual-Origin (LAN primary URL + optional Tailscale/VPN fallback URL), sleep/wake-on-request
 * reverse proxies (`502`/`503`/`504`), and both the v1 KOReader shelf contract (`docs/koreader-sync.md`)
 * and v2 Companion endpoints (`docs/yatagarasu-companion.md`).
 */
class KoreaderApi(
    private val preferences: KoreaderPreferences,
    private val network: NetworkHelper,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    private var cachedOrigin: String? = null

    @Volatile
    private var cachedOriginAt: Long = 0L

    private val probeClient by lazy {
        network.client.newBuilder()
            .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    private val longClient by lazy {
        network.client.newBuilder()
            .readTimeout(LONG_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    val isConfigured: Boolean
        get() = preferences.serverUrl().get().isNotBlank() ||
            preferences.fallbackServerUrl().get().isNotBlank()

    fun invalidateOriginCache() {
        cachedOrigin = null
        cachedOriginAt = 0L
    }

    private fun primaryBase(): String = preferences.serverUrl().get().trim().trimEnd('/')

    private fun fallbackBase(): String = preferences.fallbackServerUrl().get().trim().trimEnd('/')

    /**
     * Picks the reachable origin: tries the primary (LAN) URL first with a short timeout when a
     * fallback (Tailscale/VPN) URL is also configured, caching the winner for [ORIGIN_CACHE_TTL_MS].
     */
    private suspend fun resolveBase(forceProbe: Boolean = false): String {
        val primary = primaryBase()
        val fallback = fallbackBase()
        require(primary.isNotBlank() || fallback.isNotBlank()) { "Yatagarasu URL is not set" }

        if (primary.isBlank()) return fallback
        if (fallback.isBlank()) return primary

        val now = System.currentTimeMillis()
        val cached = cachedOrigin
        if (!forceProbe && cached != null && (cached == primary || cached == fallback) &&
            now - cachedOriginAt < ORIGIN_CACHE_TTL_MS
        ) {
            return cached
        }

        val chosen = if (isReachable(primary)) primary else fallback
        cachedOrigin = chosen
        cachedOriginAt = now
        return chosen
    }

    /**
     * Treats HTTP 503 as reachable because wake-on-request proxies return a 503 splash page while
     * starting the container — [executeWithWakeRetry] will wait for it to finish waking up.
     */
    private suspend fun isReachable(base: String): Boolean = try {
        val req = Request.Builder().url("$base/api/health").headers(headers()).get().build()
        probeClient.newCall(req).await().use { it.code in 200..299 || it.code == 503 }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private suspend fun url(path: String): String = "${resolveBase()}/$path"

    private fun headers(): Headers {
        val builder = Headers.Builder()
        preferences.apiKey().get().takeIf { it.isNotBlank() }?.let {
            builder.add("Authorization", "Bearer $it")
        }
        return builder.build()
    }

    /**
     * Executes an HTTP request with automatic retries for sleep/wake-on-request proxies
     * (`502`/`503`/`504`/`521..524` or transient timeout/connect errors while a container boots).
     */
    private suspend fun executeWithWakeRetry(
        client: OkHttpClient,
        requestBuilder: () -> Request,
        requireSuccess: Boolean = true,
    ): Response {
        var attempt = 0
        while (true) {
            val response = try {
                client.newCall(requestBuilder()).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (attempt < WAKE_RETRY_DELAYS_MS.size && isTransientWakeException(e)) {
                    delay(WAKE_RETRY_DELAYS_MS[attempt])
                    attempt++
                    continue
                }
                throw e
            }

            if (isWakeStatusCode(response.code) && attempt < WAKE_RETRY_DELAYS_MS.size) {
                response.close()
                delay(WAKE_RETRY_DELAYS_MS[attempt])
                attempt++
                continue
            }

            if (requireSuccess && !response.isSuccessful) {
                response.use { res ->
                    val errBody = runCatching { res.peekBody(MAX_ERROR_BODY_BYTES).string() }.getOrDefault("")
                    throw IOException(formatHttpError(res.code, errBody))
                }
            }

            return response
        }
    }

    data class HealthResult(
        val health: YatagarasuHealth,
        val rawBody: String,
        val origin: String,
        val isFallback: Boolean,
        val latencyMs: Long,
    )

    /** Round-trips the server so settings can show version, latency, active origin and modules. */
    suspend fun checkHealth(): HealthResult {
        val start = System.currentTimeMillis()
        val base = resolveBase(forceProbe = true)
        val reqHeaders = headers()
        executeWithWakeRetry(
            client = network.client,
            requestBuilder = { Request.Builder().url("$base/api/health").headers(reqHeaders).get().build() },
        ).use { response ->
            val raw = response.body.string()
            val parsed = runCatching { json.decodeFromString<YatagarasuHealth>(raw) }
                .getOrDefault(YatagarasuHealth())
            return HealthResult(
                health = parsed,
                rawBody = raw.take(HEALTH_SNIPPET),
                origin = base,
                isFallback = base == fallbackBase() && primaryBase().isNotBlank(),
                latencyMs = System.currentTimeMillis() - start,
            )
        }
    }

    /** Backwards-compatible health snippet for callers expecting a plain string. */
    suspend fun check(): String = checkHealth().rawBody

    // ---- v1 KOReader Shelf ----

    suspend fun shelf(): List<KoreaderShelfEntry> {
        val targetUrl = url("api/shelf")
        val reqHeaders = headers()
        executeWithWakeRetry(
            client = network.client,
            requestBuilder = { Request.Builder().url(targetUrl).headers(reqHeaders).get().build() },
        ).use { response ->
            return response.parseAs<KoreaderShelf>().entries
        }
    }

    suspend fun upload(meta: KoreaderUpload, cbz: UniFile) {
        val targetUrl = url("api/shelf")
        val reqHeaders = headers()
        val metadataJson = json.encodeToString(meta)
        val fileName = cbz.name ?: "${meta.chapterId}.cbz"
        executeWithWakeRetry(
            client = longClient,
            requestBuilder = {
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("metadata", metadataJson)
                    .addFormDataPart("file", fileName, cbz.asRequestBody())
                    .build()
                Request.Builder().url(targetUrl).headers(reqHeaders).post(body).build()
            },
        ).close()
    }

    suspend fun delete(chapterId: Long) {
        val targetUrl = url("api/shelf/$chapterId")
        val reqHeaders = headers()
        executeWithWakeRetry(
            client = network.client,
            requestBuilder = { Request.Builder().url(targetUrl).headers(reqHeaders).delete().build() },
        ).close()
    }

    // ---- v2 Bidirectional Library Sync ----

    suspend fun reconcileSync(payload: SyncPayload): SyncPayload {
        val targetUrl = url("api/sync/reconcile")
        val reqHeaders = headers()
        val encodedPayload = json.encodeToString(payload)
        executeWithWakeRetry(
            client = longClient,
            requestBuilder = {
                Request.Builder()
                    .url(targetUrl)
                    .headers(reqHeaders)
                    .post(encodedPayload.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
        ).use { response ->
            return json.decodeFromString(response.body.string())
        }
    }

    // ---- v2 Smart CBZ Downloader (Server -> App) ----

    /**
     * Streams a chapter CBZ from Yatagarasu (or Suwayomi via Yatagarasu) directly into [destFile].
     * Returns `true` when the file was downloaded from the companion, `false` when the chapter is
     * not available (404) so the caller can fall back to the internet source.
     */
    suspend fun downloadChapterCbz(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
        acquire: Boolean,
        destFile: UniFile,
    ): Boolean {
        val httpUrl = "${resolveBase()}/api/chapters/cbz".toHttpUrl().newBuilder()
            .addQueryParameter("sourceId", sourceId.toString())
            .addQueryParameter("mangaUrl", mangaUrl)
            .addQueryParameter("chapterUrl", chapterUrl)
            .addQueryParameter("acquire", acquire.toString())
            .build()
        val reqHeaders = headers()
        executeWithWakeRetry(
            client = longClient,
            requestBuilder = { Request.Builder().url(httpUrl).headers(reqHeaders).get().build() },
            requireSuccess = false,
        ).use { response ->
            if (response.code == 404) return false
            if (!response.isSuccessful) {
                val errBody = runCatching { response.peekBody(MAX_ERROR_BODY_BYTES).string() }.getOrDefault("")
                throw IOException(formatHttpError(response.code, errBody))
            }
            destFile.openOutputStream().use { out ->
                out.sink().use { sink ->
                    response.body.source().use { src ->
                        src.readAll(sink)
                    }
                }
            }
            return destFile.length() > 0
        }
    }

    // ---- v2 Remote Translation & Shared Cache ----

    suspend fun translatePages(requestBody: RemoteTranslateRequest): RemoteTranslateResponse {
        val targetUrl = url("api/translations/translate")
        val reqHeaders = headers()
        val encodedBody = json.encodeToString(requestBody)
        executeWithWakeRetry(
            client = longClient,
            requestBuilder = {
                Request.Builder()
                    .url(targetUrl)
                    .headers(reqHeaders)
                    .post(encodedBody.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
        ).use { response ->
            return json.decodeFromString(response.body.string())
        }
    }

    suspend fun getChapterTranslation(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
        toLang: String,
    ): Map<String, PageTranslation>? {
        val httpUrl = "${resolveBase()}/api/translations/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("sourceId", sourceId.toString())
            .addQueryParameter("mangaUrl", mangaUrl)
            .addQueryParameter("chapterUrl", chapterUrl)
            .addQueryParameter("toLang", toLang)
            .build()
        val reqHeaders = headers()
        executeWithWakeRetry(
            client = network.client,
            requestBuilder = { Request.Builder().url(httpUrl).headers(reqHeaders).get().build() },
            requireSuccess = false,
        ).use { response ->
            if (response.code == 404 || !response.isSuccessful) return null
            return json.decodeFromString(response.body.string())
        }
    }

    suspend fun putChapterTranslation(
        sourceId: Long,
        mangaUrl: String,
        chapterUrl: String,
        toLang: String,
        pages: Map<String, PageTranslation>,
    ) {
        val httpUrl = "${resolveBase()}/api/translations/chapter".toHttpUrl().newBuilder()
            .addQueryParameter("sourceId", sourceId.toString())
            .addQueryParameter("mangaUrl", mangaUrl)
            .addQueryParameter("chapterUrl", chapterUrl)
            .addQueryParameter("toLang", toLang)
            .build()
        val reqHeaders = headers()
        val encodedPages = json.encodeToString(pages)
        executeWithWakeRetry(
            client = network.client,
            requestBuilder = {
                Request.Builder()
                    .url(httpUrl)
                    .headers(reqHeaders)
                    .put(encodedPages.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
        ).close()
    }

    suspend fun getTranslationContext(
        sourceId: Long,
        mangaUrl: String,
    ): RemoteSeriesNotes? {
        val httpUrl = "${resolveBase()}/api/translations/context".toHttpUrl().newBuilder()
            .addQueryParameter("sourceId", sourceId.toString())
            .addQueryParameter("mangaUrl", mangaUrl)
            .build()
        val reqHeaders = headers()
        executeWithWakeRetry(
            client = network.client,
            requestBuilder = { Request.Builder().url(httpUrl).headers(reqHeaders).get().build() },
            requireSuccess = false,
        ).use { response ->
            if (response.code == 404 || !response.isSuccessful) return null
            return json.decodeFromString(response.body.string())
        }
    }

    suspend fun putTranslationContext(
        sourceId: Long,
        mangaUrl: String,
        notes: RemoteSeriesNotes,
    ) {
        val httpUrl = "${resolveBase()}/api/translations/context".toHttpUrl().newBuilder()
            .addQueryParameter("sourceId", sourceId.toString())
            .addQueryParameter("mangaUrl", mangaUrl)
            .build()
        val reqHeaders = headers()
        val encodedNotes = json.encodeToString(notes)
        executeWithWakeRetry(
            client = network.client,
            requestBuilder = {
                Request.Builder()
                    .url(httpUrl)
                    .headers(reqHeaders)
                    .put(encodedNotes.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            },
        ).close()
    }

    // ---- v2 Remote Backups (.tachibk) ----

    suspend fun listBackups(): List<RemoteBackupInfo> {
        val targetUrl = url("api/backups")
        val reqHeaders = headers()
        executeWithWakeRetry(
            client = network.client,
            requestBuilder = { Request.Builder().url(targetUrl).headers(reqHeaders).get().build() },
        ).use { response ->
            return json.decodeFromString<RemoteBackupListResponse>(response.body.string()).backups
        }
    }

    suspend fun uploadBackup(file: UniFile, filename: String): RemoteBackupInfo {
        val targetUrl = url("api/backups")
        val reqHeaders = headers().newBuilder()
            .add("X-Backup-Filename", filename)
            .build()
        executeWithWakeRetry(
            client = longClient,
            requestBuilder = {
                Request.Builder()
                    .url(targetUrl)
                    .headers(reqHeaders)
                    .post(file.asRequestBody(OCTET_MEDIA_TYPE))
                    .build()
            },
        ).use { response ->
            return json.decodeFromString(response.body.string())
        }
    }

    suspend fun downloadBackup(id: String, destFile: UniFile) {
        val httpUrl = "${resolveBase()}/api/backups".toHttpUrl().newBuilder()
            .addPathSegment(id)
            .build()
        val reqHeaders = headers()
        executeWithWakeRetry(
            client = longClient,
            requestBuilder = { Request.Builder().url(httpUrl).headers(reqHeaders).get().build() },
        ).use { response ->
            destFile.openOutputStream().use { out ->
                out.sink().use { sink ->
                    response.body.source().use { src ->
                        src.readAll(sink)
                    }
                }
            }
        }
    }

    private fun UniFile.asRequestBody(mediaType: okhttp3.MediaType = CBZ_MEDIA_TYPE): RequestBody = object : RequestBody() {
        override fun contentType() = mediaType
        override fun contentLength() = length()
        override fun writeTo(sink: BufferedSink) {
            openInputStream().use { sink.writeAll(it.source()) }
        }
    }

    companion object {
        private val CBZ_MEDIA_TYPE = "application/vnd.comicbook+zip".toMediaType()
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val WAKE_RETRY_DELAYS_MS = longArrayOf(2_000L, 3_000L, 5_000L, 7_000L, 8_000L)
        private val HTML_TITLE_REGEX = Regex(
            "<title[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        private val WHITESPACE_REGEX = Regex("\\s+")
        private const val MAX_ERROR_BODY_BYTES = 4096L
        private const val MAX_ERROR_SNIPPET_CHARS = 120
        private const val HEALTH_SNIPPET = 200
        private const val PROBE_TIMEOUT_MS = 2500L
        private const val ORIGIN_CACHE_TTL_MS = 60_000L
        private const val LONG_READ_TIMEOUT_SECONDS = 120L

        internal fun isWakeStatusCode(code: Int): Boolean =
            code == 502 || code == 503 || code == 504 || code in 521..524

        internal fun isTransientWakeException(e: Throwable): Boolean {
            var cur: Throwable? = e
            while (cur != null) {
                if (cur is SocketTimeoutException || cur is ConnectException) return true
                cur = cur.cause
            }
            return false
        }

        internal fun formatHttpError(code: Int, rawBody: String): String {
            val trimmed = rawBody.trim()
            if (trimmed.isEmpty()) return "HTTP $code"

            val looksLikeHtml = trimmed.contains("<!doctype", ignoreCase = true) ||
                trimmed.contains("<html", ignoreCase = true)
            if (looksLikeHtml) {
                val title = HTML_TITLE_REGEX.find(trimmed)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.replace(WHITESPACE_REGEX, " ")
                    ?.trim()
                    ?.take(MAX_ERROR_SNIPPET_CHARS)
                    .orEmpty()
                return if (title.isNotEmpty()) "HTTP $code ($title)" else "HTTP $code"
            }

            val cleanSnippet = trimmed.replace(WHITESPACE_REGEX, " ").take(MAX_ERROR_SNIPPET_CHARS).trim()
            return if (cleanSnippet.isEmpty()) "HTTP $code" else "HTTP $code: $cleanSnippet"
        }
    }
}
