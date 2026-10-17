package karasu.domain.releases

import eu.kanade.tachiyomi.source.model.SManga
import karasu.domain.recommendation.array
import karasu.domain.recommendation.int
import karasu.domain.recommendation.isLatin
import karasu.domain.recommendation.text
import karasu.domain.recommendation.titleKey
import karasu.domain.recommendation.trackerStatusOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.util.Calendar

/**
 * One series that started in the last few months, as the "Releases" window lists it. Not a manga
 * of any source: it has no chapters, only what a catalogue says about it, and [names] is what the
 * reader's own sources are searched by.
 */
@Serializable
data class Release(
    /** [ORIGIN_ANILIST] or [ORIGIN_MANGADEX]: the catalogue [id] belongs to. */
    val origin: String,
    val id: String,
    val title: String,
    /** Every name, [title] first. */
    val names: List<String>,
    val cover: String? = null,
    /** JP, KR, CN or TW, as AniList spells a country; MangaDex's languages are mapped to these. */
    val country: String? = null,
    /** An [SManga] status, or null when the catalogue has none yet. */
    val status: Int? = null,
    /** When it started, as yyyymmdd; a missing day or month counts as the first. */
    val start: Int? = null,
    val genres: List<String> = emptyList(),
    val popularity: Int? = null,
    /** 0–100. */
    val score: Int? = null,
    val url: String,
) {
    /** "manga", "manhwa" or "manhua". */
    val type: String? get() = typeOf(country)
}

/** The last list fetched, on disk. Never two catalogues mixed: [origin] says which one this is. */
@Serializable
data class ReleasesSnapshot(
    val origin: String,
    val fetchedAt: Long,
    val items: List<Release>,
)

const val ORIGIN_ANILIST = "anilist"
const val ORIGIN_MANGADEX = "mangadex"

const val TYPE_MANGA = "manga"
const val TYPE_MANHWA = "manhwa"
const val TYPE_MANHUA = "manhua"

/** The window always covers this many months; the period filter narrows it locally. */
const val RELEASE_WINDOW_MONTHS = 6

fun typeOf(country: String?): String? = when (country?.uppercase()) {
    "JP" -> TYPE_MANGA
    "KR" -> TYPE_MANHWA
    "CN", "TW", "HK" -> TYPE_MANHUA
    else -> null
}

/** [now] minus [months], as yyyymmdd. */
fun monthsAgo(now: Long, months: Int): Int {
    val calendar = Calendar.getInstance().apply {
        timeInMillis = now
        add(Calendar.MONTH, -months)
    }
    return calendar.get(Calendar.YEAR) * 10_000 + (calendar.get(Calendar.MONTH) + 1) * 100 + calendar.get(Calendar.DAY_OF_MONTH)
}

/** What the window shows for a set of filters. Everything here is local: no filter fetches. */
data class ReleaseFilters(
    val months: Int = RELEASE_WINDOW_MONTHS,
    /** Empty for every type. */
    val types: Set<String> = emptySet(),
    /** Every one must be on the series. */
    val genres: Set<String> = emptySet(),
    /** Empty for every status. */
    val statuses: Set<Int> = emptySet(),
    val query: String = "",
)

internal fun filterReleases(
    items: List<Release>,
    filters: ReleaseFilters,
    now: Long,
    inLibrary: (Release) -> Boolean = { false },
): List<Release> {
    val since = monthsAgo(now, filters.months)
    val wantedGenres = filters.genres.map { it.lowercase() }
    return items.filter { item ->
        (item.start ?: 0) >= since &&
            (filters.types.isEmpty() || item.type in filters.types) &&
            (filters.statuses.isEmpty() || item.status in filters.statuses) &&
            item.genres.map { it.lowercase() }.containsAll(wantedGenres) &&
            (filters.query.isBlank() || item.names.any { it.contains(filters.query.trim(), ignoreCase = true) }) &&
            !inLibrary(item)
    }
}

/**
 * The window's order for the sheet's [index] — popularity, start date, score, title — and arrow.
 * As in the recommendations, ↓ is most first and what has no value goes last both ways.
 */
internal fun releaseOrder(index: Int, ascending: Boolean): Comparator<Release> = when (index) {
    1 -> by(ascending) { it.start }
    2 -> by(ascending) { it.score }
    3 -> compareBy<Release> { it.title.lowercase() }.let { if (ascending) it else it.reversed() }
    else -> by(ascending) { it.popularity }
}.then(by(false) { it.popularity })

private fun <K : Comparable<K>> by(ascending: Boolean, key: (Release) -> K?): Comparator<Release> = Comparator { a, b ->
    val x = key(a)
    val y = key(b)
    when {
        x == null && y == null -> 0
        x == null -> 1
        y == null -> -1
        ascending -> x.compareTo(y)
        else -> y.compareTo(x)
    }
}

/**
 * The names the reader's sources are searched by, best first: the one the source most likely
 * uses for a translated release (Portuguese, then English), then romanised, then the rest, and
 * the native script last — few sources in the reader's languages index it.
 */
internal fun searchNames(names: List<String>, portuguese: List<String> = emptyList()): List<String> =
    (portuguese + names.filter { it.isLatin() } + names.filterNot { it.isLatin() })
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.titleKey() }

/** Whether a release is one the library has, by catalogue id or by any name. */
internal fun inLibrary(item: Release, anilistIds: Set<String>, mangaDexIds: Set<String>, titleKeys: Set<String>): Boolean =
    (item.origin == ORIGIN_ANILIST && item.id in anilistIds) ||
        (item.origin == ORIGIN_MANGADEX && item.id in mangaDexIds) ||
        item.names.any { it.titleKey() in titleKeys }

/** One page of AniList's `Page.media`, kept only from [since] (yyyymmdd) on. */
internal fun parseAniListReleases(root: JsonObject, since: Int): List<Release> =
    (((root["data"] as? JsonObject)?.get("Page") as? JsonObject)?.get("media")).array()
        .mapNotNull { (it as? JsonObject)?.let(::parseAniListRelease) }
        // AniList's startDate_greater lets through the odd series dated years back.
        .filter { (it.start ?: 0) >= since }

internal fun parseAniListRelease(media: JsonObject): Release? {
    val id = media["id"].text() ?: return null
    val title = media["title"] as? JsonObject
    val names = (
        listOfNotNull(title?.get("english").text(), title?.get("romaji").text(), title?.get("native").text()) +
            media["synonyms"].array().mapNotNull { it.text() }
        ).distinct()
    val cover = media["coverImage"] as? JsonObject
    return Release(
        origin = ORIGIN_ANILIST,
        id = id,
        title = names.firstOrNull() ?: return null,
        names = names,
        cover = cover?.get("extraLarge").text() ?: cover?.get("large").text(),
        country = media["countryOfOrigin"].text(),
        status = trackerStatusOf(media["status"].text()),
        start = fuzzyDate(media["startDate"] as? JsonObject),
        genres = media["genres"].array().mapNotNull { it.text() },
        popularity = media["popularity"].int(),
        score = media["averageScore"].int(),
        url = media["siteUrl"].text() ?: "https://anilist.co/manga/$id",
    )
}

/** MangaDex's `/manga` list, the fallback when AniList cannot be reached. */
internal fun parseMangaDexReleases(root: JsonObject): List<Release> = root["data"].array()
    .mapNotNull { (it as? JsonObject)?.let(::parseMangaDexRelease) }

internal fun parseMangaDexRelease(obj: JsonObject): Release? {
    val id = obj["id"].text() ?: return null
    val attributes = obj["attributes"] as? JsonObject ?: return null
    val main = (attributes["title"] as? JsonObject)?.values?.firstNotNullOfOrNull { it.text() } ?: return null
    val alt = attributes["altTitles"].array().flatMap { (it as? JsonObject)?.values.orEmpty() }.mapNotNull { it.text() }
    val english = attributes["altTitles"].array().mapNotNull { (it as? JsonObject)?.get("en").text() }
    val names = (english + main + alt).distinct()
    val cover = obj["relationships"].array().mapNotNull { it as? JsonObject }
        .firstOrNull { it["type"].text() == "cover_art" }
        ?.let { (it["attributes"] as? JsonObject)?.get("fileName").text() }
    // createdAt is when it reached MangaDex, which for a new series is about when it started.
    val created = attributes["createdAt"].text()?.take(10)?.replace("-", "")?.toIntOrNull()
    return Release(
        origin = ORIGIN_MANGADEX,
        id = id,
        title = names.first(),
        names = names,
        cover = cover?.let { "https://uploads.mangadex.org/covers/$id/$it.512.jpg" },
        country = when (attributes["originalLanguage"].text()) {
            "ja" -> "JP"
            "ko" -> "KR"
            "zh", "zh-hk" -> "CN"
            else -> null
        },
        status = trackerStatusOf(attributes["status"].text()),
        start = created,
        genres = attributes["tags"].array().mapNotNull { it as? JsonObject }
            .mapNotNull { it["attributes"] as? JsonObject }
            .filter { it["group"].text() == "genre" }
            .mapNotNull { (it["name"] as? JsonObject)?.get("en").text() },
        url = "https://mangadex.org/title/$id",
    )
}

/** Ongoing, completed, hiatus, cancelled: the statuses the filter offers, in that order. */
val RELEASE_STATUSES = listOf(SManga.ONGOING, SManga.COMPLETED, SManga.ON_HIATUS, SManga.CANCELLED)

internal fun fuzzyDate(date: JsonObject?): Int? {
    date ?: return null
    val year = date["year"].int() ?: return null
    return year * 10_000 + (date["month"].int() ?: 1) * 100 + (date["day"].int() ?: 1)
}
