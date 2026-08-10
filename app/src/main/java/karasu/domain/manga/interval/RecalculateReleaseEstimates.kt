package karasu.domain.manga.interval

import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.interactor.GetLibraryManga

/**
 * Rebuilds every release estimate from the chapters already on disk.
 *
 * Normally an estimate is written as a by-product of fetching, so a library that has never run
 * an update has an empty calendar even though the chapter list it needs has been sitting in the
 * database the whole time. This reads that history instead of the network: nothing is requested
 * from any source, so it is safe to offer as a button and safe to press twice.
 *
 * It is also the repair path. A restored backup, a run of updates that all failed, or simply
 * changing what the app considers a rhythm all leave estimates that no longer match the data;
 * this recomputes them from scratch rather than waiting weeks for the schedule to correct itself.
 */
class RecalculateReleaseEstimates(
    private val getLibraryManga: GetLibraryManga,
    private val getChapter: GetChapter,
    private val fetchInterval: FetchInterval,
) {
    /**
     * @return how many entries came out with a usable estimate. The rest have too few chapters,
     *  or a source that reports no dates and has not been watched long enough — both of which
     *  the calendar already shows as "no estimate yet".
     */
    suspend fun await(): Int {
        var placed = 0
        getLibraryManga.await()
            .distinctBy { it.manga.id }
            .forEach { libraryManga ->
                val id = libraryManga.manga.id ?: return@forEach
                // The merged list rather than the manga's own rows: for a merged entry the
                // chapters that arrive are the merged ones, and that is the rhythm the user sees.
                val chapters = getChapter.awaitAll(id, false)
                if (chapters.isEmpty()) return@forEach
                val written = fetchInterval.record(
                    mangaId = id,
                    uploadDates = chapters.map { it.date_upload },
                    fetchDates = chapters.map { it.date_fetch },
                )
                if (written) placed++
            }
        return placed
    }
}
