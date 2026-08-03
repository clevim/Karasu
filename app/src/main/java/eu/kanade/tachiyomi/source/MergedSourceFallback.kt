package eu.kanade.tachiyomi.source

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import karasu.domain.chapter.services.ChapterRecognition
import karasu.domain.manga.failures.ReadFailures
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
 * The fallback stays inside the language being read. `GetChapter` already collapses one row
 * per language and puts the others in `Chapter.alternates`, so switching language is the
 * user's choice from the chapter list, never something that happens behind their back when a
 * source misbehaves. Two sources in the same language are the same read, so swapping between
 * them silently is exactly what's wanted.
 *
 * Existing behaviour is preserved on total failure: if the first source threw, that same
 * throwable is rethrown, and if it merely returned nothing we return nothing — so callers'
 * error handling is unchanged for a manga with no merged sources.
 */
class MergedSourceFallback(
    private val sourceManager: SourceManager,
    private val mergedSources: MergedSources,
    private val getManga: GetManga,
    private val readFailures: ReadFailures,
) {

    /**
     * The manga row [chapter] is actually stored under, or null when that is [mangaId] itself.
     *
     * A merged chapter belongs to its own source's row, so anything that fetches it — pages,
     * image urls, the images themselves, the download directory — has to go through that
     * source. The parent's has never seen the chapter's url and its headers are the wrong ones.
     */
    suspend fun ownerOf(mangaId: Long, chapter: Chapter): Manga? =
        chapter.manga_id?.takeIf { it != mangaId }?.let { getManga.awaitById(it) }

    /** Whether [mangaId] is merged, and so has anywhere to fall back to. A cached set lookup. */
    suspend fun hasAlternates(mangaId: Long): Boolean = mergedSources.hasMerges(mangaId)

    suspend fun getPageList(mangaId: Long, chapter: Chapter, primary: HttpSource): List<Page> =
        getPages(mangaId, chapter, primary).pages

    /**
     * As [getPageList], but says which source actually served the pages.
     *
     * Callers need it: the images are fetched against the serving source's urls, headers and
     * client, so using the one they started from would fail every image of a chapter the
     * fallback rescued.
     *
     * [exclude] holds source ids already known to be broken for this chapter. Passing the
     * preferred source in it skips it entirely, which is how a caller asks for "the next
     * source after the one I'm on".
     */
    suspend fun getPages(
        mangaId: Long,
        chapter: Chapter,
        primary: HttpSource,
        exclude: Set<Long> = emptySet(),
    ): SourcedPages {
        val parent = getManga.awaitById(mangaId)

        val owner = ownerOf(mangaId, chapter)
        val preferred = owner?.let { sourceManager.get(it.source) as? HttpSource } ?: primary

        // The chapter's own source knows its url; the rest have to look the number up. Everywhere
        // else this manga lives is a candidate — the primary included, since the chapter we may be
        // failing on came from a merged source instead.
        val candidates = buildList {
            add(Candidate(preferred, null))
            parent?.takeIf { it.source != preferred.id }
                ?.let { addCandidate(it.source, it.url) }
            mergedSources.await(mangaId)
                .filter { it.source != preferred.id }
                .forEach { addCandidate(it.source, it.url) }
        }
            .filter { it.source.id !in exclude && it.source.lang == preferred.lang }
            // A source that just failed goes to the back of the queue rather than being dropped:
            // when every source is cooling down, a stale one is still better than an error. Sort
            // is stable, so the user's priority order survives inside each group.
            .sortedBy { readFailures.isRecent(mangaId, it.source.id) }

        val mangaTitle = parent?.title.orEmpty()
        var preferredFailure: Throwable? = null

        for (candidate in candidates) {
            val source = candidate.source
            val pages = try {
                val target = if (candidate.mangaUrl == null) {
                    chapter
                } else {
                    val chapters = source.getChapterList(SManga.create().apply { url = candidate.mangaUrl })
                    matchChapter(chapters, chapter.chapter_number, mangaTitle)
                }
                if (target == null) {
                    // The source doesn't carry this chapter. Worth remembering, or every reopen
                    // pays another chapter-list request to find the same nothing.
                    Logger.i { "Merged fallback: no chapter ${chapter.chapter_number} on ${source.name}" }
                    readFailures.record(mangaId, source.id, mangaTitle, MISSING_CHAPTER_MESSAGE)
                    continue
                }
                source.getPageList(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.w(e) { "Merged fallback: ${source.name} failed for manga $mangaId" }
                readFailures.record(mangaId, source.id, mangaTitle, e.message)
                if (source.id == preferred.id) preferredFailure = e
                continue
            }
            // A chapter with no pages is a failure like any other, however politely the source
            // phrased it.
            if (pages.isEmpty()) {
                readFailures.record(mangaId, source.id, mangaTitle, NO_PAGES_MESSAGE)
                continue
            }
            if (source.id != preferred.id) {
                Logger.i { "Merged fallback: served chapter ${chapter.chapter_number} from ${source.name}" }
            }
            readFailures.clear(mangaId, source.id)
            return SourcedPages(source, pages)
        }

        preferredFailure?.let { throw it }
        return SourcedPages(preferred, emptyList())
    }

    /**
     * Repoints [pages] at the next source that can serve [chapter], and returns it.
     *
     * This is the recovery for a source that hands out a perfectly good page list and then fails
     * on the images it points at — a dead image host, hotlink protection, an error page served
     * with a 200. Both the reader and the downloader hit it, and both are already holding the
     * [Page] objects by the time they find out, so the new source's page list can't replace them:
     * only its image urls are written over the old ones. That rules out a source that resolves
     * images lazily (a page list with no image urls) and one whose page list is shorter, since
     * neither can repoint what the caller already has; both are skipped for the source after them.
     *
     * Pages already [Page.State.Ready] are left alone — they are downloaded and they are fine.
     *
     * [tried] is the caller's set of sources already known to be broken for this chapter; it is
     * added to here, which is what stops the walk instead of looping over the same sources.
     * Returns null when nothing else can serve the chapter.
     */
    suspend fun switchSource(
        mangaId: Long,
        chapter: Chapter,
        primary: HttpSource,
        pages: List<Page>,
        tried: MutableSet<Long>,
    ): HttpSource? {
        while (true) {
            // Every source failure is already remembered as "no more sources": getPages only
            // throws once it has exhausted them, which is exactly when there is nothing to switch.
            val next = try {
                getPages(mangaId, chapter, primary, tried)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return null
            }
            if (next.pages.isEmpty()) return null
            tried += next.source.id

            if (next.pages.size < pages.size || next.pages.any { it.imageUrl.isNullOrEmpty() }) {
                Logger.w { "Can't switch to ${next.source.name}: its page list doesn't line up" }
                continue
            }

            Logger.i { "Switching ${chapter.name} to ${next.source.name}" }
            pages.forEachIndexed { index, page ->
                if (page.status is Page.State.Ready) return@forEachIndexed
                page.imageUrl = next.pages[index].imageUrl
                page.status = Page.State.Queue
            }
            return next.source
        }
    }

    /** Adds [sourceId] as a candidate, skipping it when it isn't installed or isn't an http source. */
    private fun MutableList<Candidate>.addCandidate(sourceId: Long, mangaUrl: String) {
        val source = sourceManager.get(sourceId) as? HttpSource ?: return
        add(Candidate(source, mangaUrl))
    }
}

/** A source that might be able to serve a chapter. [mangaUrl] is null for the chapter's own source. */
private class Candidate(val source: HttpSource, val mangaUrl: String?)

// Stand-ins for the exception a throwing source would have given, so the broken-sources screen has
// something to show for the two failures that arrive as a quiet absence instead.
private const val MISSING_CHAPTER_MESSAGE = "Chapter not found on this source"
private const val NO_PAGES_MESSAGE = "Source returned no pages"

/** A page list together with the source it came from. Empty pages mean nothing could serve it. */
data class SourcedPages(val source: HttpSource, val pages: List<Page>)

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
