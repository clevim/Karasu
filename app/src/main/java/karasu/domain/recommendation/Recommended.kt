package karasu.domain.recommendation

import kotlin.math.log10
import kotlinx.serialization.Serializable

/**
 * One recommendation, with everything the shop window needs to draw, sort and explain it.
 *
 * @param tags what is known about it: the full genre list once details were fetched, otherwise
 *   only the tags it was found under.
 * @param because the tags it was recommended for, strongest first.
 * @param chapters how many chapters it has, null until known.
 * @param firstUpload when its earliest chapter went up, null until known or when the source
 *   reports no dates.
 * @param lastUpload when its latest chapter went up, same caveats.
 * @param completed the source says the series is finished: it stopped because it ended.
 * @param fromLibrary saved by the reader and never opened. Shown only on request.
 * @param fetched whether details were read: a fetched entry is ranked on what it is, an
 *   unfetched one on what it was found under, so the two do not compare and the unfetched ones
 *   always go after.
 * @param via the series this was suggested as being like, when it came from outside rather
 *   than from a tag search. Shown in place of the tags: "like X" says more than "Romance".
 * @param votes how many tracking services named it. Two agreeing is worth more than one.
 * @param popularity AniList's member count, null when unknown. A tie-breaker, never a reason.
 * @param averageScore AniList's mean score 0..100, same caveat.
 * @param explore recommended on tags the library barely backs: the slice kept for surprises.
 * @param foundByTag it came up in a genre search of a source, not only from a tracker.
 * @param fromCatalog found in AniList's own catalogue under the reader's tags, with a score the
 *   community gave it. The one signal here that says "good", not only "matches", so it weighs
 *   most: see [withBonuses].
 * @param oneShotChapters most of its chapters call themselves a one-shot. The tags and the
 *   title do not always say; the chapter list usually does.
 * @param datesUntrusted the source reports no real dates (none, or the fetch time on every
 *   chapter), so nothing about when this series moves can be known from it. Ranked lower and
 *   hidden by default: on equal tags, the entry whose source tells the truth is the better
 *   recommendation. Its chapter count is not trusted as "new" either: such sources often list
 *   volumes, and eleven volumes is not a new series.
 */
@Serializable
data class Recommended(
    val sourceId: Long,
    val url: String,
    val title: String,
    val thumbnail: String?,
    val tags: List<String>,
    val score: Float,
    val because: List<String>,
    val chapters: Int? = null,
    val firstUpload: Long? = null,
    val lastUpload: Long? = null,
    val completed: Boolean = false,
    val fromLibrary: Boolean = false,
    val fetched: Boolean = false,
    val via: String? = null,
    val votes: Int = 0,
    val popularity: Int? = null,
    val averageScore: Int? = null,
    val explore: Boolean = false,
    val foundByTag: Boolean = false,
    val datesUntrusted: Boolean = false,
    val oneShotChapters: Boolean = false,
    val fromCatalog: Boolean = false,
) {
    /**
     * Everything that moves a freshly ranked score, in one place so a build and a re-rank agree:
     * silence costs, a tracker naming what the tags also found pays, two trackers agreeing pays
     * more, and what the wider world thinks breaks the ties the tags leave — a mild lift that
     * orders candidates the tags could not tell apart and never moves one past a better match.
     */
    fun withBonuses(now: Long): Recommended {
        var s = score
        if (datesUntrusted) s *= UNTRUSTED_DATES_PENALTY
        if (isQuiet(now)) s *= QUIET_PENALTY
        if (via != null && foundByTag) s *= BOTH_WAYS
        if (votes > 1) s *= 1f + AGREEMENT * (votes - 1)
        popularity?.let { p ->
            val pop = (log10(p + 1f) / POPULARITY_LOG_CEILING).coerceIn(0f, 1f)
            val avg = averageScore?.let { it / 100f } ?: pop
            s *= 1f + QUALITY_LIFT * (pop + avg) / 2f
        }
        // The community's verdict is the strongest thing known about a series: a catalogue find
        // rated 85 is lifted by nearly half, on top of what its tags earned.
        if (fromCatalog) averageScore?.let { s *= 1f + CATALOG_LIFT * (it / 100f) }
        return copy(score = s)
    }

    /**
     * A new release: it started recently, or it is short *and still moving*. Short alone is not
     * enough — a two-chapter crossover from 2022 is short, and it is not news.
     */
    fun isNew(now: Long): Boolean {
        val startedRecently = firstUpload != null && firstUpload > 0 && now - firstUpload <= NEW_MAX_AGE_MS
        val shortAndMoving = chapters != null && chapters <= NEW_MAX_CHAPTERS && !datesUntrusted &&
            lastUpload != null && lastUpload > 0 && now - lastUpload <= NEW_MAX_AGE_MS
        return startedRecently || shortAndMoving
    }

    /**
     * Gone quiet for longer than a series its size gets: one chapter and nothing for a year is a
     * drop or a source nobody updates; a hundred and fifty chapters and nothing for a year is a
     * season break. The allowance grows with the chapter count, from [STALE_BASE_MONTHS] up to
     * [STALE_MAX_MONTHS]. A finished series is never stale, and one whose source gives no dates
     * cannot be judged and is let through.
     */
    fun isStale(now: Long): Boolean = quietRatio(now) >= 1f

    /**
     * A one-shot is not something to follow. Known by its tag, its title, or by being one
     * finished chapter — the last is how a completed one slips past the staleness rule.
     */
    val isOneShot: Boolean
        get() = oneShotChapters ||
            tags.any { normalizeTag(it) == "One Shot" } ||
            ONE_SHOT.containsMatchIn(title) ||
            (chapters == 1 && completed)

    /** Past half its allowance the series is suspect: still shown, ranked lower. */
    fun isQuiet(now: Long): Boolean = quietRatio(now) >= QUIET_FROM

    /** How much of its silence allowance the series has used, 0 when it cannot be judged. */
    private fun quietRatio(now: Long): Float {
        if (completed) return 0f
        val last = lastUpload?.takeIf { it > 0 } ?: return 0f
        val count = chapters ?: return 0f
        val allowedMonths = (STALE_BASE_MONTHS + count / STALE_CHAPTERS_PER_MONTH).coerceAtMost(STALE_MAX_MONTHS)
        return (now - last).toFloat() / (allowedMonths * MONTH_MS)
    }

    companion object {
        const val NEW_MAX_CHAPTERS = 30
        const val NEW_MAX_AGE_MS = 180L * 24 * 60 * 60 * 1000

        private const val MONTH_MS = 30L * 24 * 60 * 60 * 1000

        /** What a brand-new series gets before silence counts against it. */
        const val STALE_BASE_MONTHS = 3

        /** One more month of allowance per this many chapters. */
        const val STALE_CHAPTERS_PER_MONTH = 10

        /** The reader's ceiling: past two years nothing is a season break. */
        const val STALE_MAX_MONTHS = 24

        /** From this share of the allowance on, silence costs rank. */
        const val QUIET_FROM = 0.5f

        /** What it costs. */
        const val QUIET_PENALTY = 0.6f

        /** What a source that cannot say when anything came out costs its entries. */
        const val UNTRUSTED_DATES_PENALTY = 0.7f

        val ONE_SHOT = Regex("(?i)\\bone[\\s-]?shot\\b")

        /** Score multiplier for a candidate the tags found and a tracker named. */
        private const val BOTH_WAYS = 1.25f

        /** Extra per additional tracker naming the same series. */
        private const val AGREEMENT = 0.15f

        /** log10 of a million members: what "everyone read it" looks like on AniList. */
        private const val POPULARITY_LOG_CEILING = 6f

        /** The most the quality lift can add. */
        private const val QUALITY_LIFT = 0.15f

        /** The most AniList's score can add to a catalogue find. */
        private const val CATALOG_LIFT = 0.5f

        /** One explore slot in every this many. */
        const val EXPLORE_EVERY = 5

        /** A tag on this many liked entries or fewer is barely backed: recommending on it is exploring. */
        const val EXPLORE_SUPPORT = 3
    }
}

/**
 * A window that only exploits converges on the same five tags and stops surprising. One slot in
 * [Recommended.EXPLORE_EVERY] goes to a candidate recommended on tags the library barely backs;
 * the rest keep their rank. Explore candidates come in their own order, so the best of them lands
 * first.
 */
fun List<Recommended>.withExploreSlots(): List<Recommended> {
    val (explore, main) = partition { it.explore }
    if (explore.isEmpty() || main.isEmpty()) return this
    val out = ArrayList<Recommended>(size)
    val mainIt = main.iterator()
    val exploreIt = explore.iterator()
    var i = 0
    while (mainIt.hasNext() || exploreIt.hasNext()) {
        val takeExplore = (i + 1) % Recommended.EXPLORE_EVERY == 0
        out += when {
            takeExplore && exploreIt.hasNext() -> exploreIt.next()
            mainIt.hasNext() -> mainIt.next()
            else -> exploreIt.next()
        }
        i++
    }
    return out
}

/** What the last build produced, and the profile it was ranked against. */
/**
 * What the last build produced, and the profile it was ranked against.
 *
 * @param items what has been read, ranked: the window.
 * @param pending candidates found but not read yet, best first. A build is resumable: each run
 *  takes a batch off this queue, and "done" is when [items] reaches the reader's target or this
 *  runs dry.
 */
@Serializable
data class RecommendationSnapshot(
    val builtAt: Long,
    val profileTags: Map<String, Float>,
    val items: List<Recommended>,
    /** How big the library was: a much bigger one means the window is out of date. */
    val librarySize: Int = 0,
    val pending: List<Recommended> = emptyList(),
) {
    /** Cards the window can show: what was read, not counting the reader's own unread library. */
    val shown: Int get() = items.count { !it.fromLibrary }
}

/**
 * The same items ranked against a different profile — the reader moved a slider — without going
 * back to the sources. Unfetched entries keep their place after the fetched ones.
 */
fun RecommendationSnapshot.rerank(profile: TasteProfile): RecommendationSnapshot {
    if (profile.tags == profileTags) return this
    val (fetched, rest) = items.partition { it.fetched || it.fromLibrary }
    val now = System.currentTimeMillis()
    val ranked = profile.rank(fetched) { it.tags }
        .map { it.item.copy(score = it.score, because = it.because).withBonuses(now) }
        .map { item -> item.copy(explore = item.because.isNotEmpty() && item.because.all { (profile.support[it] ?: 0) <= Recommended.EXPLORE_SUPPORT }) }
        .sortedByDescending { it.score }
        .withExploreSlots() +
        profile.rank(rest) { it.tags }.map { it.item.copy(score = it.score, because = it.because) }
    return copy(profileTags = profile.tags, items = ranked)
}

/**
 * Where a build is, for whoever is watching it: a notification, a snack, a settings row.
 *
 * @param done and [total] count the stage's own unit — sources searched, entries detailed,
 *   seeds asked about. Zero total means the stage has no countable unit.
 */
data class BuildProgress(val stage: Stage, val done: Int = 0, val total: Int = 0) {
    enum class Stage { ENRICHING, MERGES, PROFILE, SEARCHING, TRACKERS, DETAILS, RANKING }
}

/**
 * Upload dates worth believing, or none.
 *
 * A source with no dates of its own hands back the moment it was asked, for every chapter: a
 * hundred chapters "0 minutes ago". Read literally that is a brand-new, never-quiet series, and
 * it climbs the window on a lie. Chapters of one series are not all released within a day of
 * each other, so when three or more are, the dates are the fetch, not the release, and say
 * nothing.
 */
fun honestUploadDates(dates: List<Long>): List<Long> {
    val real = dates.filter { it > 0 }
    if (real.size < 3) return real
    return if (real.max() - real.min() < SAME_MOMENT_MS) emptyList() else real
}

/**
 * Whether the source is telling nothing about when this series moves: either it reports no
 * dates at all (every chapter at zero — the app then shows the fetch time, "0 minutes ago"),
 * or it stamps every chapter with the moment it was asked. Either way, with three or more
 * chapters to judge by, the dates are not evidence.
 */
fun uploadDatesUntrusted(dates: List<Long>): Boolean {
    if (dates.size < 3) return false
    val real = dates.filter { it > 0 }
    return real.isEmpty() || honestUploadDates(real).isEmpty()
}

private const val SAME_MOMENT_MS = 24L * 60 * 60 * 1000
