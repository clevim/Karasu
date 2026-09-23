package karasu.translation

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.system.launchIO
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.manga.interactor.GetManga
import karasu.translation.data.TranslationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import karasu.translation.model.sourceText
import karasu.translation.model.TranslationBlock
import karasu.domain.translation.TranslationPreferences
import karasu.translation.data.TranslationCache
import karasu.translation.model.PageTranslation
import karasu.translation.model.Translation
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import uy.kohesive.injekt.injectLazy

class TranslationManager(context: Context) {

    private val provider: TranslationProvider by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val getChapter: GetChapter by injectLazy()
    private val sourceManager: SourceManager by injectLazy()

    private val translator = ChapterTranslator(context)
    private val cache: TranslationCache by injectLazy()
    private val preferences: TranslationPreferences by injectLazy()

    /** Bumped whenever a translation on screen is edited, so the overlays redraw. */
    val revision = MutableStateFlow(0)

    /**
     * Writes the reader's correction of one bubble: into the chapter's file, so it survives
     * reopening, and into the cache, so the same line comes out corrected in later chapters.
     */
    suspend fun correctTranslation(
        manga: Manga,
        chapter: Chapter,
        source: Source,
        pageKey: String,
        page: PageTranslation,
        block: TranslationBlock,
        corrected: String,
    ) {
        block.translation = corrected
        val all = getChapterTranslation(manga, chapter, source).toMutableMap()
        all[pageKey] = page
        val dir = provider.getMangaDir(manga, source) ?: return
        val file = dir.createFile(provider.getTranslationFileName(chapter)) ?: return
        file.openOutputStream().use { Json.encodeToStream(all.toMap(), it) }
        cache.store(
            translator.engineKey(translator.contextFor(manga.id)),
            preferences.translateFrom().get().code,
            preferences.translateTo().get(),
            mapOf(block.sourceText to corrected),
        )
        revision.value++
    }

    suspend fun translateChapter(manga: Manga, chapter: Chapter, source: Source) {
        translator.queueChapter(manga, chapter, source)
    }

    /**
     * Queues the chapter, or returns null when it already has a translation.
     *
     * Handed back rather than only awaited so a caller can show what it is waiting on: the entry
     * carries the status and the page progress.
     */
    suspend fun queueChapter(manga: Manga, chapter: Chapter, source: Source): Translation? =
        translator.queueChapter(manga, chapter, source)

    /** Suspends until [translation] finishes. @return null when it worked, the reason when not. */
    suspend fun awaitTranslation(translation: Translation): String? {
        val status = translation.statusFlow
            .first { it == Translation.State.TRANSLATED || it == Translation.State.ERROR }
        if (status == Translation.State.TRANSLATED) return null
        return translation.error.orEmpty()
    }

    /** Stops a translation the user no longer wants, part way through if it is already running. */
    fun cancelTranslation(chapter: Chapter) = translator.cancel(chapter)

    /**
     * For callers that only hold the chapter, such as the chapter list's download menu.
     *
     * Resolves the library entry the chapter is *shown* under, not the row it is stored on: a
     * chapter borrowed from a merged source lives on that source's row, and filing its
     * translation there would put it somewhere neither reader looks. Everything else keys
     * translations on the manga being read and that manga's own source; this matches.
     */
    fun translateChapter(chapter: Chapter) {
        launchIO {
            val ownerId = chapter.id?.let { getChapter.awaitOwnerMangaId(it) }
                ?: chapter.manga_id
                ?: return@launchIO
            val manga = getManga.awaitById(ownerId) ?: return@launchIO
            val source = sourceManager.get(manga.source) ?: return@launchIO
            translateChapter(manga, chapter, source)
        }
    }

    /** Page file name to its translation, or empty if the chapter has none. */
    fun getChapterTranslation(manga: Manga, chapter: Chapter, source: Source): Map<String, PageTranslation> {
        val file = provider.findTranslationFile(chapter, manga, source) ?: return emptyMap()
        return try {
            file.openInputStream().use { Json.decodeFromStream(it) }
        } catch (e: Exception) {
            // A half-written file from an interrupted run is worthless; drop it so it can be redone.
            Logger.e(e) { "Could not read the translation for ${chapter.name}" }
            file.delete()
            emptyMap()
        }
    }

    /**
     * Drops the translations of chapters whose download is going away. Call from an IO context.
     *
     * Without this a deleted chapter leaves its JSON behind forever, and re-downloading it could
     * pair fresh pages with stale text.
     */
    fun deleteTranslations(manga: Manga, chapters: List<Chapter>, source: Source) {
        chapters.forEach { chapter ->
            translator.removeFromQueue(chapter)
            provider.findTranslationFile(chapter, manga, source)?.delete()
        }
    }

    /** Drops every translation of a manga. Call from an IO context. */
    fun deleteManga(manga: Manga, source: Source) {
        translator.removeFromQueue(manga)
        provider.findMangaDir(manga, source)?.delete()
        val sourceDir = provider.findSourceDir(source)
        if (sourceDir?.listFiles()?.isEmpty() == true) sourceDir.delete()
    }
}
