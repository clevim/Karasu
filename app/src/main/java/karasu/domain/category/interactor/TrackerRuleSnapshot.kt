package karasu.domain.category.interactor

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import karasu.domain.category.models.TrackerStatus
import kotlin.math.roundToInt

/** What the rule engine needs to know about a manga's trackers, with the service scales removed. */
data class TrackerRuleSnapshot(
    val status: TrackerStatus,
    /** 0-10, or null when nothing has scored it. */
    val score: Int?,
)

/**
 * Folds every tracker bound to one manga into a single answer.
 *
 * Status takes the most progressed opinion — finished beats reading beats planning — because a
 * manga marked complete on one service and still "reading" on a stale one is complete. Score
 * takes the highest, since an unscored tracker reports zero and would otherwise drag a real
 * rating down to nothing.
 *
 * Tracks whose service is not registered are ignored rather than guessed at; their numbering is
 * unknowable, and treating an unknown status as [TrackerStatus.OTHER] would let it outvote a
 * real one.
 */
fun trackerSnapshot(tracks: List<Track>, service: (Long) -> TrackService?): TrackerRuleSnapshot? {
    val known = tracks.mapNotNull { track -> service(track.sync_id)?.let { track to it } }
    if (known.isEmpty()) return null

    val status = known
        .map { (track, svc) -> svc.statusOf(track.status) }
        .minByOrNull { it.precedence() }
        ?: TrackerStatus.OTHER

    val score = known
        .mapNotNull { (track, svc) -> svc.get10PointScore(track.score).takeIf { it > 0f } }
        .maxOrNull()
        ?.roundToInt()
        ?.coerceIn(0, 10)

    return TrackerRuleSnapshot(status, score)
}

/**
 * Each service exposes its own constant for these three, which is the whole reason a rule can be
 * written once and keep meaning the same thing on a different tracker.
 */
private fun TrackService.statusOf(status: Int): TrackerStatus = when (status) {
    completedStatus() -> TrackerStatus.COMPLETED
    readingStatus() -> TrackerStatus.READING
    planningStatus() -> TrackerStatus.PLANNING
    else -> TrackerStatus.OTHER
}

/** Lower wins, so "most progressed" is just a min. */
private fun TrackerStatus.precedence(): Int = when (this) {
    TrackerStatus.COMPLETED -> 0
    TrackerStatus.READING -> 1
    TrackerStatus.PLANNING -> 2
    TrackerStatus.OTHER -> 3
}
