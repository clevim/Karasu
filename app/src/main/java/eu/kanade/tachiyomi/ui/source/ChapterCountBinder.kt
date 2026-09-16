package eu.kanade.tachiyomi.ui.source

import eu.kanade.tachiyomi.domain.manga.models.Manga
import karasu.domain.chapter.interactor.GetSourceChapterCount
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Drives the chapter-count badge of one recycled view holder.
 *
 * Search results are bound and rebound constantly, so the request for the manga that scrolled
 * away is dropped rather than left to arrive on top of whatever the view shows now.
 */
class ChapterCountBinder(private val show: (Int?) -> Unit) {

    private val scope = MainScope()
    private var job: Job? = null
    private var mangaId: Long? = null

    fun bind(manga: Manga) {
        val sameManga = mangaId == manga.id
        // A rebind of the manga already being counted must not restart the wait below: a card
        // that rebinds every few hundred milliseconds — which is what a library update used to
        // make every visible card do — would cancel its own request forever and never show a
        // count at all.
        if (sameManga && job?.isActive == true) return

        job?.cancel()
        mangaId = manga.id
        val cached = GetSourceChapterCount.cached(manga.id)
        // Clearing is only right when the holder has been recycled onto a different entry.
        // Blanking the badge of the entry that is already showing one is just a flicker.
        if (cached != null || !sameManga) show(cached)
        if (cached != null) return
        job = scope.launch {
            // Flinging through a list binds dozens of holders that are gone a frame later.
            // Only the ones that stay put long enough are worth a request; the rebind that
            // replaces this one cancels the wait before it ever fires.
            delay(SETTLE_MS)
            val count = GetSourceChapterCount.await(manga)
            // Cancellation can lose the race with a source that answers at the wrong moment.
            if (mangaId == manga.id) show(count)
        }
    }

    private companion object {
        const val SETTLE_MS = 300L
    }
}
