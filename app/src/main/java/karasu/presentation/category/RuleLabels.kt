package karasu.presentation.category

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.model.SManga
import karasu.domain.category.models.RuleField
import karasu.domain.category.models.RuleOperator
import karasu.domain.category.models.TrackerStatus
import karasu.i18n.MR

fun RuleField.label(): StringResource = when (this) {
    RuleField.UNREAD -> MR.strings.rule_field_unread
    RuleField.READ -> MR.strings.rule_field_read
    RuleField.TOTAL_CHAPTERS -> MR.strings.rule_field_total_chapters
    RuleField.DAYS_SINCE_READ -> MR.strings.rule_field_days_since_read
    RuleField.DAYS_SINCE_UPDATE -> MR.strings.rule_field_days_since_update
    RuleField.DAYS_UNTIL_RELEASE -> MR.strings.rule_field_days_until_release
    RuleField.RELEASE_STALLED -> MR.strings.rule_field_release_stalled
    RuleField.STATUS -> MR.strings.rule_field_status
    RuleField.SOURCE_MISSING -> MR.strings.rule_field_source_missing
    RuleField.SOURCE_OBSOLETE -> MR.strings.rule_field_source_obsolete
    RuleField.UPDATE_FAILURES -> MR.strings.rule_field_update_failures
    RuleField.NEVER_READ -> MR.strings.rule_field_never_read
    RuleField.TRACKED -> MR.strings.rule_field_tracked
    RuleField.TRACKER_STATUS -> MR.strings.rule_field_tracker_status
    RuleField.TRACKER_SCORE -> MR.strings.rule_field_tracker_score
}

fun RuleOperator.label(): StringResource = when (this) {
    RuleOperator.GREATER -> MR.strings.rule_operator_greater
    RuleOperator.LESS -> MR.strings.rule_operator_less
    RuleOperator.EQUAL -> MR.strings.rule_operator_equal
}

/** Fields that are a number the user compares against, rather than a choice from a list. */
fun RuleField.isComparable(): Boolean = when (this) {
    RuleField.UNREAD,
    RuleField.READ,
    RuleField.TOTAL_CHAPTERS,
    RuleField.DAYS_SINCE_READ,
    RuleField.DAYS_SINCE_UPDATE,
    RuleField.DAYS_UNTIL_RELEASE,
    RuleField.UPDATE_FAILURES,
    RuleField.TRACKER_SCORE,
    -> true
    RuleField.RELEASE_STALLED,
    RuleField.STATUS,
    RuleField.SOURCE_MISSING,
    RuleField.SOURCE_OBSOLETE,
    RuleField.NEVER_READ,
    RuleField.TRACKED,
    RuleField.TRACKER_STATUS,
    -> false
}

/** The values a non-numeric field can take, as they should be offered to the user. */
fun RuleField.choices(): List<Pair<StringResource, Long>> = when (this) {
    RuleField.STATUS -> listOf(
        MR.strings.ongoing to SManga.ONGOING.toLong(),
        MR.strings.completed to SManga.COMPLETED.toLong(),
        MR.strings.publishing_finished to SManga.PUBLISHING_FINISHED.toLong(),
        MR.strings.on_hiatus to SManga.ON_HIATUS.toLong(),
        MR.strings.cancelled to SManga.CANCELLED.toLong(),
        MR.strings.licensed to SManga.LICENSED.toLong(),
        MR.strings.unknown to SManga.UNKNOWN.toLong(),
    )
    RuleField.TRACKER_STATUS -> listOf(
        MR.strings.rule_tracker_reading to TrackerStatus.READING.id,
        MR.strings.rule_tracker_completed to TrackerStatus.COMPLETED.id,
        MR.strings.rule_tracker_planning to TrackerStatus.PLANNING.id,
        MR.strings.rule_tracker_other to TrackerStatus.OTHER.id,
    )
    else -> listOf(
        MR.strings.rule_value_yes to 1L,
        MR.strings.rule_value_no to 0L,
    )
}
