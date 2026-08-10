package karasu.domain.category.models

import kotlin.time.Duration.Companion.days

/**
 * The snapshot of a manga a rule is evaluated against.
 *
 * Everything here already comes loaded with the library, so evaluating the whole graph costs
 * no queries. It is a plain data class rather than `LibraryManga` so the evaluator stays
 * testable without building a manga.
 */
data class RuleInput(
    val unread: Int = 0,
    val read: Int = 0,
    val totalChapters: Int = 0,
    /** Epoch millis of the last chapter read, 0 when never read. */
    val lastRead: Long = 0,
    /** Epoch millis of the newest chapter published, 0 when unknown. */
    val latestUpdate: Long = 0,
    val status: Int = 0,
    val sourceMissing: Boolean = false,
    val sourceObsolete: Boolean = false,
    /** Consecutive failed updates blamed on the source. */
    val updateFailures: Int = 0,
    /**
     * Reading status from whichever tracker is bound, already normalised. Null when nothing
     * tracks this manga — which is different from tracking it as "planning".
     */
    val trackerStatus: TrackerStatus? = null,
    /** Tracker score on a 0-10 scale. Null when untracked, or tracked but never scored. */
    val trackerScore: Int? = null,
    /**
     * Epoch millis the next chapter is expected at. Null when the app has no estimate yet —
     * a new entry, or a source that reports no dates to learn a rhythm from.
     */
    val nextRelease: Long? = null,
    /** Whether the series is overdue by several cycles. Null when there is no estimate. */
    val releaseStalled: Boolean? = null,
)

/**
 * The category this manga should move to, or null to leave it where it is.
 *
 * Transitions are tried in order and the first whose conditions all hold wins.
 */
fun CategoryRule.evaluate(input: RuleInput, now: Long): Long? =
    transitions.firstOrNull { transition ->
        transition.enabled && transition.conditions.matches(input, now)
    }?.target

/**
 * Whether every condition holds, as a predicate with no destination attached.
 *
 * Conditions are ANDed. An empty list is deliberately false: in a transition it would fire on
 * everything, and anywhere else it means "not configured yet" — neither should select the whole
 * library. Callers that want "no filter" must check for empty themselves.
 */
fun List<RuleCondition>.matches(input: RuleInput, now: Long): Boolean =
    isNotEmpty() && all { it.matches(input, now) }

private fun RuleCondition.matches(input: RuleInput, now: Long): Boolean = when (field) {
    RuleField.UNREAD -> compare(input.unread.toLong())
    RuleField.READ -> compare(input.read.toLong())
    RuleField.TOTAL_CHAPTERS -> compare(input.totalChapters.toLong())
    RuleField.STATUS -> input.status.toLong() == value
    RuleField.SOURCE_MISSING -> input.sourceMissing == (value != 0L)
    RuleField.SOURCE_OBSOLETE -> input.sourceObsolete == (value != 0L)
    RuleField.UPDATE_FAILURES -> compare(input.updateFailures.toLong())
    RuleField.NEVER_READ -> (input.lastRead == 0L) == (value != 0L)
    // A missing timestamp is unknown, not "very long ago": matching on it would sweep in
    // every manga the user never opened.
    RuleField.DAYS_SINCE_READ -> input.lastRead != 0L && compare(daysBetween(input.lastRead, now))
    RuleField.DAYS_SINCE_UPDATE ->
        input.latestUpdate != 0L && compare(daysBetween(input.latestUpdate, now))
    // No estimate matches nothing: "unknown" is not zero days away, and treating it as such
    // would hand every brand-new entry to whichever schedule rule the user wrote first.
    RuleField.DAYS_UNTIL_RELEASE ->
        input.nextRelease != null && compare(daysBetween(now, input.nextRelease))
    RuleField.RELEASE_STALLED ->
        input.releaseStalled != null && input.releaseStalled == (value != 0L)
    RuleField.TRACKED -> (input.trackerStatus != null) == (value != 0L)
    // Untracked matches no status, the same way an unknown timestamp matches no age. Otherwise
    // every untracked manga would satisfy whatever status the user happened to pick first.
    RuleField.TRACKER_STATUS -> input.trackerStatus?.id == value
    RuleField.TRACKER_SCORE -> input.trackerScore != null && compare(input.trackerScore.toLong())
}

private fun RuleCondition.compare(actual: Long): Boolean = when (operator) {
    RuleOperator.GREATER -> actual > value
    RuleOperator.LESS -> actual < value
    RuleOperator.EQUAL -> actual == value
}

private fun daysBetween(from: Long, to: Long): Long = (to - from) / 1.days.inWholeMilliseconds
