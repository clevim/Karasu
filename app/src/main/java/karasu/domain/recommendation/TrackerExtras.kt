package karasu.domain.recommendation

import co.touchlab.kermit.Logger
import karasu.data.DatabaseHandler
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    /**
     * The series' ids elsewhere, by [ExternalIds] name: what lets a recommendation ask AniList
     * about *this* series rather than whatever a title search lands on, and tell a suggestion the
     * reader already has from a new one however differently it is spelled. From MangaBaka, which
     * maps every service to every other; the tracks the reader bound are folded in too.
     */
    val ids: Map<String, String> = emptyMap(),
    /** What MangaBaka said, kept apart so a tracker refresh does not erase it. See [IdentifyWithMangaBaka]. */
    val mbTags: List<String> = emptyList(),
    val mbTitles: List<String> = emptyList(),
    val mbStatus: Int? = null,
    /** How many chapters MangaBaka says the series has, when it says. See [chaptersBehind]. */
    val mbChapters: Int? = null,
    /** When MangaBaka was last asked, found or not; 0 = never. */
    val mangaBakaAt: Long = 0,
    /** What MangaDex and MangaUpdates said, see [CheckPublication]. Names English-first. */
    val catalogTags: List<String> = emptyList(),
    val catalogTitles: List<String> = emptyList(),
    val catalogStatus: Int? = null,
    /** When they were last asked, found or not; 0 = never. */
    val catalogAt: Long = 0,
) {
    /** Every tag known about the series, catalogues and trackers first. */
    val allTags: List<String> get() = (catalogTags + tags + mbTags).distinctBy { it.lowercase() }

    /** Every other name it goes by, the catalogues' English and romanised names first. */
    val allTitles: List<String> get() = (catalogTitles + titles + mbTitles).distinctBy { it.lowercase() }

    /**
     * The status the library shows: the catalogues', which know hiatus and cancelled, else the
     * trackers'. MangaBaka's is left to the calendar and the stats, as before.
     */
    val publicationStatus: Int? get() = catalogStatus ?: status

    /** The best word on its status there is, MangaBaka's last. */
    val anyStatus: Int? get() = publicationStatus ?: mbStatus
}

/**
 * In the `manga_extras` table (migration 39), one row per entry, and in the backup with the entry
 * it belongs to ([eu.kanade.tachiyomi.data.backup.models.BackupManga.extras]).
 *
 * Held in memory once read: the profile, the calendar and the aliases all ask per entry, in loops
 * over the whole library.
 */
class TrackerExtrasStore(
    private val handler: DatabaseHandler,
    private val preferences: PreferencesHelper,
) {
    @Volatile
    private var cached: Map<Long, TrackerExtra>? = null

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun all(): Map<Long, TrackerExtra> = cached ?: load().also { cached = it }

    suspend fun get(mangaId: Long?): TrackerExtra? = mangaId?.let { all()[it] }

    suspend fun putAll(extras: Map<Long, TrackerExtra>) {
        if (extras.isEmpty()) return
        runCatching {
            handler.await(inTransaction = true) {
                extras.forEach { (id, extra) -> manga_extrasQueries.upsert(id, json.encodeToString(extra)) }
            }
        }.onFailure { Logger.e(it) { "Could not store library extras" } }
        cached = all() + extras
    }

    private val writing = Mutex()

    /**
     * Rewrites one entry from what is stored at this moment, under a lock: the trackers' run, the
     * catalogues' and MangaBaka's each own some of the fields, and a copy read before a minute of
     * network calls would put back what another one wrote meanwhile.
     *
     * @return what was stored before, and what is stored now.
     */
    suspend fun update(mangaId: Long, change: (TrackerExtra) -> TrackerExtra): Pair<TrackerExtra?, TrackerExtra> = writing.withLock {
        val before = get(mangaId)
        val after = change(before ?: TrackerExtra(key = "", tags = emptyList(), titles = emptyList()))
        putAll(mapOf(mangaId to after))
        before to after
    }

    fun encode(extra: TrackerExtra): String = json.encodeToString(extra)

    fun decode(raw: String): TrackerExtra? = runCatching { json.decodeFromString<TrackerExtra>(raw) }.getOrNull()

    private suspend fun load(): Map<Long, TrackerExtra> {
        val rows = runCatching {
            handler.awaitList { manga_extrasQueries.findAll { id, data -> id to data } }
        }.getOrDefault(emptyList())
        val stored = rows.mapNotNull { (id, data) -> decode(data)?.let { id to it } }.toMap()
        // Once, on the first start after the move: what the old preference held comes across,
        // and the preference is emptied so it is not resent with every backup.
        val legacy = preferences.recommendationTrackerExtras().get()
        if (legacy.length <= 2) return stored
        val old = runCatching { json.decodeFromString<Map<Long, TrackerExtra>>(legacy) }.getOrDefault(emptyMap())
            .filterKeys { it !in stored }
        if (old.isNotEmpty()) {
            runCatching {
                handler.await(inTransaction = true) {
                    // Entries deleted since have no row to point at; the foreign key turns them away.
                    old.forEach { (id, extra) -> runCatching { manga_extrasQueries.upsert(id, json.encodeToString(extra)) } }
                }
            }
        }
        preferences.recommendationTrackerExtras().set("{}")
        return stored + old
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
            // Only the trackers' fields change: MangaBaka's and the catalogues' half of the record
            // is whatever is stored now. Stored before the status is applied, so a run stopped
            // halfway leaves no status this put there that the store does not know as its own.
            val (before, extra) = store.update(manga.id!!) {
                it.copy(
                    key = key,
                    tags = found.flatMap { it.tags }.mapNotNull(::normalizeTag).distinctBy { it.lowercase() },
                    titles = found.flatMap { it.titles }.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() },
                    // Finished beats hiatus beats releasing: the most final word any tracker gave.
                    status = found.mapNotNull { it.status }.maxByOrNull { STATUS_RANK.indexOf(it) },
                    fetchedAt = now,
                )
            }
            if (extra.tags.isNotEmpty()) runCatching { addToGenres(manga, extra.tags) }
            runCatching { applyTrackerStatus(customMangaManager, manga, before?.publicationStatus, extra.publicationStatus) }
        }
    }

    private suspend fun addToGenres(manga: Manga, tags: List<String>) = addToGenres(customMangaManager, manga, tags)

    private data class Found(val tags: List<String>, val titles: List<String>, val status: Int? = null)

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
                id to Found(tags, titles, trackerStatusOf(obj["status"]?.jsonPrimitive?.contentOrNull))
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
        return Found(tags, titles, trackerStatusOf(obj["status"]?.jsonPrimitive?.contentOrNull))
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
        return Found(tags, titles, trackerStatusOf(attributes?.get("status")?.jsonPrimitive?.contentOrNull))
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
        // Not `completed`: that flag means the translation is complete, not the series.
        return Found(tags, titles, mangaUpdatesStatusOf(obj["status"]?.jsonPrimitive?.contentOrNull))
    }

    private val malHeaders = Headers.headersOf("X-MAL-CLIENT-ID", MyAnimeListApi.CLIENT_ID)
    private val kitsuHeaders = Headers.headersOf("Accept", "application/vnd.api+json")

    companion object {
        private const val ANILIST = "https://graphql.anilist.co/"
        private const val MAL = "https://api.myanimelist.net/v2"
        private const val KITSU = "https://kitsu.io/api/edge"
        private const val MU = "https://api.mangaupdates.com"

        val KNOWN = setOf(TrackManager.ANILIST, TrackManager.MYANIMELIST, TrackManager.KITSU, TrackManager.MANGA_UPDATES)

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

        /** How long a read stays fresh before status is asked about again. */
        private const val REFRESH_MS = 30L * 24 * 60 * 60 * 1000
    }
}

/** Least to most final. */
private val STATUS_RANK = listOf(SManga.ONGOING, SManga.ON_HIATUS, SManga.CANCELLED, SManga.COMPLETED)

/**
 * A tracker's status word as the app's status number, or null for anything it does not know.
 * Takes both the APIs' words and what the search results show (MyAnimeList's with spaces).
 */
internal fun trackerStatusOf(word: String?): Int? = when (word?.lowercase()?.replace(' ', '_')) {
    "releasing", "currently_publishing", "current", "ongoing", "publishing" -> SManga.ONGOING
    "hiatus", "on_hiatus" -> SManga.ON_HIATUS
    "finished", "complete", "completed" -> SManga.COMPLETED
    "cancelled", "canceled", "discontinued" -> SManga.CANCELLED
    else -> null
}

/**
 * The status the library should show when the trackers said [tracker] and the source [source],
 * or null to leave the source's own. The trackers win: people keep them current, where a source
 * rarely touches its status after the first scrape and almost never says hiatus or cancelled.
 * Except where the source says more: "publishing finished" (the original is done, the
 * translation is not) and "licensed" are both truer than a tracker's plain "finished".
 */
internal fun libraryStatus(source: Int, tracker: Int?): Int? = when {
    tracker == null || tracker == source -> null
    tracker == SManga.COMPLETED && (source == SManga.PUBLISHING_FINISHED || source == SManga.LICENSED) -> null
    else -> tracker
}

/**
 * Shows the trackers' status in the library, as a custom edit so a refresh from the source does
 * not undo it. [previous] is what the trackers said last time, which is how a status this put
 * there is told apart from one the reader set by hand: theirs is never touched.
 */
internal suspend fun applyTrackerStatus(customMangaManager: CustomMangaManager, manga: Manga, previous: Int?, tracker: Int?) {
    val current = customMangaManager.getManga(manga)
    val custom = current?.status?.takeIf { it != -1 }
    if (custom != null && custom != previous) return
    val wanted = libraryStatus(manga.ogStatus, tracker)
    if (wanted == custom) return
    customMangaManager.saveMangaInfo(
        CustomMangaInfo(
            mangaId = manga.id!!,
            title = current?.title?.takeIf { it.isNotBlank() },
            author = current?.author,
            artist = current?.artist,
            description = current?.description,
            genre = current?.genre,
            status = wanted,
        ),
    )
}

/**
 * On binding a tracker, the status its search result showed goes to the library right away
 * rather than after the next overnight [EnrichFromTrackers] run, which then takes over. Kept in
 * [TrackerExtrasStore] like that run's, so the run knows it as its own. The key is left alone,
 * so the run still fetches the entry in full.
 */
suspend fun applyBoundTrackerStatus(
    store: TrackerExtrasStore,
    customMangaManager: CustomMangaManager,
    manga: Manga,
    syncId: Long,
    publishingStatus: String,
) {
    if (syncId !in EnrichFromTrackers.KNOWN) return
    val found = trackerStatusOf(publishingStatus) ?: return
    val (before, after) = store.update(manga.id!!) { it.copy(status = listOfNotNull(it.status, found).maxBy { s -> STATUS_RANK.indexOf(s) }) }
    applyTrackerStatus(customMangaManager, manga, before?.publicationStatus, after.publicationStatus)
}

/**
 * The tags become part of the entry's genre list, as a custom edit so a refresh from the source
 * does not wipe them. An edit the reader already made is extended, never replaced.
 */
internal suspend fun addToGenres(customMangaManager: CustomMangaManager, manga: Manga, tags: List<String>) {
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

/**
 * How many chapters the reader's sources are behind the series, or null when it is not enough to
 * mention: [official] is the count MangaBaka reports, [have] the highest chapter number the entry
 * has from all its sources. Only a real gap counts — a source ahead of the catalogue (it often
 * is, catalogues lag) is not a problem.
 */
fun chaptersBehind(official: Int?, have: Float): Int? {
    if (official == null || official <= 0) return null
    val gap = official - have.toInt()
    return gap.takeIf { it >= BEHIND_MIN }
}

/** Fewer missing than this is a catalogue counting extras or a source a week late. */
private const val BEHIND_MIN = 5
