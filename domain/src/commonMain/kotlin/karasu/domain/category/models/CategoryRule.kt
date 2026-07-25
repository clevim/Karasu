package karasu.domain.category.models

import kotlinx.serialization.Serializable

/**
 * What a category does to the manga sitting in it.
 *
 * Rules describe leaving, not belonging: a category with a rule is a state, and its
 * [transitions] are the ways out of that state. A manga is only ever considered for the
 * transitions of a category it is already in, which is what lets the path carry information a
 * standalone predicate can't — "forgotten" only makes sense for something you were reading,
 * and it is only reachable from the category you were reading it in.
 *
 * Entry into the graph is manual, or the default category new library entries land in.
 */
@Serializable
data class CategoryRule(
    val transitions: List<CategoryTransition> = emptyList(),
)

/**
 * One way out of a category.
 *
 * [conditions] are ANDed. The first transition of a category whose conditions all hold wins,
 * so their order is their priority.
 */
@Serializable
data class CategoryTransition(
    val target: Long,
    val conditions: List<RuleCondition> = emptyList(),
    /** A disabled transition is kept and editable but never fires. Defaults on, so rules
     *  written before this field — and older backups — stay active. */
    val enabled: Boolean = true,
    /**
     * Only set inside a backup, where ids mean nothing. See [CategoryRuleTargets].
     */
    val targetName: String? = null,
)

@Serializable
data class RuleCondition(
    val field: RuleField,
    val operator: RuleOperator = RuleOperator.GREATER,
    val value: Long = 0,
)

enum class RuleField {
    /** Chapters not read yet. */
    UNREAD,

    /** Chapters read. `READ = 0` is "never opened", `READ > 0` is "started". */
    READ,

    TOTAL_CHAPTERS,

    /**
     * Days since the last chapter was read.
     *
     * Never-read manga deliberately match nothing here. Their stored timestamp is 0, which
     * would read as infinitely long ago and quietly sweep the whole to-read pile into
     * whatever "forgotten" category the user built. Use [NEVER_READ] for that case.
     */
    DAYS_SINCE_READ,

    /** Days since the source last published a chapter, so "is it still running". */
    DAYS_SINCE_UPDATE,

    /** Compared against `SManga` status constants. */
    STATUS,

    /** True when the manga's extension is gone, so nothing can be fetched for it. */
    SOURCE_MISSING,

    /**
     * True when the extension is installed but no longer offered by its repository, so it
     * will break rather than has broken. This is the warning that arrives in time to migrate.
     */
    SOURCE_OBSOLETE,

    /**
     * Updates in a row that failed for a reason the source is responsible for. Connection
     * problems never count towards it, so it does not spike when the device is offline.
     */
    UPDATE_FAILURES,

    /** True when no chapter of this manga was ever read. */
    NEVER_READ,

    /** True when any tracker is bound to this manga. */
    TRACKED,

    /**
     * The tracker's reading status, normalised to [TrackerStatus].
     *
     * Services number their statuses differently, so the raw value is never compared: it is
     * mapped through the service that owns the track. A rule written while a manga was on
     * AniList therefore keeps working if it is later tracked on MAL instead.
     */
    TRACKER_STATUS,

    /**
     * The tracker's score on a 0-10 scale.
     *
     * Normalised the same way, because services score out of 10, out of 100, or in stars. An
     * unscored or untracked manga matches nothing rather than counting as a zero.
     */
    TRACKER_SCORE,
}

/**
 * Reading status as every tracker agrees on it.
 *
 * [id] is what a saved rule stores, so these numbers are part of the persisted format and must
 * not be renumbered. Anything a service offers beyond these three lands in [OTHER].
 */
enum class TrackerStatus(val id: Long) {
    READING(0),
    COMPLETED(1),
    PLANNING(2),
    OTHER(3),
}

enum class RuleOperator {
    GREATER,
    LESS,
    EQUAL,
}
