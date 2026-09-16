package karasu.translation.data

import karasu.data.DatabaseHandler

/** Per-manga notes for the LLM translator. See `manga_translation_context.sq`. */
class TranslationContexts(private val handler: DatabaseHandler) {

    suspend fun get(mangaId: Long): String = runCatching {
        handler.awaitOneOrNull { manga_translation_contextQueries.find(mangaId) }
    }.getOrNull().orEmpty()

    /** Blank clears it. */
    suspend fun set(mangaId: Long, context: String) {
        runCatching {
            handler.await {
                if (context.isBlank()) {
                    manga_translation_contextQueries.delete(mangaId)
                } else {
                    manga_translation_contextQueries.upsert(mangaId, context.trim())
                }
            }
        }
    }
}
