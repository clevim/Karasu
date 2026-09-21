package karasu.domain.recommendation

import kotlin.math.pow

/** What a tracker says about one entry. One of these per logged-in service that knows it. */
data class Rating(
    /**
     * The reader's own score, 0f to 1f, or null when they never gave one.
     *
     * Normalised by the caller: every service has its own scale — ten points, a hundred, five
     * stars, a row of faces — and which one is in use is a per-account setting. That knowledge
     * belongs next to the service, not in here.
     */
    val score: Float?,
    val status: Status,
) {
    enum class Status { READING, COMPLETED, DROPPED, OTHER }
}

/**
 * One library entry, reduced to what recommending needs.
 *
 * Deliberately not `Manga`: the scoring below is the part that will be tuned over and over, and
 * keeping it over plain data is what lets it be tuned against a test instead of against a phone.
 *
 * @param readFraction chapters read over chapters there are, 0f when nothing is read.
 * @param ratings what each logged-in tracker says. Empty when none are connected, which is the
 *   case the reading history alone has to carry.
 * @param lastReadAt when the reader last opened it, epoch millis, or null when unknown. Taste
 *   moves; what was read last month says more about it than what was read three years ago.
 */
data class LibraryEntry(
    val mangaId: Long,
    val title: String,
    val tags: List<String>,
    val readFraction: Float,
    val favorite: Boolean,
    val ratings: List<Rating> = emptyList(),
    val lastReadAt: Long? = null,
)

/**
 * What the reader likes, measured off their own library.
 *
 * @param tags tag to weight, strongest first. The strongest is 1f — weights are relative and mean
 *   nothing on their own. **Weights can be negative**: a tag that keeps turning up on series the
 *   reader dropped is evidence too, and the ranker should push those candidates down rather than
 *   ignore them.
 * @param formats the same, for the tags that say what a series *is* rather than what it is about
 *   (manga, manhwa, webtoon). Kept apart because they are not a taste: a reader whose library is
 *   all webtoons would otherwise see "webtoon" outrank every genre and drown the real signal.
 * @param seeds the entries worth asking a tracker "what is like this?" about, strongest first.
 *   Never a dropped one.
 * @param support how many liked entries carry each tag: how sure the weight is. Three dismissed
 *   isekai must not sink a tag forty read isekai hold up.
 * @param pairs tag pairs that keep turning up together on liked entries, keyed by [pairKey],
 *   strongest 1f. "Romance" alone says little; "Romance" with "Supernatural" is a taste.
 */
data class TasteProfile(
    val tags: Map<String, Float> = emptyMap(),
    val formats: Map<String, Float> = emptyMap(),
    val seeds: List<LibraryEntry> = emptyList(),
    val support: Map<String, Int> = emptyMap(),
    val pairs: Map<String, Float> = emptyMap(),
) {
    val isEmpty: Boolean get() = tags.isEmpty() && formats.isEmpty()
}

/**
 * How much one entry counts, and which way.
 *
 * **Reading is the driver.** What makes a tag matter is turning up again and again in what the
 * reader actually read; a score is the reader commenting on one series, and one comment should not
 * outrank a pattern. So the reading history sets the size of the vote and a tracker only scales it.
 *
 * Past [FULLY_READ_AT] an entry counts fully — the reader's own rule, and it keeps a finished
 * 200-chapter series from outweighing a finished 20-chapter one, which says nothing about taste.
 * Below the line it ramps, so abandoning something after three chapters counts for less than
 * abandoning it halfway. Merely saved counts least: a library fills up with things the reader
 * meant to try.
 */
internal fun affinityOf(entry: LibraryEntry, now: Long? = null): Float {
    val read = readingAffinityOf(entry)
    if (read == 0f) return 0f
    return read * (ratingMultiplierOf(entry.ratings) ?: 1f) * recencyOf(entry, now)
}

/**
 * Halves every [HALF_LIFE_MS] since the entry was last read, down to [RECENCY_FLOOR]: an old
 * favourite still counts, it just no longer outvotes last month. Unknown dates are not aged.
 */
private fun recencyOf(entry: LibraryEntry, now: Long?): Float {
    val at = entry.lastReadAt?.takeIf { it > 0 } ?: return 1f
    if (now == null) return 1f
    val halfLives = (now - at).coerceAtLeast(0L).toFloat() / HALF_LIFE_MS
    return 2f.pow(-halfLives).coerceAtLeast(RECENCY_FLOOR)
}

private fun readingAffinityOf(entry: LibraryEntry): Float = when {
    entry.readFraction >= FULLY_READ_AT -> 1f
    entry.readFraction > 0f -> STARTED_WEIGHT + (entry.readFraction / FULLY_READ_AT) * (1f - STARTED_WEIGHT)
    entry.favorite -> SAVED_WEIGHT
    else -> 0f
}

/**
 * How much the trackers scale this entry's vote, or null when none of them knew it.
 *
 * Every connected service that has an opinion gets one say and the says are averaged, so two
 * trackers agreeing counts once rather than twice.
 */
private fun ratingMultiplierOf(ratings: List<Rating>): Float? {
    val votes = ratings.mapNotNull(::multiplierOf)
    return if (votes.isEmpty()) null else votes.sum() / votes.size
}

/**
 * One service's verdict as a multiplier, or null when it has nothing to say.
 *
 * A score moves the vote between [DISLIKED_SCALE] and [LIKED_SCALE] but never flips it: disliking
 * one series is not evidence against everything it is tagged with. Dropping it is — that is the
 * reader refusing to carry on, and it is the one thing a reading history cannot express, since
 * stopping at chapter three looks identical whether they hated it or simply got busy.
 */
private fun multiplierOf(rating: Rating): Float? = when {
    rating.status == Rating.Status.DROPPED -> DROPPED_SCALE
    rating.score != null -> DISLIKED_SCALE + rating.score * (LIKED_SCALE - DISLIKED_SCALE)
    rating.status == Rating.Status.COMPLETED -> 1f
    else -> null
}

/**
 * Folds a library into [TasteProfile].
 *
 * A tag's weight is the total affinity of the entries carrying it, so a tag on three series the
 * reader finished beats one on ten they only saved. No inverse-frequency correction: a tag on most
 * of the library really is the reader's taste, however little it tells one candidate from another
 * — that is the ranker's problem, not this one's.
 */
fun buildTasteProfile(
    library: List<LibraryEntry>,
    isFormatTag: (String) -> Boolean = { false },
    seedCount: Int = DEFAULT_SEEDS,
    now: Long? = null,
): TasteProfile {
    val scored = library.map { it to affinityOf(it, now) }.filter { (_, affinity) -> affinity != 0f }
    if (scored.isEmpty()) return TasteProfile()

    val tags = mutableMapOf<String, Float>()
    val formats = mutableMapOf<String, Float>()
    val support = mutableMapOf<String, Int>()
    val pairs = mutableMapOf<String, Float>()
    scored.forEach { (entry, affinity) ->
        // One entry cannot vote twice for the same tag however its source spelled it.
        val (formatTags, its) = entry.tags.mapNotNull(::normalizeTag)
            .distinctBy { it.lowercase() }
            .partition(isFormatTag)
        formatTags.forEach { formats[it] = (formats[it] ?: 0f) + affinity }
        its.forEach { tag ->
            tags[tag] = (tags[tag] ?: 0f) + affinity
            if (affinity > 0f) support[tag] = (support[tag] ?: 0) + 1
        }
        // Every pair on a liked entry: what the reader likes *together*.
        if (affinity > 0f) {
            for (i in its.indices) for (j in i + 1 until its.size) {
                val key = pairKey(its[i], its[j])
                pairs[key] = (pairs[key] ?: 0f) + affinity
            }
        }
    }

    return TasteProfile(
        tags = tags.normalized(),
        formats = formats.normalized(),
        support = support,
        // Only pairs seen more than once are a pattern; the rest is one series' tag list.
        pairs = pairs.filterValues { it > PAIR_MIN_WEIGHT }.normalized().entries.take(MAX_PAIRS).associate { it.key to it.value },
        // Asking "what is like this?" about something the reader gave up on would spend a request
        // to be told about more of it.
        seeds = scored.filter { (_, affinity) -> affinity > 0f }
            .sortedByDescending { (_, affinity) -> affinity }
            .take(seedCount)
            .map { (entry, _) -> entry },
    )
}

/**
 * Scaled against the strongest liked tag, so a big library and a small one produce comparable
 * numbers. Divided by the largest *positive* weight rather than the largest absolute one: with a
 * heavily disliked tag in the mix the latter would squash every real preference towards zero.
 */
private fun Map<String, Float>.normalized(): Map<String, Float> {
    val top = values.filter { it > 0f }.maxOrNull() ?: return emptyMap()
    return entries.sortedByDescending { it.value }
        .associate { (tag, weight) -> tag to weight / top }
}

/** The reader's own cut: read this much of a series and it counts as a series they read. */
const val FULLY_READ_AT = 0.5f

/** Started but barely: enough to count, not enough to steer. */
private const val STARTED_WEIGHT = 0.3f

/** Saved and never opened. Meant to try, which is not the same as liked. */
private const val SAVED_WEIGHT = 0.15f

/** Dropped: the vote flips, and is as big as the chance the reader gave the series before bailing. */
private const val DROPPED_SCALE = -1f

/** What the lowest score does to a vote. It shrinks it; it does not turn it against the tag. */
private const val DISLIKED_SCALE = 0.5f

/** And what the highest does. */
private const val LIKED_SCALE = 1.5f

private const val DEFAULT_SEEDS = 10

/** How long it takes a read to count half as much. */
private const val HALF_LIFE_MS = 270L * 24 * 60 * 60 * 1000

/** What an old read still counts. Never zero: the library is the reader's history, not their week. */
private const val RECENCY_FLOOR = 0.25f

/** A pair with this much total affinity turned up on more than one liked entry. */
private const val PAIR_MIN_WEIGHT = 1f

private const val MAX_PAIRS = 200

/** The one spelling for an unordered pair of tags. */
fun pairKey(a: String, b: String): String = listOf(a.lowercase(), b.lowercase()).sorted().joinToString("|")

/**
 * The reader's own say over what was learned.
 *
 * A tag in [overrides] has exactly that weight, whether the library taught it or not: pinning
 * a tag the library never saw adds it, and pinning one at -1 sinks everything carrying it.
 * Every tag not overridden keeps following the library, so control never costs the learning.
 */
fun TasteProfile.adjusted(overrides: Map<String, Float>): TasteProfile {
    if (overrides.isEmpty()) return this
    val merged = tags.toMutableMap()
    overrides.forEach { (tag, weight) ->
        val canonical = canonicalTag(tag)
        val key = merged.keys.firstOrNull { it.equals(canonical, ignoreCase = true) } ?: canonical
        merged[key] = weight
    }
    return copy(tags = merged.entries.sortedByDescending { it.value }.associate { it.key to it.value })
}

/**
 * The reader's explicit rejections, folded in.
 *
 * Each dismissal is the tags a recommendation was made for, and it is the one signal here that
 * comes from a gesture rather than from inference, so it counts — but relative to how much the
 * library backs the tag. A weight shrinks by `support / (support + dismissals)`: three dismissed
 * isekai against forty read ones is a nudge, against two it is most of the weight. Without that
 * the loop oscillates — the tag sinks, vanishes from the window, stops being dismissed, climbs
 * back, repeat.
 */
fun TasteProfile.penalized(dismissed: List<List<String>>): TasteProfile {
    if (dismissed.isEmpty()) return this
    val counts = dismissed.flatMap { it.map(::canonicalTag).distinctBy { tag -> tag.lowercase() } }
        .groupingBy { it.lowercase() }.eachCount()
    if (counts.isEmpty()) return this
    val shrunk = tags.mapValues { (tag, weight) ->
        val hits = counts[tag.lowercase()] ?: return@mapValues weight
        if (weight <= 0f) return@mapValues weight
        val backing = support[tag] ?: 0
        weight * backing / (backing + hits).toFloat()
    }
    return copy(tags = shrunk.entries.sortedByDescending { it.value }.associate { it.key to it.value })
}

/**
 * The reader's explicit yeses: the tags of what they added to the library straight from the
 * window. The mirror image of [penalized], and just as careful about support — one add must not
 * hand a tag on two entries the weight of a tag on forty. Grows by `(support + adds × ADD_WEIGHT)
 * / support` and is then re-scaled, so the strongest tag stays at 1f.
 *
 * Deliberately weaker than a read: something added from the window and then read reinforces the
 * tags that recommended it, which recommend more of the same. Undiscounted, the taste narrows on
 * its own.
 */
fun TasteProfile.boosted(added: List<List<String>>): TasteProfile {
    if (added.isEmpty()) return this
    val counts = added.flatMap { it.map(::canonicalTag).distinctBy { tag -> tag.lowercase() } }
        .groupingBy { it.lowercase() }.eachCount()
    if (counts.isEmpty()) return this
    val grown = tags.mapValues { (tag, weight) ->
        val hits = counts[tag.lowercase()] ?: return@mapValues weight
        val backing = support[tag] ?: return@mapValues weight
        if (weight <= 0f || backing == 0) return@mapValues weight
        weight * (backing + hits * ADD_WEIGHT) / backing
    }
    val top = grown.values.filter { it > 0f }.maxOrNull() ?: return this
    return copy(tags = grown.mapValues { it.value / top }.entries.sortedByDescending { it.value }.associate { it.key to it.value })
}

/** What one add is worth next to one read entry. */
private const val ADD_WEIGHT = 0.5f
