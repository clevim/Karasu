package karasu.translation.model

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One queued chapter translation. */
data class Translation(
    val source: Source,
    val manga: Manga,
    val chapter: Chapter,
) {
    private val _statusFlow = MutableStateFlow(State.NOT_TRANSLATED)
    val statusFlow = _statusFlow.asStateFlow()

    private val _progressFlow = MutableStateFlow(Progress())
    val progressFlow = _progressFlow.asStateFlow()

    var status: State
        get() = _statusFlow.value
        set(value) {
            _statusFlow.value = value
        }

    /** Why it failed, when [status] is [State.ERROR]. The engine's own words, for a toast. */
    var error: String? = null

    var progress: Progress
        get() = _progressFlow.value
        set(value) {
            _progressFlow.value = value
        }

    enum class State {
        NOT_TRANSLATED,
        QUEUE,
        TRANSLATING,
        TRANSLATED,
        ERROR,
    }
}

/**
 * How far a chapter's translation has got, so a caller can show it rather than a spinner.
 *
 * The two phases are not interchangeable: reading is per page and takes most of the wall clock on
 * a long chapter, while translating is a handful of network requests for the whole chapter at
 * once and cannot be counted in pages.
 */
data class Progress(
    val pagesRead: Int = 0,
    val pages: Int = 0,
    val phase: Phase = Phase.READING,
) {
    enum class Phase { READING, TRANSLATING }
}
