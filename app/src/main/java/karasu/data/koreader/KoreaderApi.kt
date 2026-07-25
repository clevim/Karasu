package karasu.data.koreader

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.koreader.models.KoreaderShelf
import karasu.domain.koreader.models.KoreaderShelfEntry
import karasu.domain.koreader.models.KoreaderUpload
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source

/**
 * Talks to the self-hosted shelf container.
 *
 * The contract is documented in `docs/koreader-sync.md` and is deliberately tiny: the container
 * stores CBZ files and the read flag the KOReader plugin sets, and nothing else. Karasu keeps no
 * mirror of that state, so whatever the shelf reports is the truth for the pull direction.
 */
class KoreaderApi(
    private val preferences: KoreaderPreferences,
    private val network: NetworkHelper,
) {

    private val json = Json { ignoreUnknownKeys = true }

    val isConfigured: Boolean get() = preferences.serverUrl().get().isNotBlank()

    /**
     * Trailing slashes are the most common thing to paste wrong, and a doubled slash 404s on most
     * routers, so the base is normalised once here rather than at every call site.
     */
    private fun url(path: String): String {
        val base = preferences.serverUrl().get().trim().trimEnd('/')
        require(base.isNotBlank()) { "KOReader shelf URL is not set" }
        return "$base/$path"
    }

    private fun headers(): Headers {
        val builder = Headers.Builder()
        preferences.apiKey().get().takeIf { it.isNotBlank() }?.let {
            builder.add("Authorization", "Bearer $it")
        }
        return builder.build()
    }

    /** Round-trips the shelf so the settings screen can say whether the URL and key actually work. */
    suspend fun check(): String {
        val request = Request.Builder().url(url("api/health")).headers(headers()).get().build()
        network.client.newCall(request).awaitSuccess().use { response ->
            return response.body.string().take(HEALTH_SNIPPET)
        }
    }

    /** Everything currently on the shelf, including which entries KOReader finished. */
    suspend fun shelf(): List<KoreaderShelfEntry> {
        val request = Request.Builder().url(url("api/shelf")).headers(headers()).get().build()
        network.client.newCall(request).awaitSuccess().use { response ->
            return response.parseAs<KoreaderShelf>().entries
        }
    }

    /**
     * Uploads one chapter.
     *
     * The CBZ is streamed straight off the download directory rather than read into memory —
     * a chapter is routinely tens of megabytes, and this runs on a background worker where an
     * OOM would just look like a sync that never succeeds.
     */
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

    private fun UniFile.asRequestBody(): RequestBody = object : RequestBody() {
        override fun contentType() = CBZ_MEDIA_TYPE
        override fun contentLength() = length()
        override fun writeTo(sink: BufferedSink) {
            openInputStream().use { sink.writeAll(it.source()) }
        }
    }

    private companion object {
        val CBZ_MEDIA_TYPE = "application/vnd.comicbook+zip".toMediaType()
        const val HEALTH_SNIPPET = 200
    }
}
