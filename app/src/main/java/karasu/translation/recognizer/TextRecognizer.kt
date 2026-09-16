package karasu.translation.recognizer

import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.Closeable

/**
 * Source languages the page text can be read in.
 *
 * Unlike the translation target, this list cannot just be every language ML Kit knows: text has
 * to be *recognized* first, and ML Kit only ships recognizers for these scripts. Everything on
 * the latin recognizer shares one model, so those entries only differ in which language the
 * translator is told the text is in.
 *
 * ponytail: Devanagari is the one recognizer ML Kit offers that is left out. It would cost about
 * as much as the others (~0.6 MB, the heavy native pipeline is already shared), so add the
 * artifact and one entry here the day a scan needs it.
 */
enum class OcrLanguage(val code: String) {
    ENGLISH(TranslateLanguage.ENGLISH),
    JAPANESE(TranslateLanguage.JAPANESE),
    KOREAN(TranslateLanguage.KOREAN),
    CHINESE(TranslateLanguage.CHINESE),
    SPANISH(TranslateLanguage.SPANISH),
    PORTUGUESE(TranslateLanguage.PORTUGUESE),
    FRENCH(TranslateLanguage.FRENCH),
    GERMAN(TranslateLanguage.GERMAN),
    ITALIAN(TranslateLanguage.ITALIAN),
    INDONESIAN(TranslateLanguage.INDONESIAN),
    ;

    internal fun recognizerOptions() = when (this) {
        JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
        KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        else -> TextRecognizerOptions.DEFAULT_OPTIONS
    }
}

class TextRecognizer(val language: OcrLanguage) : Closeable {

    private val recognizer = TextRecognition.getClient(language.recognizerOptions())

    /** Blocking; callers are already on [kotlinx.coroutines.Dispatchers.IO]. */
    fun recognize(image: InputImage): Text = Tasks.await(recognizer.process(image))

    override fun close() = recognizer.close()
}
