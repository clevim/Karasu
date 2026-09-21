package karasu.domain.recommendation

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.library.custom.model.CustomMangaInfo
import karasu.domain.track.interactor.GetTrack
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What the bound trackers know about a library entry that its source did not say: the genres
 * and tags they file it under, and every other name it goes by.
 *
 * @param key which tracks this was built from (`syncId:mediaId`, sorted), so binding a new
 *   tracker gets it fetched again and nothing else does.
 * @param status what the trackers say the series is doing, as an [SManga] status, or null when
 *   none said. A source rarely updates its own; the trackers are kept current by people.
 * @param fetchedAt when this was read. Status changes, so it is read again after a month.
 */
@Serializable
data class TrackerExtra(
    val key: String,
    val tags: List<String>,
    val titles: List<String>,
    val status: Int? = null,
    val fetchedAt: Long = 0,
)

/** In a preference, keyed by manga id, so a backup carries it and a restore keeps the names. */
class TrackerExtrasStore(private val preferences: PreferencesHelper) {

    @Volatile
    private var cached: Map<Long, TrackerExtra>? = null

    fun all(): Map<Long, TrackerExtra> = cached ?: runCatching {
        Json.decodeFromString<Map<Long, TrackerExtra>>(preferences.recommendationTrackerExtras().get())
    }.getOrDefault(emptyMap()).also { cached = it }

    fun get(mangaId: Long?): TrackerExtra? = mangaId?.let { all()[it] }

    fun putAll(extras: Map<Long, TrackerExtra>) {
        if (extras.isEmpty()) return
        val next = all() + extras
        preferences.recommendationTrackerExtras().set(Json.encodeToString(next))
        cached = next
    }
}

/**
 * Fills [TrackerExtrasStore] for every tracked entry whose tracks changed since last time, and
 * writes the tags into the entry's own genre list so they show on its page and teach the
 * profile from then on.
 *
 * A pt-BR source often lists a series under the name the scanlation group gave it, which no
 * tracker knows; the trackers' names are what lets migration and merged-source search find the
 * same series elsewhere. AniList is asked in batches of fifty; the others one entry at a time,
 * which is why this only runs at night and only for what changed.
 */
class EnrichFromTrackers(
    private val getLibraryManga: GetLibraryManga,
    private val getTrack: GetTrack,
    private val network: NetworkHelper,
    private val store: TrackerExtrasStore,
    private val customMangaManager: CustomMangaManager,
) {
    suspend fun await(now: Long = System.currentTimeMillis(), onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }) {
        val library = getLibraryManga.await().distinctBy { it.manga.id }.map { it.manga }
        val tracks = getTrack.awaitAll().groupBy { it.manga_id }
        val pending = library.mapNotNull { manga ->
            val its = tracks[manga.id].orEmpty().filter { it.sync_id in KNOWN }
            if (its.isEmpty()) return@mapNotNull null
            val key = its.map { "${it.sync_id}:${it.media_id}" }.sorted().joinToString(",")
            val have = store.get(manga.id)
            if (have?.key == key && have.fetchedAt > now - REFRESH_MS) null else Triple(manga, its, key)
        }
        if (pending.isEmpty()) return
        onProgress(0, pending.size)

        // AniList answers fifty at once; ask it first, for everyone, then fill the gaps.
        val anilist = pending.flatMap { (_, its, _) -> its.filter { it.sync_id == TrackManager.ANILIST }.map { it.media_id } }
            .distinct()
            .chunked(50)
            .flatMap { ids -> runCatching { fromAniList(ids) }.onFailure { Logger.w(it) { "AniList batch failed" } }.getOrDefault(emptyMap()).entries }
            .associate { it.key to it.value }

        val collected = mutableMapOf<Long, TrackerExtra>()
        pending.forEachIndexed { index, (manga, its, key) ->
            val found = its.mapNotNull { track ->
                when (track.sync_id) {
                    TrackManager.ANILIST -> anilist[track.media_id]
                    TrackManager.MYANIMELIST -> withTimeoutOrNull(REQUEST_TIMEOUT_MS) { runCatching { fromMyAnimeList(track) }.getOrNull() }
                    TrackManager.KITSU -> withTimeoutOrNull(REQUEST_TIMEOUT_MS) { runCatching { fromKitsu(track) }.getOrNull() }
                    TrackManager.MANGA_UPDATES -> withTimeoutOrNull(REQUEST_TIMEOUT_MS) { runCatching { fromMangaUpdates(track) }.getOrNull() }
                    else -> null
                }
            }
            onProgress(index + 1, pending.size)
            // Nothing answered — the network, most likely. Not stored, so it is asked again.
            if (found.isEmpty()) return@forEachIndexed
            val extra = TrackerExtra(
                key = key,
                tags = found.flatMap { it.tags }.mapNotNull(::normalizeTag).distinctBy { it.lowercase() },
                titles = found.flatMap { it.titles }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
                // Finished beats hiatus beats releasing: the most final word any tracker gave.
                status = found.mapNotNull { it.status }.maxByOrNull { STATUS_RANK.indexOf(it) },
                fetchedAt = now,
            )
            collected[manga.id!!] = extra
            if (extra.tags.isNotEmpty()) runCatching { addToGenres(manga, extra.tags) }
        }
        // One write: the preference is the whole map, and rewriting it per entry is quadratic.
        store.putAll(collected)
    }

    /**
     * The tags become part of the entry's genre list, as a custom edit so a refresh from the
     * source does not wipe them. An edit the reader already made is extended, never replaced.
     */
    private suspend fun addToGenres(manga: Manga, tags: List<String>) {
        val current = customMangaManager.getManga(manga)
        val genres = (current?.genre ?: manga.originalGenre)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        val known = genres.mapNotNull(::normalizeTag).map { it.lowercase() }.toSet()
        val missing = tags.filter { it.lowercase() !in known }
        if (missing.isEmpty()) return
        customMangaManager.saveMangaInfo(
            CustomMangaInfo(
                mangaId = manga.id!!,
                title = current?.title?.takeIf { it.isNotBlank() },
                author = current?.author,
                artist = current?.artist,
                description = current?.description,
                genre = (genres + missing).joinToString(", "),
                status = current?.status?.takeIf { it != -1 },
            ),
        )
    }

    private data class Found(val tags: List<String>, val titles: List<String>, val status: Int? = null)

    /** A tracker's status word as the app's status number, or null for anything it does not know. */
    private fun statusOf(word: String?): Int? = when (word?.lowercase()) {
        "releasing", "currently_publishing", "current", "ongoing" -> SManga.ONGOING
        "hiatus", "on_hiatus" -> SManga.ON_HIATUS
        "finished", "complete", "completed" -> SManga.COMPLETED
        "cancelled", "canceled", "discontinued" -> SManga.CANCELLED
        else -> null
    }

    private suspend fun fromAniList(ids: List<Long>): Map<Long, Found> {
        val payload = buildJsonObject {
            put("query", ANILIST_QUERY)
            putJsonObject("variables") { put("ids", kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
        }
        val root = network.client.newCall(POST(ANILIST, body = payload.toString().toRequestBody(jsonMime)))
            .awaitSuccess()
            .parseAs<JsonObject>()
        return root["data"]?.jsonObject?.get("Page")?.jsonObject?.get("media")?.jsonArray.orEmpty()
            .mapNotNull { media ->
                val obj = media.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
                val names = obj["title"]?.jsonObject
                val titles = listOfNotNull("romaji", "english", "native").mapNotNull { names?.get(it)?.jsonPrimitive?.contentOrNull } +
                    obj["synonyms"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                val tags = obj["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } +
                    obj["tags"]?.jsonArray.orEmpty().mapNotNull { tag ->
                        val t = tag.jsonObject
                        t["name"]?.jsonPrimitive?.contentOrNull?.takeIf { (t["rank"]?.jsonPrimitive?.intOrNull ?: 0) >= TAG_RANK }
                    }
                id to Found(tags, titles, statusOf(obj["status"]?.jsonPrimitive?.contentOrNull))
            }.toMap()
    }

    private suspend fun fromMyAnimeList(track: Track): Found {
        val obj = network.client.newCall(GET("$MAL/manga/${track.media_id}?fields=genres,alternative_titles,title,status", malHeaders))
            .awaitSuccess()
            .parseAs<JsonObject>()
        val alt = obj["alternative_titles"]?.jsonObject
        val titles = listOfNotNull(obj["title"]?.jsonPrimitive?.contentOrNull, alt?.get("en")?.jsonPrimitive?.contentOrNull, alt?.get("ja")?.jsonPrimitive?.contentOrNull) +
            alt?.get("synonyms")?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val tags = obj["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        return Found(tags, titles, statusOf(obj["status"]?.jsonPrimitive?.contentOrNull))
    }

    private suspend fun fromKitsu(track: Track): Found {
        val root = network.client.newCall(GET("$KITSU/manga/${track.media_id}?include=categories", kitsuHeaders))
            .awaitSuccess()
            .parseAs<JsonObject>()
        val attributes = root["data"]?.jsonObject?.get("attributes")?.jsonObject
        val titles = attributes?.get("titles")?.jsonObject?.values.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } +
            attributes?.get("abbreviatedTitles")?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } +
            listOfNotNull(attributes?.get("canonicalTitle")?.jsonPrimitive?.contentOrNull)
        val tags = root["included"]?.jsonArray.orEmpty()
            .filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "categories" }
            .mapNotNull { it.jsonObject["attributes"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull }
        return Found(tags, titles, statusOf(attributes?.get("status")?.jsonPrimitive?.contentOrNull))
    }

    private suspend fun fromMangaUpdates(track: Track): Found {
        val obj = network.client.newCall(GET("$MU/v1/series/${track.media_id}")).awaitSuccess().parseAs<JsonObject>()
        val titles = listOfNotNull(obj["title"]?.jsonPrimitive?.contentOrNull) +
            obj["associated"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.contentOrNull }
        val tags = obj["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["genre"]?.jsonPrimitive?.contentOrNull } +
            obj["categories"]?.jsonArray.orEmpty()
                .sortedByDescending { it.jsonObject["votes"]?.jsonPrimitive?.intOrNull ?: 0 }
                .take(MU_CATEGORIES)
                .mapNotNull { it.jsonObject["category"]?.jsonPrimitive?.contentOrNull }
        // MangaUpdates' status is prose ("Ongoing", "Complete (Hiatus)"): the strongest word wins.
        val prose = obj["status"]?.jsonPrimitive?.contentOrNull?.lowercase().orEmpty()
        val status = when {
            obj["completed"]?.jsonPrimitive?.contentOrNull == "true" || "complete" in prose -> SManga.COMPLETED
            "hiatus" in prose -> SManga.ON_HIATUS
            "cancel" in prose || "discontinued" in prose || "dropped" in prose -> SManga.CANCELLED
            "ongoing" in prose -> SManga.ONGOING
            else -> null
        }
        return Found(tags, titles, status)
    }

    private val malHeaders = Headers.headersOf("X-MAL-CLIENT-ID", MyAnimeListApi.CLIENT_ID)
    private val kitsuHeaders = Headers.headersOf("Accept", "application/vnd.api+json")

    companion object {
        private const val ANILIST = "https://graphql.anilist.co/"
        private const val MAL = "https://api.myanimelist.net/v2"
        private const val KITSU = "https://kitsu.io/api/edge"
        private const val MU = "https://api.mangaupdates.com"

        private val KNOWN = setOf(TrackManager.ANILIST, TrackManager.MYANIMELIST, TrackManager.KITSU, TrackManager.MANGA_UPDATES)

        /** AniList tags below this rank are noise. */
        private const val TAG_RANK = 60

        /** MangaUpdates lists dozens of reader-voted categories; the top few are the real ones. */
        private const val MU_CATEGORIES = 8

        private const val REQUEST_TIMEOUT_MS = 20_000L

        private val ANILIST_QUERY = """
            |query (${'$'}ids: [Int]) {
              |Page(perPage: 50) {
                |media(id_in: ${'$'}ids, type: MANGA) {
                  |id
                  |title { romaji english native }
                  |synonyms
                  |genres
                  |tags { name rank }
                  |status
                |}
              |}
            |}
        """.trimMargin()

        /** Least to most final. */
        private val STATUS_RANK = listOf(SManga.ONGOING, SManga.ON_HIATUS, SManga.CANCELLED, SManga.COMPLETED)

        /** How long a read stays fresh before status is asked about again. */
        private const val REFRESH_MS = 30L * 24 * 60 * 60 * 1000
    }
}
