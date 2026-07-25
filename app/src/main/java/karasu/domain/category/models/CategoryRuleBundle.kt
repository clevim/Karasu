package karasu.domain.category.models

import kotlinx.serialization.Serializable

/**
 * A set of category rules on their own, outside a full backup.
 *
 * Rules take real effort to write and are the only part of a library that is worth handing to
 * someone else — everything else in a backup is personal. This is that part, shareable.
 */
@Serializable
data class CategoryRuleBundle(
    val version: Int = VERSION,
    val rules: List<ExportedCategoryRule> = emptyList(),
) {
    companion object {
        /** Bumped only if the shape below stops being readable by an older build. */
        const val VERSION = 1
    }
}

@Serializable
data class ExportedCategoryRule(
    /** Matched by name on import, the same way a backup matches categories. */
    val category: String,
    /**
     * The rule, serialised exactly as a backup stores it: targets carried by name rather than
     * id, because ids mean nothing on another device. See `CategoryRuleTargets`.
     */
    val rule: String,
)
