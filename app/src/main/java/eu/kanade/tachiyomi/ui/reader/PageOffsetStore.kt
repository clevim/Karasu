package eu.kanade.tachiyomi.ui.reader

import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import kotlinx.serialization.json.Json

/**
 * How far into its last read page each chapter was left, 0..1 of the page's height.
 *
 * A page index is the unit the rest of the app saves, and in a webtoon one page can be a strip
 * several screens tall — resuming at its top loses minutes of reading. A fraction rather than
 * pixels, so it survives a different phone, width or zoom.
 *
 * ponytail: a JSON map in one preference, capped at [MAX] chapters, most recent kept. Not the
 * chapter's `memo` — that one belongs to the source and is overwritten on every chapter sync.
 * A column on `chapters` is the upgrade if this should reach backups.
 */
class PageOffsetStore(private val preferences: PreferencesHelper) {

    private fun read(): Map<String, Float> =
        runCatching { Json.decodeFromString<Map<String, Float>>(preferences.readerPageOffsets().get()) }
            .getOrDefault(emptyMap())

    fun get(chapterId: Long?): Float = chapterId?.let { read()[it.toString()] } ?: 0f

    fun set(chapterId: Long, fraction: Float) {
        val map = read().toMutableMap()
        // Re-inserted so the entry moves to the end: insertion order is recency.
        map.remove(chapterId.toString())
        if (fraction > 0f) map[chapterId.toString()] = fraction
        while (map.size > MAX) map.remove(map.keys.first())
        preferences.readerPageOffsets().set(Json.encodeToString(map))
    }

    private companion object {
        const val MAX = 500
    }
}
