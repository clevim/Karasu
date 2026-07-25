package karasu.data.manga.failures

import karasu.data.DatabaseHandler
import karasu.domain.manga.failures.MangaUpdateFailureRepository
import karasu.domain.manga.failures.models.MangaUpdateFailure

class MangaUpdateFailureRepositoryImpl(
    private val handler: DatabaseHandler,
) : MangaUpdateFailureRepository {

    override suspend fun getAll(): Map<Long, Int> =
        handler.awaitList {
            manga_update_failuresQueries.findAll { mangaId, failures ->
                mangaId to failures.toInt()
            }
        }.toMap()

    override suspend fun getAllDetailed(): List<MangaUpdateFailure> =
        handler.awaitList {
            manga_update_failuresQueries.findAllDetailed { mangaId, failures, message, attempt ->
                MangaUpdateFailure(
                    mangaId = mangaId,
                    failures = failures.toInt(),
                    lastMessage = message,
                    lastAttempt = attempt,
                )
            }
        }

    override suspend fun record(mangaId: Long, message: String?, at: Long) {
        handler.await {
            manga_update_failuresQueries.recordFailure(
                manga_id = mangaId,
                last_message = message,
                last_attempt = at,
            )
        }
    }

    override suspend fun clear(mangaId: Long) {
        handler.await { manga_update_failuresQueries.clear(mangaId) }
    }
}
