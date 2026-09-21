package karasu.domain.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.MangaChapter
import kotlinx.coroutines.flow.Flow
import karasu.domain.chapter.models.ChapterUpdate

interface ChapterRepository {
    suspend fun getChapters(mangaId: Long, filterScanlators: Boolean): List<Chapter>
    fun getChaptersAsFlow(mangaId: Long, filterScanlators: Boolean): Flow<List<Chapter>>

    /** How many chapters are stored for [mangaId], without reading the rows themselves. */
    suspend fun countChapters(mangaId: Long): Int

    suspend fun getChapterById(id: Long): Chapter?

    /** The library entry a chapter is shown under: its own manga, or the one it was merged into. */
    suspend fun getOwnerMangaId(chapterId: Long): Long?

    suspend fun getChaptersByUrl(url: String, filterScanlators: Boolean): List<Chapter>
    suspend fun getChapterByUrl(url: String, filterScanlators: Boolean): Chapter?

    suspend fun getChaptersByUrlAndMangaId(url: String, mangaId: Long, filterScanlators: Boolean): List<Chapter>
    suspend fun getChapterByUrlAndMangaId(url: String, mangaId: Long, filterScanlators: Boolean): Chapter?
    suspend fun getUnread(mangaId: Long, filterScanlators: Boolean): List<Chapter>

    suspend fun getRecents(filterScanlators: Boolean, search: String = "", limit: Long = 25L, offset: Long = 0L): List<MangaChapter>

    suspend fun getScanlatorsByChapter(mangaId: Long): List<String>
    fun getScanlatorsByChapterAsFlow(mangaId: Long): Flow<List<String>>

    suspend fun delete(chapter: Chapter): Boolean
    suspend fun deleteAllById(chapters: List<Long>): Boolean

    suspend fun update(update: ChapterUpdate): Boolean
    suspend fun updateAll(updates: List<ChapterUpdate>): Boolean

    suspend fun insert(chapter: Chapter): Long?
    suspend fun insertBulk(chapters: List<Chapter>): List<Chapter>
}
