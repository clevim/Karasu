package karasu.domain.recommendation

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.RecommendationSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SManga
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.util.normalizedLevenshteinSimilarity
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.POST
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The whole pipeline, from library to ranked list. Run by the nightly job and by the window when
 * it has nothing to show.
 *
 * Candidates come from the enabled sources, searched with their own genre filter set to one of
 * the reader's top tags. Reading a candidate's tags would cost one request per candidate; sending
 * the tag in the search costs one request per source per tag, and every hit is known to carry
 * that tag. That is only a preliminary rank, though: a hit knows one tag of itself, so page one
 * of "Romance" is thirty entries tied at the same score. The shortlist that survives it gets its
 * details and chapters read — a bounded number of requests — and is ranked again on everything
 * it is. What was not read is not shown: a card with no chapter count is a card that lies.
 */
class BuildRecommendations(
    private val getTasteProfile: GetTasteProfile,
    private val getLibraryManga: GetLibraryManga,
    private val getChapter: GetChapter,
    private val sourceManager: SourceManager,
    private val preferences: PreferencesHelper,
    private val network: NetworkHelper,
) {
    /**
     * Gathers every candidate — the tag searches across every enabled source, plus the trackers'
     * suggestions — and queues them best first. Nothing is read yet: that is [detailBatch]'s job,
     * a batch at a time, so a target of five hundred can be met over several runs.
     */
    suspend fun search(
        now: Long = System.currentTimeMillis(),
        onProgress: suspend (BuildProgress) -> Unit = {},
    ): RecommendationSnapshot {
        onProgress(BuildProgress(BuildProgress.Stage.PROFILE))
        val profile = getTasteProfile.await()
        val library = getLibraryManga.await().distinctBy { it.manga.id }
        val known = library.map { it.manga.title.key() }.toSet()

        val fromLibrary = library.filter { it.read == 0 }.map { it.toRecommended() }
        val hits = searchSources(profile, onProgress)
            // Whatever the library already has, however another source spells it, is not a discovery.
            .filter { it.title.key() !in known }
        val suggested = suggestions(profile, library, onProgress).filter { it.title.key() !in known }
        val suggestedKeys = suggested.map { it.title.key() }.toSet()

        // AniList's catalogue first — the only candidates that arrive already rated by people —
        // then the trackers' "like this", then the hits by preliminary rank. Copies of one series
        // on several sources stay together, so a batch reads all of them and the one with the
        // most chapters can be kept.
        val preliminary = profile.rank(hits.filter { it.title.key() !in suggestedKeys }) { it.tags }
            .map { it.item.copy(score = it.score, because = it.because) }
        val (fromCatalog, fromTrackers) = suggested.partition { it.fromCatalog }
        val queue = fromCatalog + fromTrackers + preliminary.groupBy { it.title.key() }.values.flatMap { it.take(COPIES) }

        return RecommendationSnapshot(
            builtAt = now,
            profileTags = profile.tags,
            items = rank(fromLibrary, profile, emptyMap(), now),
            librarySize = library.size,
            pending = queue,
        )
    }

    /**
     * Reads the next [batch] series off the queue and folds them into the window: details and
     * chapters, one copy kept per series, ranked with everything already there. Small on
     * purpose, with the caller pausing between batches, so a long build is a background hum
     * rather than a burst that stalls the app.
     */
    suspend fun detailBatch(
        snapshot: RecommendationSnapshot,
        batch: Int = BATCH,
        now: Long = System.currentTimeMillis(),
        onProgress: suspend (BuildProgress) -> Unit = {},
    ): RecommendationSnapshot {
        if (snapshot.pending.isEmpty()) return snapshot
        val profile = getTasteProfile.await()
        // A batch is [batch] distinct series, copies included.
        val keys = snapshot.pending.map { it.title.key() }.distinct().take(batch).toSet()
        val (take, rest) = snapshot.pending.partition { it.title.key() in keys }

        val fetched = fetchDetails(take, onProgress)
            // Only what was actually read makes the window. An entry whose details failed has no
            // chapter count and no real tags: shown, it is the "0 chapters" card the reader opens
            // and finds empty.
            .filter { it.fetched }
            .groupBy { it.title.key() }
            .values
            // The copy with the most chapters wins, but a tracker naming the series, or two of
            // them agreeing, is about the series and travels with it.
            .map { same ->
                same.maxBy { it.chapters ?: -1 }.copy(
                    via = same.firstNotNullOfOrNull { it.via },
                    votes = same.maxOf { it.votes },
                    foundByTag = same.any { it.foundByTag },
                    tags = same.flatMap { it.tags }.distinctBy { it.lowercase() },
                )
            }

        onProgress(BuildProgress(BuildProgress.Stage.RANKING))
        val fame = popularityOf(fetched)
        val merged = snapshot.items + fetched.map { item ->
            fame[item.title.key()]?.let { (pop, avg) -> item.copy(popularity = pop, averageScore = avg) } ?: item
        }
        return snapshot.copy(
            builtAt = now,
            items = rank(merged.distinctBy { it.sourceId to it.url }, profile, emptyMap(), now),
            pending = rest,
        )
    }

    /** The window's order: everything that is shown, scored fresh against [profile]. */
    private fun rank(items: List<Recommended>, profile: TasteProfile, fame: Map<String, Pair<Int, Int?>>, now: Long): List<Recommended> =
        profile.rank(items) { it.tags }
            .map { it.item.copy(score = it.score, because = it.because) }
            // Nothing to read yet is nothing to recommend, whatever it is tagged with; nor is
            // something its source stopped updating long ago for its size.
            .filter { it.fromLibrary || (it.chapters != 0 && !it.isOneShot && !it.isStale(now)) }
            .map { item -> fame[item.title.key()]?.let { (pop, avg) -> item.copy(popularity = pop, averageScore = avg) } ?: item }
            .map { it.withBonuses(now) }
            // Recommended on tags the library barely backs: the surprises, kept apart.
            .map { item -> item.copy(explore = item.because.isNotEmpty() && item.because.all { (profile.support[it] ?: 0) <= Recommended.EXPLORE_SUPPORT }) }
            .sortedByDescending { it.score }
            .withExploreSlots()

    /**
     * AniList's popularity and mean score for the shortlist, by title, [POPULARITY_BATCH] titles
     * per request through aliased fields. Only a close title match counts: a wrong series' fame
     * is worse than none.
     */
    private suspend fun popularityOf(items: List<Recommended>): Map<String, Pair<Int, Int?>> {
        val titles = items.map { it.title }.distinctBy { it.key() }
        val found = mutableMapOf<String, Pair<Int, Int?>>()
        titles.chunked(POPULARITY_BATCH).forEach { batch ->
            runCatching {
                val fields = batch.indices.joinToString("\n") { i ->
                    "m$i: Media(search: \$q$i, type: MANGA, format_not_in: [NOVEL]) { title { romaji english } popularity averageScore }"
                }
                val query = "query(" + batch.indices.joinToString(", ") { "\$q$it: String" } + ") { $fields }"
                val payload = buildJsonObject {
                    put("query", query)
                    putJsonObject("variables") { batch.forEachIndexed { i, title -> put("q$i", title) } }
                }
                val data = network.client.newCall(POST(ANILIST, body = payload.toString().toRequestBody(jsonMime)))
                    .awaitSuccess()
                    .parseAs<JsonObject>()["data"]?.jsonObject ?: return@runCatching
                batch.forEachIndexed { i, title ->
                    val media = data["m$i"]?.takeIf { it !is JsonNull }?.jsonObject ?: return@forEachIndexed
                    val names = media["title"]?.jsonObject
                    val close = listOfNotNull(names?.get("romaji"), names?.get("english"))
                        .mapNotNull { it.jsonPrimitive.contentOrNull }
                        .any { normalizedLevenshteinSimilarity(it.key(), title.key()) >= POPULARITY_MATCH }
                    if (!close) return@forEachIndexed
                    val pop = media["popularity"]?.jsonPrimitive?.intOrNull ?: return@forEachIndexed
                    found[title.key()] = pop to media["averageScore"]?.jsonPrimitive?.intOrNull
                }
            }.onFailure { Logger.w(it) { "AniList popularity batch failed" } }
        }
        return found
    }

    /** The catalogues and the trackers' "like this", located in the sources the reader reads from. */
    private suspend fun suggestions(
        profile: TasteProfile,
        library: List<LibraryManga>,
        onProgress: suspend (BuildProgress) -> Unit,
    ): List<Recommended> {
        // Every enabled source the library actually uses, most used first: the lookup stops at
        // the first that has the series, so the order is what keeps the cost down, and the
        // breadth is what lets a big target be met.
        val enabled = enabledSources().associateBy { it.id }
        val mostRead = library.groupingBy { it.manga.source }.eachCount().entries
            .sortedByDescending { it.value }
            .mapNotNull { enabled[it.key] }
        val languages = preferences.enabledLanguages().get().toList()
        val external = ExternalSuggestions(network, languages)
        val similar = external.await(profile.seeds.map { it.title }, mostRead) { done, total ->
            onProgress(BuildProgress(BuildProgress.Stage.TRACKERS, done, total))
        }
        val catalog = runCatching { external.catalog(profile, countryFor(profile), preferences.recommendationRecentYears().get()) }
            .onFailure { Logger.w(it) { "AniList catalogue pass failed" } }
            .getOrDefault(emptyList())
        val located = external.locate(catalog, mostRead) { done, total ->
            onProgress(BuildProgress(BuildProgress.Stage.TRACKERS, done, total))
        }
        return (located + similar)
            .map {
                Recommended(
                    sourceId = it.sourceId,
                    url = it.url,
                    title = it.title,
                    thumbnail = it.thumbnail,
                    tags = it.suggestion.genres,
                    score = 0f,
                    because = emptyList(),
                    via = it.suggestion.seed.takeUnless { seed -> it.suggestion.fromCatalog },
                    votes = it.suggestion.votes,
                    popularity = it.suggestion.popularity,
                    averageScore = it.suggestion.averageScore,
                    fromCatalog = it.suggestion.fromCatalog,
                )
            }
            .distinctBy { it.title.key() }
    }

    /**
     * One search per enabled source per tag the reader is at least [TAG_MIN_WEIGHT] into, sources
     * in parallel, tags in series per source. Every tag, not a top few: the point of a periodic
     * build is that it can afford to be thorough. Deeper where the taste is stronger
     * ([pagesFor]): page one of a tag at 15%, pages one to three of a tag at 100%.
     */
    private suspend fun searchSources(profile: TasteProfile, onProgress: suspend (BuildProgress) -> Unit): List<Recommended> {
        val liked = profile.tags.filterValues { it >= TAG_MIN_WEIGHT }.entries.take(TAG_CAP).associate { it.key to it.value }
        if (liked.isEmpty()) return emptyList()
        val sources = enabledSources()
        val searched = AtomicInteger()
        onProgress(BuildProgress(BuildProgress.Stage.SEARCHING, 0, sources.size))
        val hits = coroutineScope {
            sources.map { source ->
                async {
                    runCatching { searchSource(source, liked) }.getOrDefault(emptyList())
                        .also { onProgress(BuildProgress(BuildProgress.Stage.SEARCHING, searched.incrementAndGet(), sources.size)) }
                }
            }.awaitAll()
        }.flatten()

        // The same manga found under two tags is one candidate carrying both.
        return hits.groupBy { (hit, _) -> hit.sourceId to hit.url }
            .values
            .map { found -> found.first().first.copy(tags = found.map { (_, tag) -> tag }.distinct()) }
    }

    /** Deeper where the taste is stronger. */
    private fun pagesFor(weight: Float): Int = when {
        weight >= 0.8f -> 3
        weight >= 0.5f -> 2
        else -> 1
    }

    private suspend fun searchSource(source: CatalogueSource, liked: Map<String, Float>): List<Pair<Recommended, String>> {
        val out = mutableListOf<Pair<Recommended, String>>()
        for ((tag, weight) in liked) {
            for (page in 1..pagesFor(weight)) {
                val filters = source.getFilterList()
                if (!filters.select(tag)) break
                val result = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                    runCatching { source.getSearchManga(page, "", filters) }
                        .onFailure { Logger.w(it) { "Recommendation search failed on ${source.name} for $tag" } }
                        .getOrNull()
                } ?: break
                result.mangas.forEach {
                    out += Recommended(source.id, it.url, it.title, it.thumbnail_url, listOf(tag), 0f, emptyList(), foundByTag = true) to tag
                }
                if (!result.hasNextPage) break
            }
        }
        return out
    }

    /**
     * Where the reader's reading comes from, as AniList spells it, when it is lopsided enough to
     * say: a library that is mostly manhwa wants Korean results. Balanced tastes get no filter.
     */
    private fun countryFor(profile: TasteProfile): String? {
        val formats = profile.formats.entries.sortedByDescending { it.value }
        val top = formats.firstOrNull() ?: return null
        val second = formats.getOrNull(1)?.value ?: 0f
        if (top.value < second * COUNTRY_DOMINANCE) return null
        return when (top.key.lowercase()) {
            "manhwa", "webtoon" -> "KR"
            "manhua" -> "CN"
            "manga" -> "JP"
            else -> null
        }
    }

    private fun enabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hidden = preferences.hiddenSources().get()
        return sourceManager.getCatalogueSources().filter {
            it.lang in languages && it.id.toString() !in hidden && it.id != LocalSource.ID && it.id != RecommendationSource.ID
        }
    }

    /**
     * Reads details and chapters for a batch: full tags to rank on, chapter count and dates to
     * tell a new release from an old series. Sources in parallel, one entry at a time within a
     * source. An entry that fails stays as it was, unfetched, and is dropped by the caller.
     */
    private suspend fun fetchDetails(
        batch: List<Recommended>,
        onProgress: suspend (BuildProgress) -> Unit,
    ): List<Recommended> = coroutineScope {
        val detailed = AtomicInteger()
        onProgress(BuildProgress(BuildProgress.Stage.DETAILS, 0, batch.size))
        batch.groupBy { it.sourceId }.map { (sourceId, entries) ->
            async {
                val source = sourceManager.get(sourceId) as? CatalogueSource ?: return@async entries
                entries.map { entry ->
                    onProgress(BuildProgress(BuildProgress.Stage.DETAILS, detailed.incrementAndGet(), batch.size))
                    runCatching {
                        val update = withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                            source.getMangaUpdate(entry.toSManga(), emptyList(), fetchDetails = true, fetchChapters = true)
                        } ?: return@runCatching entry
                        val genres = update.manga.getGenres().orEmpty()
                        val raw = update.chapters.map { it.date_upload }
                        val dates = honestUploadDates(raw)
                        val names = update.chapters.map { it.name }
                        entry.copy(
                            datesUntrusted = uploadDatesUntrusted(raw),
                            oneShotChapters = names.isNotEmpty() && names.count { Recommended.ONE_SHOT.containsMatchIn(it) } * 2 >= names.size,
                            title = update.manga.title.ifBlank { entry.title },
                            thumbnail = update.manga.thumbnail_url ?: entry.thumbnail,
                            // AniList's genres and the source's: more is known, not less.
                            tags = (entry.takeIf { it.via != null }?.tags.orEmpty() + genres).ifEmpty { entry.tags },
                            chapters = update.chapters.size,
                            firstUpload = dates.minOrNull(),
                            lastUpload = dates.maxOrNull(),
                            completed = update.manga.status == SManga.COMPLETED || update.manga.status == SManga.PUBLISHING_FINISHED,
                            fetched = true,
                        )
                    }.onFailure { Logger.w(it) { "Recommendation details failed for ${entry.title} on ${source.name}" } }
                        .getOrDefault(entry)
                }
            }
        }.awaitAll().flatten()
    }

    private suspend fun LibraryManga.toRecommended(): Recommended {
        val raw = getChapter.awaitAll(manga.id!!, false).map { it.date_upload }
        val dates = honestUploadDates(raw)
        return Recommended(
            datesUntrusted = uploadDatesUntrusted(raw),
            sourceId = manga.source,
            url = manga.url,
            title = manga.title,
            thumbnail = manga.thumbnail_url,
            tags = manga.getOriginalGenres().orEmpty(),
            score = 0f,
            because = emptyList(),
            chapters = totalChapters,
            firstUpload = dates.minOrNull(),
            lastUpload = dates.maxOrNull(),
            completed = manga.status == SManga.COMPLETED || manga.status == SManga.PUBLISHING_FINISHED,
            fromLibrary = true,
            fetched = true,
        )
    }

    private fun Recommended.toSManga() = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail
    }

    /**
     * Turns on the option that spells [tag] the way this source does, or returns false when the
     * source has no such option. Sources name genres in their own language, so the canonical
     * name is tried first and an approximate match is allowed after; "Status: Completed" never
     * comes close to a genre.
     */
    private fun FilterList.select(tag: String): Boolean {
        fun like(name: String) = canonicalTag(name).equals(tag, true) ||
            normalizedLevenshteinSimilarity(name.lowercase(), tag.lowercase()) >= TAG_MATCH
        for (filter in this) {
            when (filter) {
                is Filter.Group<*> -> for (item in filter.state) {
                    when {
                        item is Filter.CheckBox && like(item.name) -> return true.also { item.state = true }
                        item is Filter.TriState && like(item.name) -> return true.also { item.state = Filter.TriState.STATE_INCLUDE }
                    }
                }
                is Filter.Select<*> -> filter.values.indexOfFirst { like(it.toString()) }
                    .takeIf { it >= 0 }?.let { filter.state = it; return true }
                else -> {}
            }
        }
        return false
    }

    companion object {
        /** A tag with at least this much of the top tag's weight is searched for. */
        private const val TAG_MIN_WEIGHT = 0.15f

        /** A sanity ceiling on tags searched, however wide the profile. */
        private const val TAG_CAP = 60


        /** Series read per batch. A batch is one unit of pause, progress and persistence. */
        const val BATCH = 50

        /** The top format must outweigh the next this many times before results are kept to its country. */
        private const val COUNTRY_DOMINANCE = 2f

        /**
         * How many sources' copies of one series are detailed, so the one with the most chapters
         * can be picked. Requests per build ≤ [SHORTLIST] × this, spread over sources.
         */
        private const val COPIES = 2

        /** How close a source's option name must be to a tag to count as the same genre. */
        private const val TAG_MATCH = 0.85

        /** One request's allowance. A source that hangs costs this, not the build. */
        private const val REQUEST_TIMEOUT_MS = 30_000L

        private const val ANILIST = "https://graphql.anilist.co/"

        /** Aliased searches per GraphQL request. AniList's complexity limit allows a few dozen. */
        private const val POPULARITY_BATCH = 25

        /** How alike the found title must be to the candidate's before its numbers are trusted. */
        private const val POPULARITY_MATCH = 0.75

        // ponytail: exact key after stripping case and punctuation; Levenshtein against the whole
        // library is the upgrade if "Solo Leveling" and "Solo Leveling (Official)" keep coexisting.
        fun String.key() = lowercase().filter { it.isLetterOrDigit() }
    }
}
