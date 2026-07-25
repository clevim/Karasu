package karasu.domain.chapter.interactor

import karasu.domain.chapter.ChapterRepository
import karasu.domain.chapter.models.ChapterUpdate

class UpdateChapter(
    private val chapterRepository: ChapterRepository,
) {
    suspend fun await(chapter: ChapterUpdate) = chapterRepository.update(chapter)
    suspend fun awaitAll(chapters: List<ChapterUpdate>) = chapterRepository.updateAll(chapters)
}
