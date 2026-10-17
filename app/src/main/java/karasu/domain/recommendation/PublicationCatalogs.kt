package karasu.domain.recommendation

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.library.CustomMangaManager
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.data.database.models.seriesType
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.track.interactor.GetTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.Headers
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Calendar

/**
 * Publication status, names and tags from the two catalogues that actually keep status: MangaDex
 * first, MangaUpdates second. A source scrapes its status once and almost never says hiatus or
 * cancelled; AniList had Hunter x Hunter as releasing, and Kitsu has no word for either. Both of
 * these are public and need no account.
 *
 * Found by id when anything already known links to the series — a bound tracker, an earlier
 * match, MangaDex's own links to MangaUpdates — and otherwise only by an exact name, see
 * [matchCatalog]. A search's first result is often not the series: "Solo Leveling" finds the
 * Ragnarok sequel first.
 *
 * What is found goes into [TrackerExtra] ([TrackerExtra.catalogStatus] and the rest). The status
 * goes on to the library right away ([applyTrackerStatus]); the tags only join the genres when
 * asked ([applyGenres]), so a library update can refresh status without editing anyone's genres.
 */
class CheckPublication(
    private val getLibraryManga: GetLibraryManga,
    private val getTrack: GetTrack,
    private val network: NetworkHelper,
    private val store: TrackerExtrasStore,
    private val customMangaManager: CustomMangaManager,
    private val getChapter: GetChapter,
) {
    /**
     * Checks the entries in [entries] (the whole library when null) not checked for a week, the
     * most recently read first, until [budgetMs] runs out; the rest wait for the next run.
     *
     * ponytail: a week per entry and a time budget per run, because a library update cannot pay
     * two to five requests for every entry every time. Status rarely changes faster than that.
     */
    suspend fun await(
        entries: List<LibraryManga>? = null,
        now: Long = System.currentTimeMillis(),
        budgetMs: Long = BUDGET_MS,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> },
    ) {
        val deadline = now + budgetMs
        val pending = (entries ?: getLibraryManga.await())
            .distinctBy { it.manga.id }
            .filter { it.manga.favorite && (store.get(it.manga.id)?.catalogAt ?: 0L) < now - REFRESH_MS }
            .sortedByDescending { it.lastRead }
            .map { it.manga }
        if (pending.isEmpty()) return
        onProgress(0, pending.size)
        for ((index, manga) in pending.withIndex()) {
            if (System.currentTimeMillis() > deadline) break
            // A failed request is not a miss: left to be asked again next run. A cancelled
            // update is not a failed request: it stops here rather than spend the budget.
            try {
                check(manga, now)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.w(e) { "Publication lookup failed for ${manga.title}" }
            }
            onProgress(index + 1, pending.size)
        }
    }

    /** Looks one entry up now, whatever its age; for binding a tracker. False when nothing was found. */
    suspend fun check(manga: Manga, now: Long = System.currentTimeMillis()): Boolean {
        val id = manga.id ?: return false
        val before = store.get(id)
        val tracks = getTrack.awaitAllByMangaId(id)
        val bound = boundIds(tracks)
        val known = learnedIds(before) + bound
        // The source's own title first: a pt-BR source's name is exactly what MangaDex's pt-br
        // alt titles are for, and it is also how the reader knows the series. Not the names an
        // earlier lookup found: a wrong match would otherwise keep finding itself by name.
        val names = (listOf(manga.title) + tracks.map { it.title } + before?.titles.orEmpty() + before?.mbTitles.orEmpty())
            .map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
        val hints = MatchHints(type = typeHint(manga), firstYear = firstYear(id))

        val (mangaDex, deadId) = findOnMangaDex(known, bound, names, hints)
        val muId = known[ExternalIds.MANGA_UPDATES] ?: mangaDex?.ids?.get(ExternalIds.MANGA_UPDATES)
        val mangaUpdates = if (muId != null) {
            fetchMangaUpdates(muId)
        } else {
            findOnMangaUpdates(names, hints)
        }

        val found = listOfNotNull(mangaDex, mangaUpdates)
        // Over what is stored now, and stored before the status is applied (see applyTrackerStatus).
        val (stored, after) = store.update(id) { current ->
            current.copy(
                ids = mergeIds(current.ids, found.fold(emptyMap()) { acc, it -> acc + it.ids }, bound, dropped = setOfNotNull(deadId)),
                catalogStatus = catalogStatus(mangaDex?.status, mangaUpdates?.status),
                catalogTitles = found.flatMap { it.titles }.distinctBy { it.lowercase() }.take(MAX_TITLES),
                catalogTags = found.flatMap { it.tags }.mapNotNull(::normalizeTag).distinctBy { it.lowercase() },
                catalogAt = now,
            )
        }
        applyTrackerStatus(customMangaManager, manga, stored?.publicationStatus, after.publicationStatus)
        return found.isNotEmpty()
    }

    /**
     * Writes what the catalogues tagged an entry with into its genres, for every entry that has
     * any. Nothing is fetched: run where genre edits are expected, the overnight build.
     */
    suspend fun applyGenres() {
        val extras = store.all()
        getLibraryManga.await().distinctBy { it.manga.id }.forEach { entry ->
            val tags = extras[entry.manga.id]?.catalogTags.orEmpty()
            if (tags.isNotEmpty()) runCatching { addToGenres(customMangaManager, entry.manga, tags) }
        }
    }

    /** The ids of the trackers the reader bound: their own word on which series it is. */
    private fun boundIds(tracks: List<Track>): Map<String, String> = tracks.mapNotNull { track ->
        when (track.sync_id) {
            TrackManager.ANILIST -> ExternalIds.ANILIST
            TrackManager.MYANIMELIST -> ExternalIds.MAL
            TrackManager.KITSU -> ExternalIds.KITSU
            TrackManager.MANGA_UPDATES -> ExternalIds.MANGA_UPDATES
            else -> null
        }?.let { it to track.media_id.toString() }
    }.toMap()

    /** The ids earlier lookups found. MangaBaka files MangaUpdates ids in either base; decimal is what the API takes. */
    private fun learnedIds(before: TrackerExtra?): Map<String, String> = before?.ids.orEmpty().mapValues { (key, value) ->
        if (key == ExternalIds.MANGA_UPDATES) (value.toLongOrNull() ?: value.toLongOrNull(36))?.toString() ?: value else value
    }

    /**
     * What kind of comic the source says it is, only when it says: [seriesType] falls back to
     * manga for an entry with no tags at all, and that is no evidence against a manhwa.
     */
    private fun typeHint(manga: Manga): String? = when (manga.seriesType()) {
        Manga.TYPE_MANHWA, Manga.TYPE_WEBTOON -> "manhwa"
        Manga.TYPE_MANHUA -> "manhua"
        Manga.TYPE_MANGA -> "manga".takeIf { manga.genre.orEmpty().contains(Regex("(?i)\\bmanga\\b")) }
        else -> null
    }

    /** The year of the entry's oldest chapter: a series that started after it cannot be this one. */
    private suspend fun firstYear(mangaId: Long): Int? = getChapter.awaitAll(mangaId, false)
        .mapNotNull { chapter -> chapter.date_upload.takeIf { it > 0 } ?: chapter.date_fetch.takeIf { it > 0 } }
        .minOrNull()
        ?.let { Calendar.getInstance().apply { timeInMillis = it }.get(Calendar.YEAR) }

    /**
     * The series on MangaDex, and the key of an id that turned out wrong — gone (404) or
     * contradicting a bound tracker — so it is dropped rather than asked about every week.
     */
    private suspend fun findOnMangaDex(
        known: Map<String, String>,
        bound: Map<String, String>,
        names: List<String>,
        hints: MatchHints,
    ): Pair<CatalogRecord?, String?> {
        var dead: String? = null
        known[ExternalIds.MANGADEX]?.let { id ->
            val root = try {
                mangaDex("$MANGADEX/manga/$id")
            } catch (e: HttpException) {
                if (e.code != HTTP_NOT_FOUND) throw e
                null
            }
            val record = (root?.get("data") as? JsonObject)?.let(::parseMangaDexManga)
            if (record != null && agreesWith(record, bound)) return record to null
            dead = ExternalIds.MANGADEX
        }
        // Searched by the trackers' ids when there are any: an id an earlier name match learned
        // never outranks them.
        val matchBy = bound.ifEmpty { known - setOfNotNull(dead) }
        for (name in names.take(MAX_NAME_SEARCHES)) {
            val url = "$MANGADEX/manga?limit=$SEARCH_LIMIT&title=" + URLEncoder.encode(name.take(MAX_QUERY), "UTF-8")
            val candidates = mangaDex(url)?.get("data").array().mapNotNull { (it as? JsonObject)?.let(::parseMangaDexManga) }
            matchCatalog(candidates, matchBy, names, hints)?.let { return it to dead }
        }
        return null to dead
    }

    private suspend fun mangaDex(url: String): JsonObject? {
        delay(MANGADEX_SPACING_MS)
        return network.client.newCall(GET(url, userAgent)).awaitSuccess().parseAs<JsonObject>()
    }

    private suspend fun findOnMangaUpdates(names: List<String>, hints: MatchHints): CatalogRecord? {
        for (name in names.take(MAX_NAME_SEARCHES)) {
            delay(MANGA_UPDATES_SPACING_MS)
            val body = buildJsonObject {
                put("search", name.take(MAX_QUERY))
                put("perpage", SEARCH_LIMIT)
            }
            val results = network.client
                .newCall(POST("$MANGA_UPDATES/v1/series/search", userAgent, body.toString().toRequestBody(jsonMime)))
                .awaitSuccess()
                .parseAs<JsonObject>()["results"].array()
                .mapNotNull { (it as? JsonObject)?.let(::parseMangaUpdatesHit) }
            val hit = matchCatalog(results, emptyMap(), names, hints) ?: continue
            return fetchMangaUpdates(hit.ids.getValue(ExternalIds.MANGA_UPDATES))
        }
        return null
    }

    private suspend fun fetchMangaUpdates(id: String): CatalogRecord? {
        delay(MANGA_UPDATES_SPACING_MS)
        return network.client.newCall(GET("$MANGA_UPDATES/v1/series/$id", userAgent))
            .awaitSuccess()
            .parseAs<JsonObject>()
            .let(::parseMangaUpdatesSeries)
    }

    /** MangaDex asks for a User-Agent that names the client rather than a browser's. */
    private val userAgent = Headers.headersOf("User-Agent", "clevim/Karasu/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID})")

    companion object {
        private const val MANGADEX = "https://api.mangadex.org"
        private const val HTTP_NOT_FOUND = 404
        private const val MANGA_UPDATES = "https://api.mangaupdates.com"
        private const val SEARCH_LIMIT = 10
        private const val MAX_QUERY = 120

        /** The source's title and two more: past that the names stop finding anything new. */
        private const val MAX_NAME_SEARCHES = 3
        private const val MAX_TITLES = 25

        /** MangaDex allows about five requests a second; MangaUpdates states no limit, so gently. */
        private const val MANGADEX_SPACING_MS = 250L
        private const val MANGA_UPDATES_SPACING_MS = 500L

        /** What a library update spends: a couple of dozen entries, the rest next time. */
        const val LIBRARY_UPDATE_BUDGET_MS = 2L * 60 * 1000
        private const val BUDGET_MS = 10L * 60 * 1000
        private const val REFRESH_MS = 7L * 24 * 60 * 60 * 1000
    }
}

/** Whether a record agrees with every bound tracker it also links to. */
internal fun agreesWith(record: CatalogRecord, bound: Map<String, String>): Boolean =
    bound.none { (key, value) -> record.ids[key].let { it != null && it != value } }

/**
 * The ids to keep: what was stored, less what turned out wrong, then what was just found, then the
 * bound trackers' over everything — they are the reader's own word on which series it is.
 */
internal fun mergeIds(stored: Map<String, String>, found: Map<String, String>, bound: Map<String, String>, dropped: Set<String>): Map<String, String> =
    (stored - dropped) + found + bound

/** One series as MangaDex or MangaUpdates describes it. */
data class CatalogRecord(
    /** Its own id and the ones it links to elsewhere, by [ExternalIds] name. */
    val ids: Map<String, String>,
    /** English and romanised names first: the ones the trackers are searched by. */
    val titles: List<String>,
    val tags: List<String>,
    val status: Int?,
    /** "manga", "manhwa" or "manhua" when known. */
    val type: String? = null,
    /** The year it started, when known. */
    val year: Int? = null,
)

/** What is known about the entry besides its names, to tell same-named series apart. */
data class MatchHints(
    /** "manga", "manhwa" or "manhua", when the source says. */
    val type: String? = null,
    /** The year of its oldest chapter. */
    val firstYear: Int? = null,
)

/**
 * The candidate that is the series an entry with [names] and ids [known] is, or null when that is
 * not certain. One that links to an id already known is it. Otherwise one whose name is one of
 * [names], ignoring case, accents, punctuation and a qualifier at the end — catalogues tell
 * same-named series apart with "Howling (WANKE)" or "(Novel)", and a source just says "Howling".
 *
 * Same-named series are then told apart by what else is known ([hints]): a different kind of
 * comic, a novel, or a start after the entry's first chapter rules a candidate out; among those
 * left, one that started the year the entry's chapters did wins. If more than one is still left,
 * nothing is: guessing would label the wrong series.
 */
internal fun matchCatalog(
    candidates: List<CatalogRecord>,
    known: Map<String, String>,
    names: List<String>,
    hints: MatchHints = MatchHints(),
): CatalogRecord? {
    candidates.filter { candidate -> candidate.ids.any { (key, value) -> known[key] == value } }
        .singleOrNull()?.let { return it }
    val keys = names.map { it.titleKey() }.filter { it.isNotEmpty() }.toSet()
    return candidates
        .filter { candidate -> candidate.titles.any { it.titleKey() in keys } }
        .filterNot { candidate -> candidate.titles.any { it.titleKey() in keys && it.qualifier()?.contains("novel", true) == true } }
        .filter { candidate -> hints.type == null || candidate.type == null || candidate.type == hints.type }
        .filter { candidate -> hints.firstYear == null || candidate.year == null || candidate.year <= hints.firstYear + YEAR_SLACK }
        .let { left ->
            left.singleOrNull()
                // Still several: the one that started when the entry's chapters did, if only one did.
                // A source picking up a new series is the usual case; an old one is left alone.
                ?: hints.firstYear?.let { first -> left.filter { it.year != null && it.year in first - YEAR_SLACK..first + YEAR_SLACK } }
                    ?.singleOrNull()
        }
}

/** A release can be dated a little before its catalogue year (previews, a late-December start). */
private const val YEAR_SLACK = 1

/** "Howling (WANKE)" → "WANKE": the qualifier a catalogue added to tell a name apart, if any. */
internal fun String.qualifier(): String? = QUALIFIER.find(trim())?.groupValues?.get(1)?.trim()

// After something: a name that is all brackets, like "[Oshi no Ko]", is the name itself.
private val QUALIFIER = Regex("""(?<=\S)\s*[(\[]([^()\[\]]+)[)\]]$""")

/** "manga", "manhwa" or "manhua" for a catalogue's own word or a language code; null otherwise. */
internal fun comicType(word: String?): String? = when (word?.lowercase()) {
    "manga", "ja" -> "manga"
    "manhwa", "ko" -> "manhwa"
    "manhua", "zh", "zh-hk", "zh-tw" -> "manhua"
    else -> null
}

/**
 * MangaDex's word, unless it is "ongoing" and MangaUpdates says otherwise. MangaDex is the main
 * source: an editor sets one of exactly four states. But "ongoing" is also what every entry is
 * created with, and stays at when nobody comes back to it, so there MangaUpdates — whose status
 * is written by hand with each release — is the better witness of a hiatus or an ending.
 */
internal fun catalogStatus(mangaDex: Int?, mangaUpdates: Int?): Int? = when {
    mangaDex == null -> mangaUpdates
    mangaDex == SManga.ONGOING && mangaUpdates != null -> mangaUpdates
    else -> mangaDex
}

/**
 * MangaUpdates' status is prose: "39 Volumes + Volume 0 (Hiatus)", or a line per edition and
 * side story ("200 Chapters (Complete)\n15 Volumes (Complete)\n\nPrologue: 1 Chapter"). The first
 * state in parentheses is the original's; with none, the first line is read whole. Its
 * `completed` flag is not this: it means the translation is complete.
 */
internal fun mangaUpdatesStatusOf(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    // Parentheses also wrap the links in its notes ("*[MORI Kouji](https://…)"), which say nothing.
    return Regex("""\(([^)]*)\)""").findAll(text).firstNotNullOfOrNull { publicationState(it.groupValues[1]) }
        ?: publicationState(text.trim().lineSequence().first())
}

/**
 * A status word, matched as a whole word: "ended" is not in "suspended", "extended" or
 * "intended". A suspended serialisation is on hiatus; dropped, axed or discontinued is cancelled.
 */
private fun publicationState(text: String): Int? {
    val words = text.lowercase().split(Regex("[^\\p{L}]+")).toSet()
    return when {
        words.any { it in HIATUS_WORDS } -> SManga.ON_HIATUS
        words.any { it in CANCELLED_WORDS } -> SManga.CANCELLED
        words.any { it in COMPLETED_WORDS } -> SManga.COMPLETED
        "ongoing" in words -> SManga.ONGOING
        else -> null
    }
}

private val HIATUS_WORDS = setOf("hiatus", "suspended", "paused")
private val CANCELLED_WORDS = setOf("cancelled", "canceled", "cancel", "discontinued", "dropped", "axed", "abandoned")
private val COMPLETED_WORDS = setOf("complete", "completed", "finished", "ended")

internal fun parseMangaDexManga(obj: JsonObject): CatalogRecord? {
    val id = obj["id"].text() ?: return null
    val attributes = obj["attributes"] as? JsonObject ?: return null
    val original = attributes["originalLanguage"].text()
    val named = ((attributes["title"] as? JsonObject)?.entries.orEmpty() + attributes["altTitles"].array().flatMap { (it as? JsonObject)?.entries.orEmpty() })
        .mapNotNull { (language, title) -> title.text()?.trim()?.let { language to it } }
    val titles = named
        .mapNotNull { (language, title) -> titleRank(language, original, title)?.let { it to title } }
        .sortedBy { it.first }
        .map { it.second }
        .distinctBy { it.lowercase() }
    val links = attributes["links"] as? JsonObject
    val ids = buildMap {
        put(ExternalIds.MANGADEX, id)
        links?.get("al").text()?.let { put(ExternalIds.ANILIST, it) }
        links?.get("mal").text()?.let { put(ExternalIds.MAL, it) }
        links?.get("kt").text()?.takeIf { slug -> slug.all { it.isDigit() } }?.let { put(ExternalIds.KITSU, it) }
        // The current slug is base 36 of the id the API takes; an all-digit one is a retired id.
        links?.get("mu").text()?.takeIf { slug -> slug.any { it.isLetter() } }?.toLongOrNull(36)
            ?.let { put(ExternalIds.MANGA_UPDATES, it.toString()) }
    }
    // Format tags say how it is printed (long strip, full colour), not what it is about.
    val tags = attributes["tags"].array().mapNotNull { it as? JsonObject }
        .mapNotNull { it["attributes"] as? JsonObject }
        .filter { it["group"].text() != "format" }
        .mapNotNull { (it["name"] as? JsonObject)?.get("en").text() }
    val status = when (attributes["status"].text()) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.COMPLETED
        "hiatus" -> SManga.ON_HIATUS
        "cancelled" -> SManga.CANCELLED
        else -> null
    }
    return CatalogRecord(ids, titles, tags, status, type = comicType(original), year = attributes["year"].int())
}

/** A search result: enough to match on, the series itself is fetched once matched. */
internal fun parseMangaUpdatesHit(obj: JsonObject): CatalogRecord? {
    val record = obj["record"] as? JsonObject ?: return null
    val id = record["series_id"].text() ?: return null
    // A novel shares its comic's name exactly and would make every match ambiguous.
    if (record["type"].text().equals("novel", ignoreCase = true)) return null
    return CatalogRecord(
        ids = mapOf(ExternalIds.MANGA_UPDATES to id),
        titles = listOfNotNull(record["title"].text(), obj["hit_title"].text()),
        tags = emptyList(),
        status = null,
        type = comicType(record["type"].text()),
        year = record["year"].text()?.take(4)?.toIntOrNull(),
    )
}

internal fun parseMangaUpdatesSeries(obj: JsonObject): CatalogRecord? {
    val id = obj["series_id"].text() ?: return null
    val titles = (listOfNotNull(obj["title"].text()) + obj["associated"].array().mapNotNull { (it as? JsonObject)?.get("title").text() })
        .map { it.trim() }
        // The other scripts are MangaDex's to give, with their language attached.
        .filter { it.isLatin() }
        .distinctBy { it.lowercase() }
    val genres = obj["genres"].array().mapNotNull { (it as? JsonObject)?.get("genre").text() }
    // Categories are reader-voted, dozens per series and mostly noise: only the ones enough
    // readers agree on, and that few voted against.
    val categories = obj["categories"].array().mapNotNull { it as? JsonObject }
        .filter { category ->
            val plus = category["votes_plus"].int() ?: 0
            val minus = category["votes_minus"].int() ?: 0
            (category["votes"].int() ?: 0) >= MU_MIN_VOTES && minus * 3 < plus
        }
        .sortedByDescending { it["votes"].int() ?: 0 }
        .take(MU_CATEGORIES)
        .mapNotNull { it["category"].text() }
    return CatalogRecord(
        ids = mapOf(ExternalIds.MANGA_UPDATES to id),
        titles = titles,
        tags = genres + categories,
        status = mangaUpdatesStatusOf(obj["status"].text()),
        type = comicType(obj["type"].text()),
        year = obj["year"].text()?.take(4)?.toIntOrNull(),
    )
}

/**
 * The order a MangaDex name is offered in, or null to drop it: English, then romanised, then the
 * original script, then Portuguese, then any other Latin-script name. Other scripts in other
 * languages are no use to a search in the reader's languages.
 */
private fun titleRank(language: String, original: String?, title: String): Int? = when {
    language == "en" -> 0
    language.endsWith("-ro") -> 1
    language == original -> 2
    language == "pt-br" || language == "pt" -> 3
    title.isLatin() -> 4
    else -> null
}

/**
 * Lowercase letters and digits, accents and an ending qualifier dropped: "Upando Sozinho!",
 * "upando sozinho" and "Upando Sozinho (Novel)" are one name — see [qualifier] for the last.
 */
internal fun String.titleKey(): String = Normalizer.normalize(trim().replace(QUALIFIER, ""), Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase()
    .filter { it.isLetterOrDigit() }

/** Whether most of the letters are Latin: a name the reader's sources can be searched by. */
internal fun String.isLatin(): Boolean {
    val letters = filter { it.isLetter() }
    return letters.isNotEmpty() && letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN } * 2 > letters.length
}

/** JSON helpers shared by the catalogue parsers here and in `karasu.domain.releases`. */
internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
internal fun JsonElement?.array(): List<JsonElement> = (this as? JsonArray).orEmpty()

private const val MU_MIN_VOTES = 10
private const val MU_CATEGORIES = 8
