package karasu.domain.releases

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import karasu.domain.recommendation.CatalogRecord
import karasu.domain.recommendation.ExternalIds
import karasu.domain.recommendation.array
import karasu.domain.recommendation.int
import karasu.domain.recommendation.text
import karasu.domain.recommendation.matchCatalog
import karasu.domain.recommendation.parseMangaDexManga
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Where the "Releases" window's list comes from: AniList, the hundred most popular series that
 * started in the last six months, in two requests; MangaDex when AniList cannot be reached. Kept
 * on disk and fetched again only when a week old or when the reader asks — opening the window
 * costs nothing in between, and the list itself makes no request per entry.
 *
 * ponytail: no background job. A week-old list fetched on open is fresh enough for a list of
 * what started months ago, and nothing runs for a reader who never opens the window.
 */
class ReleasesRepository(
    context: Context,
    private val network: NetworkHelper,
) {
    private val file = File(context.filesDir, "releases.json")

    @Volatile
    private var cached: ReleasesSnapshot? = null

    private val details = ConcurrentHashMap<String, Pair<Long, ReleaseDetail>>()

    fun read(): ReleasesSnapshot? = cached ?: runCatching { Json.decodeFromString<ReleasesSnapshot>(file.readText()) }
        .getOrNull()
        ?.also { cached = it }

    /**
     * The list, fetched again when [force]d or a week old. A failed fetch falls back to whatever
     * is on disk, however old; it only throws when there is nothing at all to show.
     */
    suspend fun snapshot(force: Boolean = false, now: Long = System.currentTimeMillis()): ReleasesSnapshot {
        val have = read()
        if (!force && have != null && now - have.fetchedAt < REFRESH_MS) return have
        val fetched = fetch(now)
        if (fetched == null) return have ?: throw Exception("Releases unavailable")
        synchronized(this) {
            file.writeText(Json.encodeToString(fetched))
            cached = fetched
        }
        return fetched
    }

    private suspend fun fetch(now: Long): ReleasesSnapshot? =
        fetchWithFallback(now, { fromAniList(monthsAgo(now, RELEASE_WINDOW_MONTHS)) }, { fromMangaDex(now) })

    private suspend fun fromAniList(since: Int): List<Release> = (1..ANILIST_PAGES).flatMap { page ->
        val root = aniList(LIST_QUERY) {
            put("page", page)
            put("since", since)
        }
        parseAniListReleases(root, since)
    }.distinctBy { it.id }

    private suspend fun fromMangaDex(now: Long): List<Release> {
        val since = SimpleDateFormat("yyyy-MM-dd'T'00:00:00", Locale.US).format(
            java.util.Calendar.getInstance().apply {
                timeInMillis = now
                add(java.util.Calendar.MONTH, -RELEASE_WINDOW_MONTHS)
            }.time,
        )
        val url = "$MANGADEX/manga?limit=100&createdAtSince=$since&order%5BfollowedCount%5D=desc" +
            "&includes%5B%5D=cover_art&contentRating%5B%5D=safe&contentRating%5B%5D=suggestive" +
            LANGUAGES.joinToString("") { "&originalLanguage%5B%5D=$it" }
        val items = withTimeout(TIMEOUT_MS) {
            network.client.newCall(GET(url, userAgent)).awaitSuccess().parseAs<JsonObject>()
        }.let(::parseMangaDexReleases)
        // The list does not carry follower counts (another request per entry would), but it is
        // sorted by them: its rank stands in for popularity.
        return items.mapIndexed { index, item -> item.copy(popularity = items.size - index) }
    }

    /** One series in full, asked when its page opens and remembered for a day. */
    suspend fun detail(release: Release, now: Long = System.currentTimeMillis()): ReleaseDetail {
        val key = "${release.origin}:${release.id}"
        details[key]?.takeIf { now - it.first < DETAIL_CACHE_MS }?.let { return it.second }
        val detail = when (release.origin) {
            ORIGIN_ANILIST -> parseAniListDetail(release, (aniList(DETAIL_QUERY) { put("id", release.id.toInt()) }["data"] as? JsonObject)?.get("Media") as? JsonObject)
            else -> parseMangaDexDetail(
                release,
                withTimeout(TIMEOUT_MS) {
                    network.client.newCall(GET("$MANGADEX/manga/${release.id}?includes%5B%5D=author&includes%5B%5D=artist", userAgent))
                        .awaitSuccess().parseAs<JsonObject>()
                }["data"] as? JsonObject,
            )
        }
        details[key] = now to detail
        return detail
    }

    /**
     * The Portuguese names MangaDex has for a series, for searching the reader's sources: a pt-BR
     * scanlation is listed under those. Found by AniList id when MangaDex links one, else by an
     * exact name; nothing on any doubt or failure. One request, made only when a search is asked.
     */
    suspend fun portugueseNames(release: Release): List<String> = runCatching {
        if (release.origin == ORIGIN_MANGADEX) {
            val root = network.client.newCall(GET("$MANGADEX/manga/${release.id}", userAgent)).awaitSuccess().parseAs<JsonObject>()
            return@runCatching portugueseOf(root["data"] as? JsonObject)
        }
        val url = "$MANGADEX/manga?limit=10&title=" + URLEncoder.encode(release.title.take(120), "UTF-8")
        val data = withTimeout(TIMEOUT_MS) {
            network.client.newCall(GET(url, userAgent)).awaitSuccess().parseAs<JsonObject>()
        }["data"].array().mapNotNull { it as? JsonObject }
        val records: List<Pair<JsonObject, CatalogRecord>> = data.mapNotNull { obj -> parseMangaDexManga(obj)?.let { obj to it } }
        val match = matchCatalog(records.map { it.second }, mapOf(ExternalIds.ANILIST to release.id), release.names)
        portugueseOf(records.firstOrNull { it.second == match }?.first)
    }.getOrDefault(emptyList())

    private suspend fun aniList(query: String, variables: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject {
        val payload = buildJsonObject {
            put("query", query)
            putJsonObject("variables", variables)
        }
        return withTimeout(TIMEOUT_MS) {
            network.client.newCall(POST(ANILIST, userAgent, payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
                .parseAs<JsonObject>()
        }
    }

    private val userAgent = Headers.headersOf("User-Agent", "clevim/Karasu/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")

    companion object {
        private const val ANILIST = "https://graphql.anilist.co/"
        private const val MANGADEX = "https://api.mangadex.org"
        private const val ANILIST_PAGES = 2
        private const val TIMEOUT_MS = 20_000L
        private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
        private const val DETAIL_CACHE_MS = 24L * 60 * 60 * 1000
        private val LANGUAGES = listOf("ja", "ko", "zh", "zh-hk")

        private val LIST_QUERY = """
            |query (${'$'}page: Int, ${'$'}since: FuzzyDateInt) {
              |Page(page: ${'$'}page, perPage: 50) {
                |media(type: MANGA, startDate_greater: ${'$'}since, isAdult: false, sort: POPULARITY_DESC, format_not_in: [NOVEL]) {
                  |id siteUrl title { romaji english native } synonyms coverImage { extraLarge large }
                  |countryOfOrigin status startDate { year month day } genres popularity averageScore
                |}
              |}
            |}
        """.trimMargin()

        private val DETAIL_QUERY = """
            |query (${'$'}id: Int) {
              |Media(id: ${'$'}id, type: MANGA) {
                |id siteUrl title { romaji english native } synonyms coverImage { extraLarge large } bannerImage
                |description(asHtml: false) status countryOfOrigin startDate { year month day } chapters volumes
                |genres tags { name rank isMediaSpoiler } averageScore popularity
                |staff(perPage: 10) { edges { role node { name { full } } } }
                |relations { edges { relationType node { id type title { romaji english } } } }
                |externalLinks { site url }
              |}
            |}
        """.trimMargin()
    }
}

/**
 * AniList's list, else MangaDex's, else null. An error, a timeout or an empty answer all count as
 * "not reachable": a list of nothing is never what was asked for.
 */
internal suspend fun fetchWithFallback(
    now: Long,
    aniList: suspend () -> List<Release>,
    mangaDex: suspend () -> List<Release>,
): ReleasesSnapshot? {
    runCatching { aniList() }
        .onFailure { Logger.w(it) { "AniList releases failed; trying MangaDex" } }
        .getOrNull()?.takeIf { it.isNotEmpty() }
        ?.let { return ReleasesSnapshot(ORIGIN_ANILIST, now, it) }
    return runCatching { mangaDex() }
        .onFailure { Logger.w(it) { "MangaDex releases failed too" } }
        .getOrNull()?.takeIf { it.isNotEmpty() }
        ?.let { ReleasesSnapshot(ORIGIN_MANGADEX, now, it) }
}

/** What a series' page shows besides what the list already had. */
data class ReleaseDetail(
    val release: Release,
    val banner: String? = null,
    val description: String? = null,
    val chapters: Int? = null,
    val volumes: Int? = null,
    /** Name and rank (0–100), spoilers left out. */
    val tags: List<Pair<String, Int>> = emptyList(),
    val authors: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    /** Relation ("SEQUEL", "PREQUEL", "SPIN_OFF"…) and the related series' name. */
    val relations: List<Pair<String, String>> = emptyList(),
    /** Site name and url, the catalogue's own page first. */
    val links: List<Pair<String, String>> = emptyList(),
)

internal fun parseAniListDetail(release: Release, media: JsonObject?): ReleaseDetail {
    media ?: return ReleaseDetail(release, links = listOf("AniList" to release.url))
    val fresh = parseAniListRelease(media) ?: release
    val staff = (media["staff"] as? JsonObject)?.get("edges").array().mapNotNull { it as? JsonObject }
        .mapNotNull { edge ->
            val name = ((edge["node"] as? JsonObject)?.get("name") as? JsonObject)?.get("full").text() ?: return@mapNotNull null
            edge["role"].text().orEmpty() to name
        }
    return ReleaseDetail(
        release = fresh,
        banner = media["bannerImage"].text(),
        description = media["description"].text()?.let(::cleanDescription),
        chapters = media["chapters"].int(),
        volumes = media["volumes"].int(),
        tags = media["tags"].array().mapNotNull { it as? JsonObject }
            .filter { it["isMediaSpoiler"].text() != "true" }
            .mapNotNull { tag -> tag["name"].text()?.let { it to (tag["rank"].int() ?: 0) } },
        // "Story & Art" is both; "Original Story" is the author of what it adapts.
        authors = staff.filter { (role, _) -> "story" in role.lowercase() }.map { it.second }.distinct(),
        artists = staff.filter { (role, _) -> "art" in role.lowercase() }.map { it.second }.distinct(),
        relations = (media["relations"] as? JsonObject)?.get("edges").array().mapNotNull { it as? JsonObject }
            .mapNotNull { edge ->
                val node = edge["node"] as? JsonObject ?: return@mapNotNull null
                val title = node["title"] as? JsonObject
                val name = title?.get("english").text() ?: title?.get("romaji").text() ?: return@mapNotNull null
                (edge["relationType"].text() ?: return@mapNotNull null) to name
            },
        links = listOf("AniList" to fresh.url) + media["externalLinks"].array().mapNotNull { it as? JsonObject }
            .mapNotNull { link -> link["url"].text()?.let { (link["site"].text() ?: it) to it } },
    )
}

internal fun parseMangaDexDetail(release: Release, obj: JsonObject?): ReleaseDetail {
    obj ?: return ReleaseDetail(release, links = listOf("MangaDex" to release.url))
    val attributes = obj["attributes"] as? JsonObject
    val description = attributes?.get("description") as? JsonObject
    val people = obj["relationships"].array().mapNotNull { it as? JsonObject }
    fun named(type: String) = people.filter { it["type"].text() == type }
        .mapNotNull { (it["attributes"] as? JsonObject)?.get("name").text() }
    val links = attributes?.get("links") as? JsonObject
    return ReleaseDetail(
        release = parseMangaDexRelease(obj)?.copy(cover = release.cover, popularity = release.popularity) ?: release,
        description = (description?.get("pt-br").text() ?: description?.get("en").text())?.let(::cleanDescription),
        tags = attributes?.get("tags").array().mapNotNull { it as? JsonObject }
            .mapNotNull { ((it["attributes"] as? JsonObject)?.get("name") as? JsonObject)?.get("en").text() }
            .map { it to 0 },
        authors = named("author"),
        artists = named("artist"),
        links = listOf("MangaDex" to release.url) + listOfNotNull(
            links?.get("al").text()?.let { "AniList" to "https://anilist.co/manga/$it" },
            links?.get("mu").text()?.let { "MangaUpdates" to "https://www.mangaupdates.com/series/$it" },
            links?.get("raw").text()?.let { "Raw" to it },
            links?.get("engtl").text()?.let { "Official (EN)" to it },
        ),
    )
}

/** AniList's description is HTML-ish even when asked for plain text: breaks, italics, entities. */
internal fun cleanDescription(text: String): String = text
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .let { Parser.unescapeEntities(it, false) }
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

private fun portugueseOf(obj: JsonObject?): List<String> =
    ((obj?.get("attributes") as? JsonObject)?.get("altTitles")).array()
        .mapNotNull { (it as? JsonObject)?.let { names -> names["pt-br"].text() ?: names["pt"].text() } }
        .distinct()
