package karasu.domain.library

import eu.kanade.tachiyomi.core.preference.PreferenceStore

class LibraryPreferences(private val preferenceStore: PreferenceStore) {
    fun randomSortSeed() = preferenceStore.getInt("library_random_sort_seed", 0)

    fun markDuplicateReadChapterAsRead() = preferenceStore.getStringSet("mark_duplicate_read_chapter_read", emptySet())

    /** When the calendar-driven sweep of the category rules last ran. See `ApplyCategoryRules`. */
    fun lastCategoryRuleSweep() = preferenceStore.getLong("last_category_rule_sweep", 0)

    companion object {
        const val MARK_DUPLICATE_READ_CHAPTER_READ_NEW = "new"
        const val MARK_DUPLICATE_READ_CHAPTER_READ_EXISTING = "existing"
    }
}
