package karasu.translation.translator

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import karasu.translation.model.PageTranslation
import karasu.translation.model.sourceText
import karasu.translation.recognizer.OcrLanguage

/**
 * On-device translation. Free and offline, but downloads a ~30 MB language model on first use.
 */
class MLKitTranslator(
    override val fromLang: OcrLanguage,
    override val toLang: String,
) : TextTranslator {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslationLanguages.mlkitCode(toLang))
            .build(),
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        Tasks.await(translator.downloadModelIfNeeded(DownloadConditions.Builder().build()))
        pages.values.forEach { page ->
            page.blocks.forEach { block ->
                // The whole bubble in one call. Translating its lettering line by line hands the
                // model four fragments of a sentence instead of the sentence.
                block.translation = Tasks.await(translator.translate(block.sourceText))
            }
        }
    }

    override fun close() = translator.close()
}
