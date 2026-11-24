package karasu.translation.translator

import karasu.data.koreader.KoreaderApi
import karasu.domain.koreader.models.RemoteSeriesNotes
import karasu.domain.koreader.models.RemoteTranslateRequest
import karasu.translation.model.PageTranslation
import karasu.translation.model.SeriesNotes
import karasu.translation.recognizer.OcrLanguage
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Offloads manga translation to the self-hosted Yatagarasu companion server.
 *
 * Yatagarasu checks its shared translation cache first and calls whichever LLM provider is
 * configured on the server (OpenRouter, DeepSeek, Gemini, or local Ollama/vLLM), returning the
 * translated blocks and learned glossary terms directly in Karasu's [PageTranslation] schema.
 */
class YatagarasuTranslator(
    override val fromLang: OcrLanguage,
    override val toLang: String,
    private val context: SeriesNotes = SeriesNotes(),
    private val api: KoreaderApi = Injekt.get(),
) : TextTranslator {

    private var learnedTerms: Map<String, String> = emptyMap()

    override val learned: Map<String, String>
        get() = learnedTerms

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        if (pages.isEmpty()) return
        require(api.isConfigured) { "Configure the Yatagarasu server address in Settings → Yatagarasu first" }

        val response = api.translatePages(
            RemoteTranslateRequest(
                fromLang = fromLang.code,
                toLang = toLang,
                seriesNotes = RemoteSeriesNotes(notes = context.notes, glossary = context.glossary),
                pages = pages.toMap(),
            ),
        )
        learnedTerms = response.learned

        for ((pageKey, remotePage) in response.pages) {
            val localPage = pages[pageKey] ?: continue
            val count = minOf(localPage.blocks.size, remotePage.blocks.size)
            for (i in 0 until count) {
                val translated = remotePage.blocks[i].translation
                if (translated.isNotBlank()) {
                    localPage.blocks[i].translation = translated
                }
            }
        }
    }

    override fun close() = Unit
}
