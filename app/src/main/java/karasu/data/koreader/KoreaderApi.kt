package karasu.data.koreader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.koreader.models.KoreaderShelf
import karasu.domain.koreader.models.KoreaderShelfEntry
import karasu.domain.koreader.models.KoreaderUpload
import karasu.domain.koreader.models.RemoteBackupInfo
import karasu.domain.koreader.models.RemoteBackupListResponse
import karasu.domain.koreader.models.RemoteTranslateRequest
import karasu.domain.koreader.models.RemoteTranslateResponse
import karasu.domain.koreader.models.SyncPayload
import karasu.domain.koreader.models.YatagarasuHealth
import karasu.translation.model.PageTranslation
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.sink
import okio.source
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks to the self-hosted Yatagarasu companion container.
 *
 * Supports Dual-Origin (LAN primary URL + optional Tailscale/VPN fallback URL) and both the
 * v1 KOReader shelf contract (`docs/koreader-sync.md`) and v2 Companion endpoints
 * (`docs/yatagarasu-companion.md`).
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

    private suspend fun isReachable(base: String): Boolean = try {
        val req = Request.Builder().url("$base/api/health").headers(headers()).get().build()
        probeClient.newCall(req).await().use { it.isSuccessful }
    } catch (e: kotlinx.coroutines.CancellationException) {
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
        val request = Request.Builder().url("$base/api/health").headers(headers()).get().build()
        network.client.newCall(request).awaitSuccess().use { response ->
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
        val request = Request.Builder().url(url("api/shelf")).headers(headers()).get().build()
        network.client.newCall(request).awaitSuccess().use { response ->
            return response.parseAs<KoreaderShelf>().entries
        }
    }

    suspend fun upload(meta: KoreaderUpload, cbz: UniFile) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("metadata", json.encodeToString(meta))
            .addFormDataPart(
                "file",
                cbz.name ?: "${meta.chapterId}.cbz",
                cbz.asRequestBody(),
            )
            .build()

        val request = Request.Builder().url(url("api/shelf")).headers(headers()).post(body).build()
        network.client.newCall(request).awaitSuccess().close()
    }

    suspend fun delete(chapterId: Long) {
        val request = Request.Builder()
            .url(url("api/shelf/$chapterId"))
            .headers(headers())
            .delete()
            .build()
        network.client.newCall(request).awaitSuccess().close()
    }

    // ---- v2 Bidirectional Library Sync ----

    suspend fun reconcileSync(payload: SyncPayload): SyncPayload {
        val body = json.encodeToString(payload).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url("api/sync/reconcile"))
            .headers(headers())
            .post(body)
            .build()
        longClient.newCall(request).awaitSuccess().use { response ->
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
        val request = Request.Builder().url(httpUrl).headers(headers()).get().build()
        longClient.newCall(request).await().use { response ->
            if (response.code == 404) return false
            if (!response.isSuccessful) {
                throw IOException("Yatagarasu CBZ download HTTP ${response.code}")
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
        val body = json.encodeToString(requestBody).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url(url("api/translations/translate"))
            .headers(headers())
            .post(body)
            .build()
        longClient.newCall(request).awaitSuccess().use { response ->
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
        val request = Request.Builder().url(httpUrl).headers(headers()).get().build()
        network.client.newCall(request).await().use { response ->
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
        val body = json.encodeToString(pages).toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder().url(httpUrl).headers(headers()).put(body).build()
        network.client.newCall(request).awaitSuccess().close()
    }

    // ---- v2 Remote Backups (.tachibk) ----

    suspend fun listBackups(): List<RemoteBackupInfo> {
        val request = Request.Builder().url(url("api/backups")).headers(headers()).get().build()
        network.client.newCall(request).awaitSuccess().use { response ->
            return json.decodeFromString<RemoteBackupListResponse>(response.body.string()).backups
        }
    }

    suspend fun uploadBackup(file: UniFile, filename: String): RemoteBackupInfo {
        val reqHeaders = headers().newBuilder()
            .add("X-Backup-Filename", filename)
            .build()
        val request = Request.Builder()
            .url(url("api/backups"))
            .headers(reqHeaders)
            .post(file.asRequestBody(OCTET_MEDIA_TYPE))
            .build()
        longClient.newCall(request).awaitSuccess().use { response ->
            return json.decodeFromString(response.body.string())
        }
    }

    suspend fun downloadBackup(id: String, destFile: UniFile) {
        val httpUrl = "${resolveBase()}/api/backups".toHttpUrl().newBuilder()
            .addPathSegment(id)
            .build()
        val request = Request.Builder().url(httpUrl).headers(headers()).get().build()
        longClient.newCall(request).awaitSuccess().use { response ->
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

    private companion object {
        val CBZ_MEDIA_TYPE = "application/vnd.comicbook+zip".toMediaType()
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val OCTET_MEDIA_TYPE = "application/octet-stream".toMediaType()
        const val HEALTH_SNIPPET = 200
        const val PROBE_TIMEOUT_MS = 2500L
        const val ORIGIN_CACHE_TTL_MS = 60_000L
        const val LONG_READ_TIMEOUT_SECONDS = 120L
    }
}
