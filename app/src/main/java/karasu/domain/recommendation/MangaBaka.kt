package karasu.domain.recommendation

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.SManga
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.track.interactor.GetTrack
import karasu.util.normalizedLevenshteinSimilarity
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.util.Locale

/** The names [TrackerExtra.ids] files a series' ids under. */
object ExternalIds {
    const val MANGABAKA = "mangabaka"
    const val ANILIST = "anilist"
    const val MAL = "mal"
    const val MANGA_UPDATES = "mu"
    const val KITSU = "kitsu"
}

/**
 * Works out what every library entry *is*, with MangaBaka, and files what it learns next to what
 * the trackers said ([TrackerExtra]): its ids on AniList, MyAnimeList, MangaUpdates and Kitsu,
 * every name it goes by — the Portuguese release names included — its genres, its defining tags
 * and its status.
 *
 * Until now that only happened for entries the reader had bound to a tracker by hand. MangaBaka
 * merges six catalogues and answers without a login, so an untracked entry gets the same: the
 * profile learns its tags, migration and merged sources search its other names, the calendar
 * knows when it finished — and recommendations can ask AniList about the series by id.
 *
 * Bound tracks are looked up by id, which is exact. Everything else is a title search, accepted
 * only on a near-identical name, or a close one the author confirms: a wrong match teaches the
 * profile somebody else's series, which is worse than learning nothing.
 *
 * ponytail: one search per entry against a limit of 30 a minute, so a big library is identified
 * over a few runs ([BUDGET_MS] each), most recently read first. Data is CC BY-NC-SA, credited in
 * the settings.
 */
class IdentifyWithMangaBaka(
    private val getLibraryManga: GetLibraryManga,
    private val getTrack: GetTrack,
    private val network: NetworkHelper,
    private val store: TrackerExtrasStore,
    private val customMangaManager: CustomMangaManager,
) {
    /**
     * @param onlyNew only entries never looked up — what a library update does after its run, so
     *   something added today is known by tomorrow rather than at the next recommendations build.
     */
    suspend fun await(
        now: Long = System.currentTimeMillis(),
        onlyNew: Boolean = false,
        budgetMs: Long = BUDGET_MS,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val deadline = now + budgetMs
        val library = getLibraryManga.await().distinctBy { it.manga.id }.sortedByDescending { it.lastRead }
        val tracks = getTrack.awaitAll().groupBy { it.manga_id }
        val pending = library.map { it.manga }.filter { manga ->
            val have = store.get(manga.id) ?: return@filter true
            if (onlyNew) return@filter have.mangaBakaAt == 0L
            val found = ExternalIds.MANGABAKA in have.ids
            have.mangaBakaAt < now - if (found) REFRESH_FOUND_MS else REFRESH_MISSED_MS
        }
        if (pending.isEmpty()) return
        onProgress(0, pending.size)

        val collected = mutableMapOf<Long, TrackerExtra>()
        for ((index, manga) in pending.withIndex()) {
            if (System.currentTimeMillis() > deadline) break
            val its = tracks[manga.id].orEmpty()
            val known = store.get(manga.id)
            val record = runCatching { find(manga, its.associate { it.sync_id to it.media_id }, known?.allTitles.orEmpty()) }
                .onFailure { Logger.w(it) { "MangaBaka lookup failed for ${manga.title}" } }
                // A failed request is not a miss: leave it to be asked again next run.
                .getOrElse { continue }
            val base = known ?: TrackerExtra(key = "", tags = emptyList(), titles = emptyList())
            val extra = if (record == null) {
                base.copy(mangaBakaAt = now)
            } else {
                base.copy(
                    ids = base.ids + record.ids,
                    mbTags = record.tags,
                    mbTitles = record.titles,
                    mbStatus = record.status,
                    mbChapters = record.chapters,
                    mangaBakaAt = now,
                )
            }
            collected[manga.id!!] = extra
            // The genres join the entry's own, as the trackers' do; the long tail of tags stays
            // with the profile, where it informs without cluttering the entry's page.
            if (record != null && record.genres.isNotEmpty()) runCatching { addToGenres(customMangaManager, manga, record.genres) }
            onProgress(index + 1, pending.size)
            delay(SEARCH_SPACING_MS)
        }
        store.putAll(collected)
    }

    /** The entry's MangaBaka record, by a bound track's id when there is one, else by title. */
    private suspend fun find(manga: Manga, trackIds: Map<Long, Long>, aliases: List<String>): MangaBakaRecord? {
        val byId = listOfNotNull(
            trackIds[TrackManager.ANILIST]?.let { "al:$it" },
            trackIds[TrackManager.MYANIMELIST]?.let { "mal:$it" },
            trackIds[TrackManager.MANGA_UPDATES]?.let { "mu:${it.toString(36)}" },
            trackIds[TrackManager.KITSU]?.let { "kt:$it" },
        ).firstOrNull()
        if (byId != null) {
            search(byId, limit = 1).firstOrNull()?.let { return it }
        }
        val candidates = search(manga.title, limit = SEARCH_LIMIT)
        return bestMatch(listOf(manga.title) + aliases, manga.author, candidates)
    }

    private suspend fun search(query: String, limit: Int): List<MangaBakaRecord> {
        val url = "$API/v1/series/search?limit=$limit&q=" + URLEncoder.encode(query.take(MAX_QUERY), "UTF-8")
        val root = try {
            network.client.newCall(GET(url)).awaitSuccess().parseAs<JsonObject>()
        } catch (e: HttpException) {
            if (e.code != HTTP_TOO_MANY_REQUESTS) throw e
            delay(RATE_LIMIT_WAIT_MS)
            network.client.newCall(GET(url)).awaitSuccess().parseAs<JsonObject>()
        }
        return root["data"]?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseMangaBakaRecord) }
    }

    companion object {
        private const val API = "https://api.mangabaka.org"
        private const val SEARCH_LIMIT = 5
        private const val MAX_QUERY = 120
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /** 30 searches a minute is the limit; this stays under it. */
        private const val SEARCH_SPACING_MS = 2_100L
        private const val RATE_LIMIT_WAIT_MS = 30_000L

        /** One run's share; the rest is picked up next time. */
        private const val BUDGET_MS = 10L * 60 * 1000

        /** What a library update spends on what was added since: about fifty entries. */
        const val NEW_ENTRIES_BUDGET_MS = 2L * 60 * 1000

        /** Status moves, so a found entry is asked again after a while. */
        private const val REFRESH_FOUND_MS = 60L * 24 * 60 * 60 * 1000

        /** A miss is retried sooner: MangaBaka adds series every week. */
        private const val REFRESH_MISSED_MS = 21L * 24 * 60 * 60 * 1000
    }
}

/** What is kept of one MangaBaka series. */
data class MangaBakaRecord(
    val ids: Map<String, String>,
    /** Every name, the primary title first. */
    val titles: List<String>,
    val authors: List<String>,
    /** Its genres, cased for showing. */
    val genres: List<String>,
    /** Genres, then the tags MangaBaka weighs as defining or core, without spoilers. */
    val tags: List<String>,
    val status: Int?,
    val type: String?,
    /** MangaBaka's chapter count, as a number; it sends a string, sometimes "12.5" or blank. */
    val chapters: Int? = null,
)

internal fun parseMangaBakaRecord(obj: JsonObject): MangaBakaRecord? {
    val id = obj["id"].text() ?: return null
    if (obj["type"].text() == "novel") return null
    val source = obj["source"] as? JsonObject
    fun sourceId(key: String) = (source?.get(key) as? JsonObject)?.get("id").text()
    val ids = buildMap {
        put(ExternalIds.MANGABAKA, id)
        sourceId("anilist")?.let { put(ExternalIds.ANILIST, it) }
        sourceId("my_anime_list")?.let { put(ExternalIds.MAL, it) }
        sourceId("manga_updates")?.let { put(ExternalIds.MANGA_UPDATES, it) }
        sourceId("kitsu")?.let { put(ExternalIds.KITSU, it) }
    }
    val titles = (
        listOfNotNull(obj["title"].text(), obj["romanized_title"].text(), obj["native_title"].text()) +
            obj["titles"].array().mapNotNull { (it as? JsonObject)?.get("title").text() } +
            (obj["secondary_titles"] as? JsonObject)?.values.orEmpty()
                .flatMap { it.array() }
                .mapNotNull { (it as? JsonObject)?.get("title").text() }
        )
        .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        // Latin-script names are the ones a source in the reader's languages is searched by; the
        // other scripts would only fill the backup. The native title is kept for what it is.
        .filter { it == obj["native_title"].text() || it.isMostlyLatin() }
        .take(MAX_TITLES)
    val genres = obj["genres"].array().mapNotNull { it.text() }.map(::genreName)
    val tags = obj["tags_v2"].array().mapNotNull { it as? JsonObject }
        .filter { it["weight"].text() in STRONG_TAGS && (it["is_spoiler"] as? JsonPrimitive)?.booleanOrNull != true }
        .mapNotNull { it["name"].text() }
        .take(MAX_TAGS)
    val status = when (obj["status"].text()) {
        "releasing", "upcoming" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> null
    }
    return MangaBakaRecord(
        ids = ids,
        titles = titles,
        authors = (obj["authors"].array() + obj["artists"].array()).mapNotNull { it.text() }.distinct(),
        genres = genres,
        tags = (genres + tags).mapNotNull(::normalizeTag).distinctBy { it.lowercase() },
        status = status,
        type = obj["type"].text(),
        chapters = obj["total_chapters"].text()?.toFloatOrNull()?.toInt(),
    )
}

/**
 * The candidate that is the same series as an entry called [names], or null when none is close
 * enough to bet the profile on.
 *
 * Near-identical names are accepted outright. A close one needs the author to agree: "Kingdom"
 * and "Kingdom Hearts" are close, and only the author tells them apart.
 */
internal fun bestMatch(names: List<String>, author: String?, candidates: List<MangaBakaRecord>): MangaBakaRecord? {
    val keys = names.map { it.matchKey() }.filter { it.isNotEmpty() }
    if (keys.isEmpty()) return null
    val people = author.orEmpty().nameTokens()
    return candidates
        .map { candidate ->
            val similarity = candidate.titles.maxOfOrNull { title ->
                val key = title.matchKey()
                if (key.isEmpty()) 0.0 else keys.maxOf { normalizedLevenshteinSimilarity(it, key) }
            } ?: 0.0
            val sameAuthor = people.isNotEmpty() && candidate.authors.any { it.nameTokens().intersect(people).isNotEmpty() }
            Triple(candidate, similarity, sameAuthor)
        }
        .filter { (_, similarity, sameAuthor) -> similarity >= SURE_MATCH || (sameAuthor && similarity >= AUTHOR_MATCH) }
        .maxByOrNull { (_, similarity, sameAuthor) -> similarity + if (sameAuthor) AUTHOR_BONUS else 0.0 }
        ?.first
}

private fun String.isMostlyLatin(): Boolean {
    val letters = filter { it.isLetter() }
    return letters.isNotEmpty() && letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } * 2 > letters.length
}

private fun String.matchKey() = lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

/** Name parts long enough to mean something, in any order: "MIURA Kentaro" is "Kentaro Miura". */
private fun String.nameTokens(): Set<String> = lowercase(Locale.ROOT)
    .split(Regex("[^\\p{L}\\p{N}]+"))
    .filter { it.length >= 3 }
    .toSet()

/** "slice_of_life" → "Slice Of Life", the way the rest of the library spells a genre. */
private fun genreName(raw: String): String = raw.split('_', ' ')
    .filter { it.isNotEmpty() }
    .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.ROOT) } }

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
private fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()

private val STRONG_TAGS = setOf("defining", "core")
private const val MAX_TAGS = 15
private const val MAX_TITLES = 25
private const val SURE_MATCH = 0.92
private const val AUTHOR_MATCH = 0.8
private const val AUTHOR_BONUS = 0.05

/**
 * Other names of the series a search for [query] is after, from MangaBaka, for a global search
 * whose query found nothing on a source: the reader types the name they know, the source lists
 * it under another. Only when one result's name is close to the query — otherwise the search
 * just found something else, and its names would send every source after the wrong series.
 * Empty on any failure: this only ever adds tries.
 */
suspend fun mangaBakaNames(network: NetworkHelper, query: String): List<String> {
    val q = query.trim()
    if (q.length < MIN_NAME_QUERY) return emptyList()
    namesCache[q.lowercase()]?.let { return it }
    val names = runCatching {
        val url = "https://api.mangabaka.org/v1/series/search?limit=3&q=" + URLEncoder.encode(q.take(120), "UTF-8")
        val records = network.client.newCall(GET(url)).awaitSuccess().parseAs<JsonObject>()["data"]
            ?.jsonArray.orEmpty().mapNotNull { (it as? JsonObject)?.let(::parseMangaBakaRecord) }
        val key = q.matchKey()
        val best = records.maxByOrNull { r -> r.titles.maxOfOrNull { normalizedLevenshteinSimilarity(it.matchKey(), key) } ?: 0.0 }
            ?.takeIf { r -> (r.titles.maxOfOrNull { normalizedLevenshteinSimilarity(it.matchKey(), key) } ?: 0.0) >= NAME_QUERY_MATCH }
        best?.titles.orEmpty()
            .filter { it.isMostlyLatin() && !it.equals(q, ignoreCase = true) }
            .take(MAX_NAME_FALLBACKS)
    }.getOrDefault(emptyList())
    // ponytail: unbounded per process; a session's searches are a few dozen strings.
    namesCache[q.lowercase()] = names
    return names
}

private val namesCache = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
private const val MIN_NAME_QUERY = 3
private const val NAME_QUERY_MATCH = 0.75
private const val MAX_NAME_FALLBACKS = 4
