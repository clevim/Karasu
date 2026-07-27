package karasu.domain.chapter.interactor

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.SourceManager
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import uy.kohesive.injekt.injectLazy

/**
 * How many chapters a source actually has for a manga it listed in search results.
 *
 * A source that returns a manga but has nothing to read under it is common, and until now the
 * only way to find out was to open the entry. The count is worth one extra request per result:
 * an answer is remembered for the whole session, so scrolling back over a result is free.
 */
object GetSourceChapterCount {

    private val sourceManager: SourceManager by injectLazy()
    private val getChapter: GetChapter by injectLazy()

    /**
     * Owns the requests instead of the view holders.
     *
     * The database read and `getChapterList` both block the thread they run on — the Rx path
     * behind it calls `execute()` inline — so running them on the caller froze the screen. They
     * also survive the holder that asked: a result that scrolls away still fills the cache
     * rather than being thrown out half-done and asked for again on the next bind.
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val counts = ConcurrentHashMap<Long, Deferred<Int?>>()

    // ponytail: three at a time. Global search already fans out over every enabled source at
    // once, and this adds a chapter-list request per result on top of that. Raise it only if
    // counts start arriving too slowly to be useful.
    private val semaphore = Semaphore(3)

    /** The count if it is already known, without touching the network. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cached(mangaId: Long?): Int? = mangaId?.let { counts[it] }
        ?.takeIf { it.isCompleted }
        ?.let { runCatching { it.getCompleted() }.getOrNull() }

    /** The count, fetching it once if needed. Null when the source could not be asked. */
    suspend fun await(manga: Manga): Int? {
        val id = manga.id ?: return null
        // One request per manga, shared by every holder that asks for it.
        return counts.getOrPut(id) { scope.async { fetch(manga, id) } }.await()
    }

    private suspend fun fetch(manga: Manga, id: Long): Int? {
        // A manga that was opened before — or is in the library — already has its chapters
        // stored, and that is both free and authoritative.
        val stored = getChapter.awaitAllRaw(id, false).size
        if (stored > 0) return stored

        val source = sourceManager.get(manga.source) ?: return null
        return try {
            semaphore.withPermit { source.getChapterList(manga).size }
        } catch (e: CancellationException) {
            counts.remove(id)
            throw e
        } catch (e: Throwable) {
            // A source that can't answer stays unknown rather than being shown as empty:
            // "0 chapters" and "the source is down" mean very different things to the user.
            // ponytail: the failure is remembered too, so a source that is down or rate
            // limiting isn't asked again on every rebind. Costs a retry until the next launch.
            Logger.w(e) { "Failed to count chapters for manga $id" }
            null
        }
    }
}
