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
import kotlin.math.ln
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
        /** See [Recommended.cf]. */
        val cf: Float = 0f,
        val anilistId: Long? = null,
        val malId: Long? = null,
    )

    /**
     * A library entry to ask "what is like this?" about.
     *
     * @param weight how much the reader likes it, 0..1: a recommendation from a favourite counts
     *   for more than one from something merely finished.
     * @param anilistId and the others: its ids, when known, so the services are asked about this
     *   series and not about whatever a title search lands on — a Portuguese release name finds
     *   nothing, and a short one finds the wrong series.
     */
    data class Seed(
        val title: String,
        val weight: Float,
        val anilistId: Long? = null,
        val malId: Long? = null,
        val muId: Long? = null,
    )

    /** What the library already holds: a suggestion of any of it is not a discovery. */
    data class Known(
        val anilist: Set<Long> = emptySet(),
        val mal: Set<Long> = emptySet(),
        /** Every name of every entry, as [titleKey]s. */
        val titles: Set<String> = emptySet(),
    ) {
        operator fun contains(s: Suggestion): Boolean =
            (s.anilistId != null && s.anilistId in anilist) ||
                (s.malId != null && s.malId in mal) ||
                s.titles.any { titleKey(it) in titles }
    }

    /** One answer from one service, and how strongly it was put. */
    internal data class Rec(val provider: String, val suggestion: Suggestion, val strength: Float)

    private interface Provider {
        val name: String
        suspend fun similarTo(seed: Seed): List<Rec>
    }

    /** A suggestion located in a source. */
    data class Located(val suggestion: Suggestion, val sourceId: Long, val url: String, val title: String, val thumbnail: String?)

    /**
     * @param onProgress called as work lands: first per seed asked about, then per suggestion
     *   searched for, over the sum of both.
     */
    suspend fun await(
        seeds: List<Seed>,
        sources: List<CatalogueSource>,
        known: Known = Known(),
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<Located> {
        if (seeds.isEmpty() || sources.isEmpty()) return emptyList()
        // Seeds AniList can be asked about by id go [ANILIST_BATCH] to a request, so many more of
        // them can be asked about; the rest fall back to a title search each.
        val byId = seeds.filter { it.anilistId != null }.take(SEEDS_BY_ID).chunked(ANILIST_BATCH)
        val byTitle = seeds.filter { it.anilistId == null }.take(SEEDS)
        val strongest = seeds.take(SEEDS)
        val others = listOf(MyAnimeList(), MangaUpdates())
        var done = 0
        val total = byId.size + byTitle.size + strongest.size + LOCATE
        onProgress(done, total)

        val raw = mutableListOf<Pair<Seed, Rec>>()
        suspend fun ask(seed: Seed, provider: Provider) {
            raw += withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                runCatching { provider.similarTo(seed) }
                    .onFailure { Logger.w(it) { "${provider.name} had nothing for ${seed.title}" } }
                    .getOrDefault(emptyList())
            }.orEmpty().map { seed to it }
        }
        byId.forEach { chunk ->
            raw += withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                runCatching { anilistById(chunk) }.onFailure { Logger.w(it) { "AniList batch failed" } }.getOrDefault(emptyList())
            }.orEmpty()
            onProgress(++done, total)
        }
        val anilist = AniList()
        byTitle.forEach { seed ->
            ask(seed, anilist)
            onProgress(++done, total)
        }
        // MyAnimeList and MangaUpdates for the strongest seeds: a second and third opinion, and
        // the only one for a series AniList does not know.
        strongest.forEach { seed ->
            others.forEach { ask(seed, it) }
            onProgress(++done, total)
        }
        val suggestions = mergeRecommendations(raw, known).take(LOCATE)
        val asked = byId.size + byTitle.size + strongest.size
        return locate(suggestions, sources) { found, _ -> onProgress(asked + found, total) }
    }

    /**
     * Up to [ANILIST_BATCH] seeds in one request, each by its id, through aliased fields. The ids
     * are numbers, so inlining them is safe.
     */
    private suspend fun anilistById(seeds: List<Seed>): List<Pair<Seed, Rec>> {
        val fields = seeds.mapIndexed { i, seed ->
            "m$i: Media(id: ${seed.anilistId}, type: MANGA) { recommendations(sort: RATING_DESC, perPage: $PER_SEED_BY_ID) " +
                "{ nodes { rating mediaRecommendation { $ANILIST_MEDIA_FIELDS } } } }"
        }.joinToString("\n")
        val data = network.client.newCall(POST(ANILIST, body = buildJsonObject { put("query", "{ $fields }") }.toString().toRequestBody(jsonMime)))
            .awaitSuccess()
            .parseAs<JsonObject>()["data"]?.jsonObject ?: return emptyList()
        return seeds.flatMapIndexed { i, seed ->
            val media = data["m$i"]?.takeIf { it !is JsonNull }?.jsonObject ?: return@flatMapIndexed emptyList()
            anilistRecommendations(media, seed.title).map { seed to it }
        }
    }

    /** The recommendations under one AniList media object, strongest backed first. */
    private fun anilistRecommendations(media: JsonObject, seedName: String): List<Rec> =
        media["recommendations"]?.jsonObject?.get("nodes")?.jsonArray.orEmpty().mapNotNull { node ->
            val rec = node.jsonObject["mediaRecommendation"]?.takeIf { it !is JsonNull }?.jsonObject ?: return@mapNotNull null
            if (rec["format"]?.jsonPrimitive?.contentOrNull == "NOVEL") return@mapNotNull null
            val rating = node.jsonObject["rating"]?.jsonPrimitive?.intOrNull ?: 0
            // Nobody upvoting it, or more people against than for: someone's opinion, not a pattern.
            if (rating <= 0) return@mapNotNull null
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
            Rec(
                provider = "AniList",
                suggestion = Suggestion(
                    seed = seedName,
                    titles = titles.distinct(),
                    genres = genres,
                    anilistId = rec["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    malId = rec["idMal"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                ),
                strength = backing(rating),
            )
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
                    anilistId = same.firstNotNullOfOrNull { it.anilistId },
                    malId = same.firstNotNullOfOrNull { it.malId },
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
                    "a$i: Page(perPage: $CATALOG_PER_PAGE) { media($args) { id idMal title { romaji english } synonyms genres tags { name rank } averageScore popularity } }"
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
                            anilistId = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                            malId = obj["idMal"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
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

    /** For a seed with no AniList id: one GraphQL call, the best title match and its recommendations. */
    private inner class AniList : Provider {
        override val name = "AniList"

        override suspend fun similarTo(seed: Seed): List<Rec> {
            val payload = buildJsonObject {
                put("query", ANILIST_QUERY)
                putJsonObject("variables") { put("q", seed.title) }
            }
            val root = network.client.newCall(POST(ANILIST, body = payload.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
                .parseAs<JsonObject>()
            val media = root["data"]?.jsonObject?.get("Media")?.jsonObject ?: return emptyList()
            val seedName = media["title"]?.jsonObject?.get("romaji")?.jsonPrimitive?.contentOrNull ?: seed.title
            return anilistRecommendations(media, seedName).take(PER_SEED)
        }
    }

    /**
     * Two calls per seed, with the app's own client id and no user token: the search, then the
     * details with the `recommendations` field. Nodes carry a title only; the source's details
     * fill the genres in later.
     */
    private inner class MyAnimeList : Provider {
        override val name = "MyAnimeList"

        override suspend fun similarTo(seed: Seed): List<Rec> {
            val id = seed.malId?.toString() ?: run {
                val search = network.client.newCall(GET("$MAL/manga?q=${seed.title.take(64).encode()}&limit=1&nsfw=true", malHeaders))
                    .awaitSuccess()
                    .parseAs<JsonObject>()
                search["data"]?.jsonArray?.firstOrNull()?.jsonObject?.get("node")?.jsonObject
                    ?.get("id")?.jsonPrimitive?.contentOrNull
            } ?: return emptyList()
            val seedName = seed.title
            val details = network.client.newCall(GET("$MAL/manga/$id?fields=recommendations,alternative_titles", malHeaders))
                .awaitSuccess()
                .parseAs<JsonObject>()
            return details["recommendations"]?.jsonArray.orEmpty()
                .mapNotNull { rec ->
                    val node = rec.jsonObject["node"]?.jsonObject ?: return@mapNotNull null
                    val title = node["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val backers = rec.jsonObject["num_recommendations"]?.jsonPrimitive?.intOrNull ?: 1
                    Rec(
                        provider = name,
                        suggestion = Suggestion(seedName, listOf(title), emptyList(), malId = node["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()),
                        strength = backing(backers),
                    )
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

        override suspend fun similarTo(seed: Seed): List<Rec> {
            val id = seed.muId?.toString() ?: run {
                val body = buildJsonObject {
                    put("search", seed.title)
                    put("filter_types", buildJsonArray { add("drama cd"); add("novel") })
                }
                val search = network.client.newCall(POST("$MU/v1/series/search", body = body.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<JsonObject>()
                search["results"]?.jsonArray?.firstOrNull()?.jsonObject?.get("record")?.jsonObject
                    ?.get("series_id")?.jsonPrimitive?.contentOrNull
            } ?: return emptyList()
            val seedName = seed.title
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
                Rec(
                    provider = name,
                    suggestion = Suggestion(seedName, (listOf(title) + associated).distinct(), genres),
                    strength = backing(rec.jsonObject["weight"]?.jsonPrimitive?.intOrNull ?: 1),
                )
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

        /** Seeds asked about by title, and seeds MyAnimeList and MangaUpdates are asked about. */
        private const val SEEDS = 5

        /** Seeds AniList is asked about by id: cheap, [ANILIST_BATCH] to a request. */
        private const val SEEDS_BY_ID = 40
        private const val ANILIST_BATCH = 10
        private const val PER_SEED_BY_ID = 10

        private const val ANILIST_MEDIA_FIELDS = "id idMal format title { romaji english } synonyms genres tags { name rank }"

        /** How many answers per seed per service are kept. */
        private const val PER_SEED = 5

        /** How many merged suggestions are searched for in the sources, strongest first. */
        private const val LOCATE = 120

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
                    |mediaRecommendation { $ANILIST_MEDIA_FIELDS }
                  |}
                |}
              |}
            |}
        """.trimMargin()
    }
}

/** A title as the dedupe compares it: case and punctuation gone. */
fun titleKey(title: String): String = title.lowercase().filter { it.isLetterOrDigit() }

/** How strongly a recommendation counts for how many people backed it: diminishing, never zero. */
internal fun backing(people: Int): Float = 1f + ln(1f + people.coerceAtLeast(0))

/**
 * Every service's answer for every seed, folded into one suggestion per series, strongest first.
 *
 * The same series is recognised by its AniList or MyAnimeList id when a service gave one, else by
 * any of its names, so MangaUpdates naming a series by title still lands on AniList's record of
 * it. Its [ExternalSuggestions.Suggestion.cf] is the sum over the seeds that recommend it of how
 * much the reader likes the seed times how many people backed it, scaled so the strongest is 1:
 * the series several favourites point to rises, one weak mention does not. Anything in [known] —
 * the library, by id or by any name — is dropped.
 */
internal fun mergeRecommendations(
    raw: List<Pair<ExternalSuggestions.Seed, ExternalSuggestions.Rec>>,
    known: ExternalSuggestions.Known,
): List<ExternalSuggestions.Suggestion> {
    fun aliases(s: ExternalSuggestions.Suggestion) =
        listOfNotNull(s.anilistId?.let { "al:$it" }, s.malId?.let { "mal:$it" }) + s.titles.map { "t:" + titleKey(it) }
    val groupOf = mutableMapOf<String, String>()
    val groups = linkedMapOf<String, MutableList<Pair<ExternalSuggestions.Seed, ExternalSuggestions.Rec>>>()
    // Answers carrying ids first, so their record is the one the others join.
    raw.sortedByDescending { (_, rec) -> rec.suggestion.anilistId != null }.forEach { pair ->
        val names = aliases(pair.second.suggestion)
        if (names.isEmpty()) return@forEach
        val key = names.firstNotNullOfOrNull { groupOf[it] } ?: names.first()
        names.forEach { groupOf.putIfAbsent(it, key) }
        groups.getOrPut(key) { mutableListOf() } += pair
    }
    val merged = groups.values.mapNotNull { same ->
        val suggestions = same.map { it.second.suggestion }
        val one = ExternalSuggestions.Suggestion(
            seed = "",
            titles = suggestions.flatMap { it.titles }.distinctBy { it.lowercase() },
            genres = suggestions.flatMap { it.genres }.distinctBy { it.lowercase() },
            anilistId = suggestions.firstNotNullOfOrNull { it.anilistId },
            malId = suggestions.firstNotNullOfOrNull { it.malId },
        )
        if (one in known) return@mapNotNull null
        val bySeed = same.groupBy({ it.first }, { it.first.weight * it.second.strength }).mapValues { it.value.sum() }
        one.copy(
            seed = bySeed.maxBy { it.value }.key.title,
            votes = same.map { it.second.provider }.distinct().size,
            cf = bySeed.values.sum(),
        )
    }
    val top = merged.maxOfOrNull { it.cf }?.takeIf { it > 0f } ?: return merged
    return merged.map { it.copy(cf = it.cf / top) }.sortedByDescending { it.cf }
}
