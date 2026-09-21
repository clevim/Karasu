package karasu.domain.recommendation

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the reader said about a recommendation. The one signal in the whole system that is a
 * gesture rather than an inference.
 *
 * Three gestures, kept apart because they mean different things: "not interested" is evidence
 * against the tags it was recommended for; "already read" says they liked it enough to have read
 * it, and must never penalise anything; "added" is the reader taking the recommendation, the
 * clearest yes there is. The first two hide the entry; an added one is in the library now and
 * leaves on the next build by itself.
 *
 * @param titleKey the title stripped of case and punctuation, so the same series from another
 *   source stays hidden too.
 * @param because the tags the recommendation was made for. Only those are penalised: the reader
 *   is rejecting the reason, not every tag the series happens to carry.
 */
@Serializable
data class RecommendationFeedback(
    val sourceId: Long,
    val url: String,
    val titleKey: String,
    val kind: Kind,
    val because: List<String>,
    val at: Long,
) {
    enum class Kind { NOT_INTERESTED, ALREADY_READ, ADDED }
}

/**
 * Kept in a preference rather than a file: preferences travel in backups, and these verdicts are
 * the reader's own work. A table is the upgrade if this grows past a few thousand entries.
 */
class RecommendationFeedbackStore(private val preferences: PreferencesHelper) {

    @Volatile
    private var cached: List<RecommendationFeedback>? = null

    fun all(): List<RecommendationFeedback> = cached ?: runCatching {
        Json.decodeFromString<List<RecommendationFeedback>>(preferences.recommendationFeedback().get())
    }.getOrDefault(emptyList()).also { cached = it }

    fun add(feedback: RecommendationFeedback) = write(
        all().filterNot { it.sourceId == feedback.sourceId && it.url == feedback.url } + feedback,
    )

    /** The tag sets behind every "not interested", for [TasteProfile.penalized]. */
    fun dismissedTags(): List<List<String>> =
        all().filter { it.kind == RecommendationFeedback.Kind.NOT_INTERESTED }.map { it.because }

    /** The tag sets behind every add from the window, for [TasteProfile.boosted]. */
    fun addedTags(): List<List<String>> =
        all().filter { it.kind == RecommendationFeedback.Kind.ADDED }.map { it.because }

    /** What should no longer be shown: everything but the adds. */
    fun hidden(): List<RecommendationFeedback> = all().filter { it.kind != RecommendationFeedback.Kind.ADDED }

    /** "Zero what was learned": the reader's way out of a bad run of dismissals. */
    fun clear() = write(emptyList())

    private fun write(list: List<RecommendationFeedback>) {
        preferences.recommendationFeedback().set(Json.encodeToString(list))
        cached = list
    }
}
