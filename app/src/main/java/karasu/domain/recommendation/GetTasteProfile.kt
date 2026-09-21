package karasu.domain.recommendation

import android.content.Context
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.track.interactor.GetTrack
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.serialization.json.Json

/**
 * The library and everything the trackers said about it, folded into a [TasteProfile].
 *
 * Reads only the local `manga_sync` rows — the trackers' opinion is already on the phone, and it
 * is there for every service, logged in or not.
 */
class GetTasteProfile(
    private val context: Context,
    private val getLibraryManga: GetLibraryManga,
    private val getTrack: GetTrack,
    private val getChapter: GetChapter,
    private val trackManager: TrackManager,
    private val preferences: PreferencesHelper,
    private val feedback: RecommendationFeedbackStore,
    private val extras: TrackerExtrasStore,
) {
    init {
        // Every path that normalises a tag goes through a profile first; this is where the
        // reader's merges get loaded.
        TagMerges.user = merges()
    }

    fun merges(): Map<String, String> =
        runCatching { Json.decodeFromString<Map<String, String>>(preferences.recommendationTagMerges().get()) }
            .getOrDefault(emptyMap())

    /** [tags] become one tag called [into]; any override on the absorbed ones goes with them. */
    fun merge(tags: Collection<String>, into: String) {
        val name = into.trim().ifBlank { return }
        val absorbed = tags.map { canonicalTag(it) }.filter { !it.equals(name, ignoreCase = true) }
        val next = merges() + absorbed.associate { it.lowercase() to name }
        preferences.recommendationTagMerges().set(Json.encodeToString(next))
        TagMerges.user = next
        setOverrides(overrides().filterKeys { key -> absorbed.none { it.equals(key, ignoreCase = true) } })
    }

    fun unmergeAll() {
        preferences.recommendationTagMerges().set("{}")
        TagMerges.user = emptyMap()
    }

    /**
     * The profile the recommender uses: what the library taught, shrunk by what the reader
     * dismissed, with the reader's overrides on top. In that order, so a slider the reader set
     * always wins.
     */
    suspend fun await(): TasteProfile = awaitLearned()
        .boosted(feedback.addedTags())
        .penalized(feedback.dismissedTags())
        .adjusted(overrides())

    /** What the library taught alone, for the screen that shows the reader what to override. */
    suspend fun awaitLearned(): TasteProfile {
        // Only the chosen categories teach the profile: a "maybe later" shelf full of things
        // never really read says nothing about taste. Empty means all of them.
        val categories = preferences.recommendationCategories().get().mapNotNull { it.toIntOrNull() }.toSet()
        val library = getLibraryManga.await()
            .filter { categories.isEmpty() || it.category in categories }
            .distinctBy { it.manga.id }
        if (library.isEmpty()) return TasteProfile()
        val tracks = getTrack.awaitAll().groupBy { it.manga_id }
        // A source that lists the scanlation group among the genres is teaching the profile a
        // group's name. The library knows every group's name, so those never get in — nor does
        // anything the reader struck out by hand.
        val junk = getChapter.awaitAllScanlators() + banned().map { it.lowercase() }
        val entries = library.map { manga ->
            manga.toEntry(tracks[manga.manga.id].orEmpty().mapNotNull(::ratingOf))
                .let { entry -> entry.copy(tags = entry.tags.filterNot { isJunk(it, junk) }) }
        }
        // The tag helpers live on the Manga interface and use nothing of the instance.
        val any = library.first().manga
        return buildTasteProfile(entries, isFormatTag = { any.isSeriesTag(it) }, now = System.currentTimeMillis())
    }

    /** Struck by hand, spelled as the profile spells them. */
    fun banned(): Set<String> = preferences.recommendationBannedTags().get()

    fun ban(tag: String) = preferences.recommendationBannedTags().set(banned() + canonicalTag(tag))

    fun unbanAll() = preferences.recommendationBannedTags().set(emptySet())

    private fun isJunk(tag: String, junk: Set<String>): Boolean {
        val raw = tag.trim().lowercase()
        val canonical = normalizeTag(tag)?.lowercase() ?: return true
        return raw in junk || canonical in junk
    }

    fun overrides(): Map<String, Float> =
        runCatching { Json.decodeFromString<Map<String, Float>>(preferences.recommendationTagWeights().get()) }
            .getOrDefault(emptyMap())

    fun setOverrides(overrides: Map<String, Float>) =
        preferences.recommendationTagWeights().set(Json.encodeToString(overrides))

    private fun LibraryManga.toEntry(ratings: List<Rating>) = LibraryEntry(
        mangaId = manga.id!!,
        title = manga.title,
        tags = manga.getOriginalGenres().orEmpty() + extras.get(manga.id)?.tags.orEmpty(),
        readFraction = if (totalChapters > 0) read.toFloat() / totalChapters else 0f,
        favorite = manga.favorite,
        ratings = ratings,
        lastReadAt = lastRead.takeIf { it > 0 },
    )

    /**
     * Komga and Suwayomi have no score scale, so their rows always say `0f`; read as a score that
     * would be "the reader hated all 300 of these". A score only counts when the service has a
     * scale and the reader used it.
     */
    private fun ratingOf(track: Track): Rating? {
        val service = trackManager.getService(track.sync_id) ?: return null
        val status = when {
            service.getGlobalStatus(track.status) == context.getString(MR.strings.dropped) -> Rating.Status.DROPPED
            track.status == service.completedStatus() -> Rating.Status.COMPLETED
            track.status == service.readingStatus() -> Rating.Status.READING
            else -> Rating.Status.OTHER
        }
        val score = if (service.getScoreList().isNotEmpty() && track.score > 0f) {
            (service.get10PointScore(track.score) / 10f).coerceIn(0f, 1f)
        } else {
            null
        }
        return Rating(score, status)
    }
}
