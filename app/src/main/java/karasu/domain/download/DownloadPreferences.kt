package karasu.domain.download

import eu.kanade.tachiyomi.core.preference.PreferenceStore

class DownloadPreferences(
    private val preferenceStore: PreferenceStore,
) {
    fun downloadWithId() = preferenceStore.getBoolean("download_with_id", false)

    /** How many unread chapters "download new chapters" keeps downloaded ahead; 0 every new one. See [DownloadNextUnread]. */
    fun newChaptersAhead() = preferenceStore.getInt("download_new_chapters_ahead", 2)
}
