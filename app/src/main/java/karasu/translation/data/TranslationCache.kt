package karasu.translation.data

import karasu.data.DatabaseHandler
import karasu.translation.model.PageTranslation
import karasu.translation.model.sourceText
import karasu.translation.translator.TextTranslator

/** Translations already made, per engine and language pair. See `translation_cache.sq`. */
class TranslationCache(private val handler: DatabaseHandler) {

    suspend fun find(engine: String, from: String, to: String, sources: Collection<String>): Map<String, String> {
        if (sources.isEmpty()) return emptyMap()
        return runCatching {
            handler.awaitList { translation_cacheQueries.find(engine, from, to, sources) { source, translation -> source to translation } }
        }.getOrDefault(emptyList()).toMap()
    }

    suspend fun store(engine: String, from: String, to: String, translations: Map<String, String>, now: Long = System.currentTimeMillis()) {
        if (translations.isEmpty()) return
        runCatching {
            handler.await(inTransaction = true) {
                translations.forEach { (source, translation) ->
                    translation_cacheQueries.upsert(engine, from, to, source, translation, now)
                }
            }
        }
    }

    suspend fun clear() {
        runCatching { handler.await { translation_cacheQueries.deleteAll() } }
    }
}

/**
 * [inner] with the cache in front of it: only lines it has never seen reach the engine, and
 * whatever the engine answers is remembered.
 *
 * [engineKey] tells engines apart — and, for an LLM, models — since the same line translated
 * by two of them is two different translations.
 */
class CachedTranslator(
    private val inner: TextTranslator,
    private val cache: TranslationCache,
    private val engineKey: String,
) : TextTranslator by inner {

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        val from = inner.fromLang.code
        val to = inner.toLang
        val blocks = pages.values.flatMap { it.blocks }
        val known = cache.find(engineKey, from, to, blocks.map { it.sourceText }.toSet())
        blocks.forEach { block -> known[block.sourceText]?.let { block.translation = it } }

        // The same block objects, so the engine's answers land in the pages the caller holds.
        val pending = pages
            .mapValues { (_, page) -> page.copy(blocks = page.blocks.filter { it.translation.isBlank() }.toMutableList()) }
            .filterValues { it.blocks.isNotEmpty() }
            .toMutableMap()
        if (pending.isNotEmpty()) inner.translate(pending)

        cache.store(
            engineKey, from, to,
            pending.values.flatMap { it.blocks }
                .filter { it.translation.isNotBlank() }
                .associate { it.sourceText to it.translation },
        )
    }
}
