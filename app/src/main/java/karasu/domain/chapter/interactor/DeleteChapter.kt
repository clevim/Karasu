package karasu.domain.chapter.interactor

import eu.kanade.tachiyomi.data.database.models.Chapter
import karasu.domain.chapter.ChapterRepository

class DeleteChapter(
    private val chapterRepository: ChapterRepository,
) {
    suspend fun await(chapter: Chapter) = chapterRepository.delete(chapter)
    suspend fun awaitAllById(chapterIds: List<Long>) = chapterRepository.deleteAllById(chapterIds)
}
