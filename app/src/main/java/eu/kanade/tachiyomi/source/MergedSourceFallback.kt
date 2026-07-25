package eu.kanade.tachiyomi.source

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import karasu.domain.chapter.services.ChapterRecognition
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.merged.interactor.MergedSources
import kotlin.math.abs
import kotlinx.coroutines.CancellationException

/**
 * Page list retrieval that can fall back to a manga's other sources.
 *
 * The reading list a merged manga shows is assembled by `GetChapter`, and every chapter in
 * it keeps the row — and therefore the source — it came from. This resolves the right
 * source for a chapter and, if that source can't serve it, looks the same chapter *number*
 * up on the manga's other sources in priority order.
 *
 * Existing behaviour is preserved on total failure: if the first source threw, that same
 * throwable is rethrown, and if it merely returned nothing we return nothing — so callers'
 * error handling is unchanged for a manga with no merged sources.
 */
class MergedSourceFallback(
    private val sourceManager: SourceManager,
    private val mergedSources: MergedSources,
    private val getManga: GetManga,
) {

    suspend fun getPageList(mangaId: Long, chapter: Chapter, primary: HttpSource): List<Page> {
        val parent = getManga.awaitById(mangaId)

        // A merged chapter is stored under the manga row of the source it came from, so ask
        // that source first — the primary has never seen its url and would only 404.
        val owner = chapter.manga_id?.takeIf { it != mangaId }?.let { getManga.awaitById(it) }
        val preferred = owner?.let { sourceManager.get(it.source) as? HttpSource } ?: primary

        val preferredFailure = try {
            val pages = preferred.getPageList(chapter)
            if (pages.isNotEmpty()) return pages
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            e
        }

        // Everywhere else this manga lives — the primary included, since the chapter we
        // just failed on may have come from a merged source instead.
        val alternates = buildList {
            parent?.let { add(it.source to it.url) }
            mergedSources.await(mangaId).forEach { add(it.source to it.url) }
        }.filter { (sourceId, _) -> sourceId != preferred.id }

        if (alternates.isNotEmpty()) {
            val mangaTitle = parent?.title.orEmpty()
            for ((sourceId, mangaUrl) in alternates) {
                val source = sourceManager.get(sourceId) as? HttpSource ?: continue
                val pages = try {
                    val candidates = source.getChapterList(SManga.create().apply { url = mangaUrl })
                    val match = matchChapter(candidates, chapter.chapter_number, mangaTitle)
                    if (match == null) {
                        Logger.i { "Merged fallback: no chapter ${chapter.chapter_number} on ${source.name}" }
                        continue
                    }
                    source.getPageList(match)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Logger.w(e) { "Merged fallback: ${source.name} failed for manga $mangaId" }
                    continue
                }
                if (pages.isNotEmpty()) {
                    Logger.i { "Merged fallback: served chapter ${chapter.chapter_number} from ${source.name}" }
                    return pages
                }
            }
        }

        preferredFailure?.let { throw it }
        return emptyList()
    }
}

/**
 * Chapter numbers are Float here but REAL in the database, so comparing them with `==`
 * would drop matches on numbers like 11.1 that don't survive the round trip. Chapters are
 * never this close together in practice.
 */
private const val CHAPTER_NUMBER_TOLERANCE = 0.001f

/**
 * Finds the chapter matching [target] among [candidates].
 *
 * Chapter urls differ per source, so the chapter number is the only key that carries across
 * sources. [ChapterRecognition] prefers the number the source declares and falls back to
 * parsing the chapter name. An unrecognised [target] (< 0) has nothing to match on, so it
 * deliberately matches nothing rather than guessing.
 */
internal fun matchChapter(
    candidates: List<SChapter>,
    target: Float,
    mangaTitle: String,
): SChapter? {
    if (target < 0f) return null
    return candidates.firstOrNull { candidate ->
        val number = ChapterRecognition.parseChapterNumber(candidate.name, mangaTitle, candidate.chapter_number)
        abs(number - target) < CHAPTER_NUMBER_TOLERANCE
    }
}
