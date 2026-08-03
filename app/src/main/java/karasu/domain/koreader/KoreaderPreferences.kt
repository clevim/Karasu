package karasu.domain.koreader

import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore

/**
 * Settings for pushing chapters to a self-hosted KOReader shelf.
 *
 * The shelf is a container the user runs; Karasu uploads CBZ files to it and reads back which
 * ones the KOReader plugin reported as finished. Nothing here is on by default — an empty
 * [serverUrl] is what "feature off" looks like, so no job runs until one is entered.
 */
class KoreaderPreferences(private val preferenceStore: PreferenceStore) {

    /** Base URL of the shelf container, e.g. `http://192.168.1.10:3000`. Blank disables sync. */
    fun serverUrl() = preferenceStore.getString("koreader_server_url", "")

    /** Sent as a bearer token. Blank means the container was left unauthenticated. */
    fun apiKey() = preferenceStore.getString("koreader_api_key", "")

    /**
     * Ids of the manga that get pushed, as strings.
     *
     * Picked one entry at a time from its details screen rather than by category: what belongs on
     * a device with room for ten things is a per-manga decision, and expressing it as a category
     * meant maintaining a category that exists only for this. A preference rather than a database
     * column keeps it out of the schema — a set of ids is exactly what the sync needs to read.
     */
    fun shelfManga() = preferenceStore.getStringSet("koreader_shelf_manga", emptySet())

    /**
     * Extra conditions a manga must also satisfy, as a serialised `List<RuleCondition>`.
     *
     * Marking says roughly what you care about; this narrows it to what is worth carrying on a
     * device with limited room — "and it has unread chapters", "and I actually started it".
     * Blank means the marked manga alone decide.
     */
    fun selectionConditions() = preferenceStore.getString("koreader_selection_conditions", "")

    /** How many unread chapters of each manga to keep on the shelf. */
    fun chaptersPerManga() = preferenceStore.getInt("koreader_chapters_per_manga", 3)

    /** How many manga to keep on the shelf, most recently updated first. */
    fun maxManga() = preferenceStore.getInt("koreader_max_manga", 10)

    /** Hours between automatic syncs. 0 turns the periodic job off, leaving manual sync. */
    fun syncInterval() = preferenceStore.getInt("koreader_sync_interval", 12)

    /** Whether a chapter the plugin reports as finished is marked read in the library. */
    fun markReadFromDevice() = preferenceStore.getBoolean("koreader_mark_read", true)

    /** Uploads are whole CBZ files, so metered connections are opt-in. */
    fun onlyOverWifi() = preferenceStore.getBoolean("koreader_only_over_wifi", true)

    /**
     * Whether a chapter's pages are cut into e-reader-sized pieces before being uploaded.
     *
     * A webtoon strip is one image thousands of pixels tall; KOReader scales it to fit one screen,
     * which is why it arrives unreadable. On by default because that is what the shelf is for —
     * turn it off to send whatever the downloader produced, untouched.
     */
    fun optimizePages() = preferenceStore.getBoolean("koreader_optimize_pages", true)

    /**
     * Screen width in pixels of the device that reads the shelf, or 0 to send pages at full size.
     *
     * A page wider than the screen is detail the device throws away on every render, so shrinking
     * it to fit costs nothing visible and cuts the file — and the transfer — substantially.
     *
     * The matching screen height is looked up from this, so it also decides the shape pages are
     * cut to. Every e-ink device worth listing has a distinct width, which is why there is no
     * second setting for the height.
     */
    fun deviceScreenWidth() = preferenceStore.getInt("koreader_device_width", 0)

    /**
     * Drops colour, which a greyscale screen cannot show but a JPEG still pays to store.
     *
     * Doubles as the "this is an e-ink screen" signal: it also turns on the contrast stretch that
     * makes washed-out scans readable on a display with no backlight.
     */
    fun grayscalePages() = preferenceStore.getBoolean("koreader_grayscale_pages", false)

    /**
     * Which half of a double-page spread is shown first once it is cut in two.
     *
     * On by default because the shelf carries manga. Wrong for a western comic, where it would
     * hand you the right-hand page before the left one.
     */
    fun rightToLeft() = preferenceStore.getBoolean("koreader_right_to_left", true)

    /**
     * When the last sync finished, and what it did.
     *
     * A sync is a background reconcile with no notification, so without this the only trace of a
     * run is a log line the user cannot see — "Sync now" would say "started" and then nothing
     * forever. Recorded on failure too: "it failed and here is why" is the answer people are
     * actually looking for.
     *
     * App state rather than a setting: it describes what happened, not what the user chose, and
     * restoring someone else's last-sync line onto a fresh device would be nonsense.
     */
    fun lastSyncAt() = preferenceStore.getLong(Preference.appStateKey("koreader_last_sync_at"), 0L)

    /** Already localised when written, because only the job knows what happened. */
    fun lastSyncSummary() = preferenceStore.getString(Preference.appStateKey("koreader_last_sync_summary"), "")
}
