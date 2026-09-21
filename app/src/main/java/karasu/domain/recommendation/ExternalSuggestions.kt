package karasu.domain.recommendation

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.smartsearch.SmartSearchEngine
import eu.kanade.tachiyomi.source.CatalogueSource
import java.net.URLEncoder
import karasu.domain.manga.failures.ReadFailures
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What the wider world says is like the reader's favourites, found in the reader's own sources.
 *
 * Tags say what a series is about; they never say it is *good*, or that people who liked X also
 * liked Y. The trackers' recommendations are that — human-curated, per series, and served
 * without a login by AniList, MyAnimeList and MangaUpdates — so each strong seed from the
 * profile is asked "what is like this?" on all three, and every answer that exists in one of the
 * reader's sources becomes a candidate. An answer two services agree on is located first and
 * ranked higher: it is the one thing here that is corroborated. (Kitsu has no such endpoint;
 * Komga, Kavita and Suwayomi are the reader's own servers and know nothing beyond them.)
 *
 * Names are the hard part: a Portuguese source lists a series under its Portuguese title, which
 * the trackers rarely know. Romaji and English are tried first, then MangaUpdates' associated
 * titles when it named the series; for whatever is still not found, MangaDex is asked for the
 * series' alternative titles in the reader's languages and the search is repeated with those.
 *
 * ponytail: bounded by [SEEDS] × [PER_SEED] per service, [LOCATE] suggestions located at most,
 * over the sources the library uses, most used first; only the nightly job calls it. Every
 * failure is one suggestion fewer, never an error.
 */
class ExternalSuggestions(
    private val network: NetworkHelper,
    private val languages: List<String>,
    private val readFailures: ReadFailures = Injekt.get(),
) {
    /**
     * One thing a service said was like [seed].
     *
     * @param titles every name it goes by that is known, international first.
     * @param votes how many services named it, once merged.
     */
    data class Suggestion(
        val seed: String,
        val titles: List<String>,
        val genres: List<String>,
        val votes: Int = 1,
        /** From the catalogue pass: AniList's numbers, so they weigh from the start. */
        val popularity: Int? = null,
        val averageScore: Int? = null,
        val fromCatalog: Boolean = false,
    )

    private interface Provider {
        val name: String
        suspend fun similarTo(seed: String): List<Suggestion>
    }

    /** A suggestion located in a source. */
    data class Located(val suggestion: Suggestion, val sourceId: Long, val url: String, val title: String, val thumbnail: String?)

    /**
     * @param onProgress called as work lands: first per seed asked about, then per suggestion
     *   searched for, over the sum of both.
     */
    suspend fun await(
        seeds: List<String>,
        sources: List<CatalogueSource>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Located> {
        if (seeds.isEmpty() || sources.isEmpty()) return emptyList()
        val providers = listOf(AniList(), MyAnimeList(), MangaUpdates())
        val asked = seeds.take(SEEDS)
        var done = 0
        val total = asked.size + LOCATE
        onProgress(done, total)
        val raw = asked.flatMap { seed ->
            providers.flatMap { provider ->
                withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                    runCatching { provider.similarTo(seed) }
                        .onFailure { Logger.w(it) { "${provider.name} had nothing for $seed" } }
                        .getOrDefault(emptyList())
                }.orEmpty()
            }.also { onProgress(++done, total) }
        }
        val suggestions = merge(raw).take(LOCATE)
        return locate(suggestions, sources) { found, _ -> onProgress(asked.size + found, total) }
    }

    /**
     * Finds each suggestion in the reader's sources: international names first, then MangaDex's
     * names in the reader's languages for what is still missing.
     */
    suspend fun locate(
        suggestions: List<Suggestion>,
        sources: List<CatalogueSource>,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Located> {
        if (suggestions.isEmpty() || sources.isEmpty()) return emptyList()
        val engine = SmartSearchEngine(Job() + Dispatchers.IO)
        val located = mutableListOf<Located>()
        val missing = mutableListOf<Suggestion>()
        var done = 0
        for (suggestion in suggestions) {
            locate(engine, suggestion, suggestion.titles.take(TITLES), sources)?.let(located::add) ?: missing.add(suggestion)
            onProgress(++done, suggestions.size)
        }
        // Second pass, only for what no source knew by its international names.
        for (suggestion in missing) {
            val local = runCatching { altTitles(suggestion.titles.first()) }.getOrDefault(emptyList())
            if (local.isEmpty()) continue
            locate(engine, suggestion, local, sources)?.let(located::add)
        }
        return located
    }

    /**
     * AniList's own catalogue, searched by the reader's tags: what the community rates best and
     * reads most under each, plus what started in the last [recentYears] years. This is the one place a search
     * by several tags means "all of them", and the one place a candidate arrives with a score
     * someone gave it. Aliased fields batch [CATALOG_BATCH] searches per request.
     *
     * @param country an ISO code to keep to, when the reader's formats say so, else null.
     */
    suspend fun catalog(profile: TasteProfile, country: String?, recentYears: Int): List<Suggestion> {
        // Every tag the reader is at least somewhat into, the same cut the source searches use.
        val tags = profile.tags.filterValues { it >= CATALOG_TAG_MIN_WEIGHT }.keys.take(CATALOG_TAG_CAP)
        if (tags.isEmpty()) return emptyList()
        val passes = listOf(
            suspend { anilistCatalog(profile, tags, country, recentYears) },
            suspend { mangaUpdatesCatalog(tags, country) },
            suspend { kitsuCatalog(tags, country) },
            suspend { myAnimeListCatalog(tags, country) },
        )
        val all = passes.flatMap { pass -> runCatching { pass() }.onFailure { Logger.w(it) { "Catalogue pass failed" } }.getOrDefault(emptyList()) }
        // The same series in two catalogues is one, with a vote from each and its best score:
        // agreement between services is worth more than any one of them.
        return all.groupBy { it.titles.first().lowercase().filter { c -> c.isLetterOrDigit() } }
            .values
            .map { same ->
                Suggestion(
                    seed = same.map { it.seed }.distinct().joinToString(", "),
                    titles = same.flatMap { it.titles }.distinctBy { it.lowercase() },
                    genres = same.flatMap { it.genres }.distinctBy { it.lowercase() },
                    votes = same.map { it.seed }.distinct().size,
                    popularity = same.mapNotNull { it.popularity }.maxOrNull(),
                    averageScore = same.mapNotNull { it.averageScore }.maxOrNull(),
                    fromCatalog = true,
                )
            }
            .sortedWith(compareByDescending<Suggestion> { it.votes }.thenByDescending { it.averageScore ?: 0 })
            .take(CATALOG_LOCATE)
    }

    private suspend fun anilistCatalog(profile: TasteProfile, tags: List<String>, country: String?, recentYears: Int): List<Suggestion> {
        val sinceYear = java.time.Year.now().value - recentYears
        data class Ask(val tags: List<String>, val sort: String, val since: Int?)
        val asks = tags.flatMap { listOf(Ask(listOf(it), "SCORE_DESC", null), Ask(listOf(it), "POPULARITY_DESC", null), Ask(listOf(it), "SCORE_DESC", sinceYear)) } +
            profile.pairs.keys.take(CATALOG_PAIRS).map { it.split('|') }.filter { it.size == 2 }
                .map { halves -> Ask(halves.map { half -> tags.firstOrNull { t -> t.equals(half, true) } ?: half }, "SCORE_DESC", null) }

        val out = mutableListOf<Suggestion>()
        asks.chunked(CATALOG_BATCH).forEach { batch ->
            runCatching {
                val fields = batch.mapIndexed { i, ask ->
                    val genres = ask.tags.filter { it in ANILIST_GENRES }
                    val others = ask.tags.filterNot { it in ANILIST_GENRES }
                    val args = buildList {
                        add("type: MANGA")
                        add("format_not_in: [NOVEL]")
                        add("sort: ${ask.sort}")
                        add("averageScore_greater: $CATALOG_MIN_SCORE")
                        if (genres.isNotEmpty()) add("genre_in: [" + genres.joinToString { it.graphqlLiteral() } + "]")
                        if (others.isNotEmpty()) add("tag_in: [" + others.joinToString { it.graphqlLiteral() } + "]")
                        if (ask.since != null) add("startDate_greater: ${ask.since}0000")
                        if (country != null) add("countryOfOrigin: \"$country\"")
                    }.joinToString(", ")
                    "a$i: Page(perPage: $CATALOG_PER_PAGE) { media($args) { title { romaji english } synonyms genres tags { name rank } averageScore popularity } }"
                }.joinToString("\n")
                val root = network.client.newCall(POST(ANILIST, body = buildJsonObject { put("query", "{ $fields }") }.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<JsonObject>()["data"]?.jsonObject ?: return@runCatching
                batch.indices.forEach { i ->
                    root["a$i"]?.jsonObject?.get("media")?.jsonArray.orEmpty().forEach { m ->
                        val obj = m.jsonObject
                        val names = obj["title"]?.jsonObject
                        val titles = listOfNotNull(names?.get("romaji")?.jsonPrimitive?.contentOrNull, names?.get("english")?.jsonPrimitive?.contentOrNull) +
                            obj["synonyms"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                        if (titles.isEmpty()) return@forEach
                        val genres = obj["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } +
                            obj["tags"]?.jsonArray.orEmpty().mapNotNull { tag ->
                                val t = tag.jsonObject
                                t["name"]?.jsonPrimitive?.contentOrNull?.takeIf { (t["rank"]?.jsonPrimitive?.intOrNull ?: 0) >= TAG_RANK }
                            }
                        out += Suggestion(
                            seed = "AniList",
                            titles = titles.distinct(),
                            genres = genres,
                            popularity = obj["popularity"]?.jsonPrimitive?.intOrNull,
                            averageScore = obj["averageScore"]?.jsonPrimitive?.intOrNull,
                            fromCatalog = true,
                        )
                    }
                }
            }.onFailure { Logger.w(it) { "AniList catalogue batch failed" } }
        }
        // The same series under several tags is one; the best-scored copy is as good as any.
        return out.groupBy { it.titles.first().lowercase().filter { c -> c.isLetterOrDigit() } }
            .values.map { same -> same.maxBy { it.averageScore ?: 0 } }
    }

    /**
     * MangaUpdates' search, rated best first, per tag. Its genres are a fixed list and anything
     * else is a "category"; the search takes both and means all of them.
     */
    private suspend fun mangaUpdatesCatalog(tags: List<String>, country: String?): List<Suggestion> {
        val type = when (country) { "KR" -> "Manhwa"; "CN" -> "Manhua"; "JP" -> "Manga"; else -> null }
        return tags.flatMap { tag ->
            val body = buildJsonObject {
                if (tag in MU_GENRES) put("genre", buildJsonArray { add(tag) }) else put("category", buildJsonArray { add(tag) })
                type?.let { put("type", buildJsonArray { add(it) }) }
                put("orderby", "rating")
                put("perpage", CATALOG_PER_PAGE)
                put("filter_types", buildJsonArray { add("drama cd"); add("novel") })
            }
            runCatching {
                network.client.newCall(POST("$MU/v1/series/search", body = body.toString().toRequestBody(jsonMime)))
                    .awaitSuccess().parseAs<JsonObject>()["results"]?.jsonArray.orEmpty()
                    .mapNotNull { r ->
                        val rec = r.jsonObject["record"]?.jsonObject ?: return@mapNotNull null
                        val title = rec["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val rating = rec["bayesian_rating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
                        Suggestion(
                            seed = "MangaUpdates",
                            titles = listOf(title),
                            genres = rec["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["genre"]?.jsonPrimitive?.contentOrNull },
                            popularity = rec["rating_votes"]?.jsonPrimitive?.intOrNull,
                            averageScore = rating?.let { (it * 10).toInt() },
                            fromCatalog = true,
                        )
                    }
            }.onFailure { Logger.w(it) { "MangaUpdates catalogue failed for $tag" } }.getOrDefault(emptyList())
        }
    }

    /** Kitsu's catalogue, best rated first, per tag; genres and categories are both slugs. */
    private suspend fun kitsuCatalog(tags: List<String>, country: String?): List<Suggestion> {
        val subtype = when (country) { "KR" -> "manhwa"; "CN" -> "manhua"; "JP" -> "manga"; else -> null }
        return tags.flatMap { tag ->
            // A slug, then URL-encoded: a tag is text a source or the reader wrote, not a path.
            val slug = URLEncoder.encode(tag.lowercase().replace(' ', '-'), "UTF-8")
            val filter = if (tag in KITSU_GENRES) "filter[genres]=$slug" else "filter[categories]=$slug"
            val url = "$KITSU/manga?$filter&sort=-averageRating&page[limit]=$CATALOG_PER_PAGE" +
                (subtype?.let { "&filter[subtype]=$it" } ?: "") +
                "&fields[manga]=canonicalTitle,titles,abbreviatedTitles,averageRating,userCount"
            runCatching {
                network.client.newCall(GET(url.encodeBrackets(), kitsuHeaders)).awaitSuccess().parseAs<JsonObject>()["data"]?.jsonArray.orEmpty()
                    .mapNotNull { m ->
                        val a = m.jsonObject["attributes"]?.jsonObject ?: return@mapNotNull null
                        val title = a["canonicalTitle"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val others = a["titles"]?.jsonObject?.values.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } +
                            a["abbreviatedTitles"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                        Suggestion(
                            seed = "Kitsu",
                            titles = (listOf(title) + others).distinct(),
                            genres = listOf(tag),
                            popularity = a["userCount"]?.jsonPrimitive?.intOrNull,
                            averageScore = a["averageRating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toInt(),
                            fromCatalog = true,
                        )
                    }
            }.onFailure { Logger.w(it) { "Kitsu catalogue failed for $tag" } }.getOrDefault(emptyList())
        }
    }

    /**
     * MyAnimeList's ranking for the reader's kind of series, by score and by popularity. It
     * cannot filter by genre, but it reports them: only entries carrying one of the reader's
     * tags are kept.
     */
    private suspend fun myAnimeListCatalog(tags: List<String>, country: String?): List<Suggestion> {
        val type = when (country) { "KR" -> "manhwa"; "CN" -> "manhua"; else -> "manga" }
        val wanted = tags.map { it.lowercase() }.toSet()
        return listOf(type, "bypopularity").flatMap { ranking ->
            runCatching {
                network.client.newCall(GET("$MAL/manga/ranking?ranking_type=$ranking&limit=$MAL_RANKING&fields=mean,genres,num_list_users,alternative_titles", malHeaders))
                    .awaitSuccess().parseAs<JsonObject>()["data"]?.jsonArray.orEmpty()
                    .mapNotNull { r ->
                        val node = r.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
                        val genres = node["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
                        if (genres.none { canonicalTag(it).lowercase() in wanted }) return@mapNotNull null
                        val title = node["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                        val alt = node["alternative_titles"]?.jsonObject
                        val others = listOfNotNull(alt?.get("en")?.jsonPrimitive?.contentOrNull) +
                            alt?.get("synonyms")?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                        Suggestion(
                            seed = "MyAnimeList",
                            titles = (listOf(title) + others).distinct(),
                            genres = genres,
                            popularity = node["num_list_users"]?.jsonPrimitive?.intOrNull,
                            averageScore = node["mean"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.let { (it * 10).toInt() },
                            fromCatalog = true,
                        )
                    }
            }.onFailure { Logger.w(it) { "MyAnimeList ranking failed for $ranking" } }.getOrDefault(emptyList())
        }
    }

    /** Kitsu's JSON:API wants its brackets escaped, and OkHttp will not do it for us. */
    private fun String.encodeBrackets() = replace("[", "%5B").replace("]", "%5D")

    /**
     * A tag as a GraphQL string literal. Tags come from sources and from the reader's own typing,
     * so quotes, backslashes and line breaks are stripped rather than trusted: nothing typed
     * into a tag can close the literal and rewrite the query.
     */
    private fun String.graphqlLiteral(): String = "\"" + filter { it != '"' && it != '\\' && it >= ' ' }.take(64) + "\""

    /**
     * The same series named by two services is one suggestion with two votes and the union of
     * their names. Matched on the first title stripped of case and punctuation: services
     * romanise alike far more often than they agree on an English name.
     */
    private fun merge(raw: List<Suggestion>): List<Suggestion> = raw
        .groupBy { it.titles.first().lowercase().filter { c -> c.isLetterOrDigit() } }
        .values
        .map { same ->
            Suggestion(
                seed = same.first().seed,
                titles = same.flatMap { it.titles }.distinctBy { it.lowercase() },
                genres = same.flatMap { it.genres }.distinctBy { it.lowercase() },
                votes = same.size,
            )
        }
        .sortedByDescending { it.votes }

    /**
     * All sources at once, each cut off at [SEARCH_TIMEOUT_MS]; the first in the reader's order
     * that has it wins. A source that times out is marked failing and left out for the rest of
     * the run, so one dead source costs one timeout, not one per title.
     */
    private suspend fun locate(
        engine: SmartSearchEngine,
        suggestion: Suggestion,
        titles: List<String>,
        sources: List<CatalogueSource>,
    ): Located? {
        val live = sources.filterNot { readFailures.isSourceFailing(it.id) }
        if (live.isEmpty()) return null
        val hits = coroutineScope {
            live.map { source ->
                async {
                    // Optional.empty = searched and not there; null = timed out or failed.
                    val outcome = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                        runCatching { java.util.Optional.ofNullable(engine.normalSearchAliases(source, titles)) }
                            .getOrNull()
                    }
                    if (outcome == null) readFailures.recordSource(source.id)
                    source to outcome?.orElse(null)
                }
            }.awaitAll()
        }
        val (source, found) = hits.firstOrNull { (_, hit) -> hit != null } ?: return null
        return Located(suggestion, source.id, found!!.url, found.title, found.thumbnail_url)
    }

    /** One GraphQL call per seed: the best match and its recommendations, best rated first. */
    private inner class AniList : Provider {
        override val name = "AniList"

        override suspend fun similarTo(seed: String): List<Suggestion> {
            val payload = buildJsonObject {
                put("query", ANILIST_QUERY)
                putJsonObject("variables") { put("q", seed) }
            }
            val root = network.client.newCall(POST(ANILIST, body = payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
                .parseAs<JsonObject>()
            val media = root["data"]?.jsonObject?.get("Media")?.jsonObject ?: return emptyList()
            val seedName = media["title"]?.jsonObject?.get("romaji")?.jsonPrimitive?.contentOrNull ?: seed
            return media["recommendations"]?.jsonObject?.get("nodes")?.jsonArray.orEmpty()
                .mapNotNull { node ->
                    val rec = node.jsonObject["mediaRecommendation"]?.takeIf { it !is JsonNull }?.jsonObject
                        ?: return@mapNotNull null
                    val names = rec["title"]?.jsonObject
                    val titles = listOfNotNull(
                        names?.get("romaji")?.jsonPrimitive?.contentOrNull,
                        names?.get("english")?.jsonPrimitive?.contentOrNull,
                    ) + rec["synonyms"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
                    if (titles.isEmpty()) return@mapNotNull null
                    val genres = rec["genres"]?.jsonArray.orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull } +
                        rec["tags"]?.jsonArray.orEmpty().mapNotNull { tag ->
                            val obj = tag.jsonObject
                            obj["name"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { (obj["rank"]?.jsonPrimitive?.intOrNull ?: 0) >= TAG_RANK }
                        }
                    Suggestion(seedName, titles.distinct(), genres)
                }
                .take(PER_SEED)
        }
    }

    /**
     * Two calls per seed, with the app's own client id and no user token: the search, then the
     * details with the `recommendations` field. Nodes carry a title only; the source's details
     * fill the genres in later.
     */
    private inner class MyAnimeList : Provider {
        override val name = "MyAnimeList"

        override suspend fun similarTo(seed: String): List<Suggestion> {
            val search = network.client.newCall(GET("$MAL/manga?q=${seed.take(64).encode()}&limit=1&nsfw=true", malHeaders))
                .awaitSuccess()
                .parseAs<JsonObject>()
            val node = search["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("node")?.jsonObject ?: return emptyList()
            val id = node["id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
            val seedName = node["title"]?.jsonPrimitive?.contentOrNull ?: seed
            val details = network.client.newCall(GET("$MAL/manga/$id?fields=recommendations,alternative_titles", malHeaders))
                .awaitSuccess()
                .parseAs<JsonObject>()
            return details["recommendations"]?.jsonArray.orEmpty()
                .mapNotNull { rec ->
                    val title = rec.jsonObject["node"]?.jsonObject?.get("title")?.jsonPrimitive?.contentOrNull
                        ?: return@mapNotNull null
                    Suggestion(seedName, listOf(title), emptyList())
                }
                .take(PER_SEED)
        }
    }

    /**
     * Search, then the series page, then one page per recommended series: that last hop is what
     * buys the associated titles, which is where a Portuguese release name is most likely to be.
     */
    private inner class MangaUpdates : Provider {
        override val name = "MangaUpdates"

        override suspend fun similarTo(seed: String): List<Suggestion> {
            val body = buildJsonObject {
                put("search", seed)
                put("filter_types", buildJsonArray { add("drama cd"); add("novel") })
            }
            val search = network.client.newCall(POST("$MU/v1/series/search", body = body.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
                .parseAs<JsonObject>()
            val record = search["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("record")?.jsonObject ?: return emptyList()
            val id = record["series_id"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
            val seedName = record["title"]?.jsonPrimitive?.contentOrNull ?: seed
            val series = network.client.newCall(GET("$MU/v1/series/$id")).awaitSuccess().parseAs<JsonObject>()
            val recommended = series["recommendations"]?.jsonArray.orEmpty()
                .sortedByDescending { it.jsonObject["weight"]?.jsonPrimitive?.intOrNull ?: 0 }
                .take(PER_SEED)
            return recommended.mapNotNull { rec ->
                val recId = rec.jsonObject["series_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val page = runCatching {
                    network.client.newCall(GET("$MU/v1/series/$recId")).awaitSuccess().parseAs<JsonObject>()
                }.getOrNull()
                val title = page?.get("title")?.jsonPrimitive?.contentOrNull
                    ?: rec.jsonObject["series_name"]?.jsonPrimitive?.contentOrNull
                    ?: return@mapNotNull null
                val associated = page?.get("associated")?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["title"]?.jsonPrimitive?.contentOrNull }
                val genres = page?.get("genres")?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["genre"]?.jsonPrimitive?.contentOrNull }
                Suggestion(seedName, (listOf(title) + associated).distinct(), genres)
            }
        }
    }

    private val malHeaders = okhttp3.Headers.headersOf("X-MAL-CLIENT-ID", MyAnimeListApi.CLIENT_ID)
    private val kitsuHeaders = okhttp3.Headers.headersOf("Accept", "application/vnd.api+json")

    private fun String.encode() = URLEncoder.encode(this, "UTF-8")

    /** MangaDex's alternative titles for [title] in the reader's languages, most local first. */
    private suspend fun altTitles(title: String): List<String> {
        val url = "$MANGADEX/manga?limit=1&title=" + title.encode()
        val root = network.client.newCall(GET(url)).awaitSuccess().parseAs<JsonObject>()
        val entry = root["data"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        val alts = entry["attributes"]?.jsonObject?.get("altTitles")?.jsonArray.orEmpty()
            .flatMap { it.jsonObject.entries }
        return languages.flatMap { lang ->
            alts.filter { (key, _) -> key.equals(lang, true) }.mapNotNull { (_, v) -> v.jsonPrimitive.contentOrNull }
        }.distinct()
    }

    companion object {
        private const val ANILIST = "https://graphql.anilist.co/"
        private const val MAL = "https://api.myanimelist.net/v2"
        private const val MU = "https://api.mangaupdates.com"
        private const val KITSU = "https://kitsu.io/api/edge"
        private const val MANGADEX = "https://api.mangadex.org"

        /** How many of the profile's seeds are asked about. */
        private const val SEEDS = 5

        /** How many answers per seed per service are kept. */
        private const val PER_SEED = 5

            /** How many merged suggestions are searched for in the sources, most agreed first. */
        private const val LOCATE = 40

        /** International titles tried per suggestion per source before falling back to MangaDex. */
        private const val TITLES = 2

        /** One tracker call's allowance. */
        private const val PROVIDER_TIMEOUT_MS = 20_000L

        /** One source search's allowance when locating a title. */
        private const val SEARCH_TIMEOUT_MS = 20_000L

        /** AniList tags below this rank are noise ("Male Protagonist" at 30%). */
        private const val TAG_RANK = 60

        /** Catalogue pass: tags asked about (all at or above this weight), pairs, searches per request, results each. */
        private const val CATALOG_TAG_MIN_WEIGHT = 0.15f
        private const val CATALOG_TAG_CAP = 60
        private const val CATALOG_PAIRS = 5
        private const val CATALOG_BATCH = 6
        private const val CATALOG_PER_PAGE = 20

        /** Below this AniList score the community has said no. */
        private const val CATALOG_MIN_SCORE = 65

        /** How many catalogue finds are looked for in the sources, most agreed on first. */
        private const val CATALOG_LOCATE = 150

        /** Entries of each MyAnimeList ranking read. */
        private const val MAL_RANKING = 200

        /** MangaUpdates' genre list; anything else is one of its categories. */
        private val MU_GENRES = setOf(
            "Action", "Adult", "Adventure", "Comedy", "Doujinshi", "Drama", "Ecchi", "Fantasy", "Gender Bender", "Harem",
            "Hentai", "Historical", "Horror", "Josei", "Martial Arts", "Mature", "Mecha", "Mystery", "Psychological",
            "Romance", "School Life", "Sci-fi", "Seinen", "Shoujo", "Shoujo Ai", "Shounen", "Shounen Ai", "Slice of Life",
            "Smut", "Sports", "Supernatural", "Tragedy", "Yaoi", "Yuri",
        )

        /** Kitsu's genre list; anything else is one of its categories. */
        private val KITSU_GENRES = setOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Harem", "Historical", "Horror", "Josei",
            "Martial Arts", "Mecha", "Mystery", "Psychological", "Romance", "School", "Sci-Fi", "Seinen", "Shoujo",
            "Shounen", "Slice of Life", "Sports", "Supernatural", "Thriller", "Tragedy", "Yaoi", "Yuri",
        )

        /** AniList's fixed genre list; everything else is a tag, and the query says which is which. */
        private val ANILIST_GENRES = setOf(
            "Action", "Adventure", "Comedy", "Drama", "Ecchi", "Fantasy", "Horror", "Mahou Shoujo", "Mecha",
            "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
        )

        private val ANILIST_QUERY = """
            |query (${'$'}q: String) {
              |Media(search: ${'$'}q, type: MANGA, format_not_in: [NOVEL]) {
                |title { romaji }
                |recommendations(sort: RATING_DESC, perPage: 8) {
                  |nodes {
                    |rating
                    |mediaRecommendation {
                      |title { romaji english }
                      |synonyms
                      |genres
                      |tags { name rank }
                    |}
                  |}
                |}
              |}
            |}
        """.trimMargin()
    }
}
