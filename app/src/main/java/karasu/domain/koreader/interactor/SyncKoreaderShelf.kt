package karasu.domain.koreader.interactor

import co.touchlab.kermit.Logger
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.LibraryManga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.SourceManager
import karasu.data.koreader.KoreaderApi
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.category.interactor.BuildRuleInputs
import karasu.domain.category.models.RuleCondition
import karasu.domain.category.models.matches
import karasu.domain.chapter.models.ChapterUpdate
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.koreader.models.KoreaderShelfEntry
import karasu.domain.koreader.models.KoreaderUpload
import karasu.domain.koreader.models.canMarkRead
import karasu.domain.manga.interactor.GetLibraryManga
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json

/**
 * Reconciles the KOReader shelf with the library, in that order: read state comes back first,
 * then the shelf is refilled with what should be on it now.
 *
 * Pulling first matters. A chapter the user finished on the device is no longer a candidate to
 * keep on the shelf, so marking it read before choosing what to upload means the freed slot is
 * filled in the same run instead of the next one.
 */
class SyncKoreaderShelf(
    private val api: KoreaderApi,
    private val preferences: KoreaderPreferences,
    private val getLibraryManga: GetLibraryManga,
    private val getChapter: GetChapter,
    private val updateChapter: UpdateChapter,
    private val downloadManager: DownloadManager,
    private val downloadProvider: DownloadProvider,
    private val sourceManager: SourceManager,
    private val buildRuleInputs: BuildRuleInputs,
    private val prepareCbz: PrepareShelfCbz,
) {

    data class Summary(
        val markedRead: Int = 0,
        val uploaded: Int = 0,
        val removed: Int = 0,
        val queuedForDownload: Int = 0,
    )

    suspend fun await(): Summary {
        if (!api.isConfigured) return Summary()

        val shelf = api.shelf()
        val markedRead = pullReadState(shelf)
        val wanted = wantedChapters()

        val onShelf = shelf.associateBy { it.chapterId }
        val missing = wanted.filterNot { onShelf.containsKey(it.chapter.id) }
        val (ready, notDownloaded) = missing.partition { findCbz(it) != null }

        // Not downloaded, or downloaded as loose images. Queued rather than zipped here: the
        // downloader already produces the exact CBZ this would build, when "save chapters as CBZ"
        // is on. Grouped per manga because each call re-scans the download directory, and the
        // downloader already drops chapters that are downloaded or queued.
        notDownloaded.groupBy { it.manga.manga }.forEach { (manga, candidates) ->
            downloadManager.downloadChapters(manga, candidates.map { it.chapter })
        }

        var uploaded = 0
        ready.forEach { candidate ->
            val cbz = findCbz(candidate) ?: return@forEach
            // The downloaded file is what the phone's reader wants; the device wants pages it can
            // actually fit on a screen. Null means the two happen to be the same file.
            val prepared = prepareCbz.await(cbz, candidate.chapter.id!!)
            try {
                if (upload(candidate, prepared ?: cbz)) uploaded++
            } finally {
                prepared?.delete()
            }
        }

        // Anything on the shelf that is no longer wanted goes, which is what keeps the device
        // inside the manga and chapter limits instead of accumulating everything ever pushed.
        val wantedIds = wanted.mapNotNull { it.chapter.id }.toSet()
        var removed = 0
        shelf.filterNot { it.chapterId in wantedIds }.forEach { entry ->
            if (delete(entry.chapterId)) removed++
        }

        return Summary(markedRead, uploaded, removed, notDownloaded.size)
    }

    /**
     * One failed transfer must not abandon the rest of the run, but a cancelled worker must stop
     * immediately — so [CancellationException] is rethrown instead of being counted as a failure.
     * Without that, stopping the job would leave it uploading the whole shelf in the background.
     */
    private suspend fun upload(candidate: Candidate, cbz: UniFile): Boolean = try {
        api.upload(candidate.toUpload(), cbz)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(e) { "Failed to upload chapter ${candidate.chapter.id} to the shelf" }
        false
    }

    private suspend fun delete(chapterId: Long): Boolean = try {
        api.delete(chapterId)
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(e) { "Failed to remove chapter $chapterId from the shelf" }
        false
    }

    /**
     * Marks locally what the plugin reported as finished.
     *
     * The shelf is keyed by Karasu's chapter id, which a restored backup renumbers, so the url
     * that travelled with the entry is checked against the row that id points at now. A mismatch
     * means the shelf is talking about a chapter that no longer exists here — skipping it is the
     * difference between a stale sync and silently marking an unrelated chapter as read.
     */
    private suspend fun pullReadState(shelf: List<KoreaderShelfEntry>): Int {
        if (!preferences.markReadFromDevice().get()) return 0

        val updates = shelf.filter { it.read }.mapNotNull { entry ->
            val chapter = getChapter.awaitById(entry.chapterId)
            if (!entry.canMarkRead(chapter)) {
                if (chapter != null && chapter.url != entry.chapterUrl) {
                    Logger.w { "Shelf entry ${entry.chapterId} no longer matches a local chapter, ignoring" }
                }
                return@mapNotNull null
            }
            ChapterUpdate(id = entry.chapterId, read = true)
        }

        if (updates.isNotEmpty()) updateChapter.awaitAll(updates)
        return updates.size
    }

    private data class Candidate(val manga: LibraryManga, val chapter: Chapter)

    private fun Candidate.toUpload() = KoreaderUpload(
        chapterId = chapter.id!!,
        chapterUrl = chapter.url,
        mangaTitle = manga.manga.title,
        chapterName = chapter.name,
        chapterNumber = chapter.chapter_number,
        sourceId = manga.manga.source,
    )

    /**
     * The chapters that should be on the shelf right now: the next unread ones of the most
     * recently updated manga marked for the shelf, capped by both limits.
     */
    private suspend fun wantedChapters(): List<Candidate> {
        val perManga = preferences.chaptersPerManga().get().coerceAtLeast(1)

        return selectedManga(selectionConditions())
            .take(preferences.maxManga().get().coerceAtLeast(1))
            .flatMap { manga ->
                val mangaId = manga.manga.id ?: return@flatMap emptyList()
                getChapter.awaitUnread(mangaId, manga.manga.filtered_scanlators?.isNotEmpty() == true)
                    .filter { it.id != null }
                    .sortedBy { it.chapter_number }
                    .take(perManga)
                    .map { Candidate(manga, it) }
            }
    }

    /**
     * How many manga [conditions] would select right now, ignoring the shelf's size limit.
     *
     * The filter editor shows this so a condition can be judged before the next sync acts on it.
     * The limit is left out on purpose: capping the number is what the limit is for, and folding
     * it in here would make every filter look like it matched exactly ten things.
     */
    suspend fun countSelectedManga(conditions: List<RuleCondition>): Int =
        selectedManga(conditions).size

    /** The manga marked for the shelf that also satisfy [conditions], most recently updated first. */
    private suspend fun selectedManga(conditions: List<RuleCondition>): List<LibraryManga> {
        val marked = preferences.shelfManga().get().mapNotNull { it.toLongOrNull() }.toSet()
        if (marked.isEmpty()) return emptyList()

        // Only built when there is something to evaluate, since it reads the whole library's
        // failure counts and possibly its trackers.
        val inputs = if (conditions.isEmpty()) emptyMap() else buildRuleInputs.await(conditions)
        val now = System.currentTimeMillis()

        return getLibraryManga.await()
            .filter { it.manga.id in marked }
            .filter { row ->
                if (conditions.isEmpty()) return@filter true
                val input = inputs[row.manga.id] ?: return@filter false
                conditions.matches(input, now)
            }
            .distinctBy { it.manga.id }
            .sortedByDescending { it.latestUpdate }
    }

    /** An unreadable filter selects nothing rather than everything, so a corrupt setting is visible. */
    private fun selectionConditions(): List<RuleCondition> {
        val raw = preferences.selectionConditions().get().takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            json.decodeFromString<List<RuleCondition>>(raw)
        } catch (e: Exception) {
            Logger.e(e) { "Ignoring unreadable KOReader selection filter" }
            emptyList()
        }
    }

    /** The chapter's downloaded CBZ, or null when it is missing or was stored as loose images. */
    private fun findCbz(candidate: Candidate) = downloadProvider
        .findChapterDir(
            candidate.chapter,
            candidate.manga.manga,
            sourceManager.getOrStub(candidate.manga.manga.source),
        )
        ?.takeIf { it.isFile && it.name?.endsWith(".cbz") == true }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
