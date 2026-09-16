package karasu.translation.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import karasu.data.AndroidDatabaseHandler
import karasu.data.Database
import karasu.translation.model.PageTranslation
import karasu.translation.model.TranslationBlock
import karasu.translation.recognizer.OcrLanguage
import karasu.translation.translator.TextTranslator
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CachedTranslatorTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { Database.Schema.create(it) }
    private val cache = TranslationCache(AndroidDatabaseHandler(Database(driver), driver, Executors.newSingleThreadExecutor().asCoroutineDispatcher()))

    /** Counts what reaches the engine; translates by upper-casing. */
    private class Engine : TextTranslator {
        override val fromLang = OcrLanguage.ENGLISH
        override val toLang = "pt-BR"
        val seen = mutableListOf<String>()
        override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
            pages.values.flatMap { it.blocks }.forEach {
                seen += it.text
                it.translation = it.text.uppercase()
            }
        }
        override fun close() = Unit
    }

    private fun page(vararg texts: String) = PageTranslation(
        blocks = texts.map { TranslationBlock(text = it, width = 1f, height = 1f, x = 0f, y = 0f, symWidth = 1f, symHeight = 1f, angle = 0f) }.toMutableList(),
        imgWidth = 100f,
        imgHeight = 100f,
    )

    @Test
    fun `a line seen before never reaches the engine again`() = runTest {
        val first = Engine()
        val chapter1 = mutableMapOf("001.jpg" to page("hello", "run"))
        CachedTranslator(first, cache, "test").translate(chapter1)
        first.seen shouldBe listOf("hello", "run")

        val second = Engine()
        val chapter2 = mutableMapOf("001.jpg" to page("hello", "again"))
        CachedTranslator(second, cache, "test").translate(chapter2)
        second.seen shouldBe listOf("again")
        chapter2.getValue("001.jpg").blocks.map { it.translation } shouldBe listOf("HELLO", "AGAIN")
    }

    @Test
    fun `another engine is another cache`() = runTest {
        CachedTranslator(Engine(), cache, "a").translate(mutableMapOf("p" to page("hello")))
        val other = Engine()
        CachedTranslator(other, cache, "b").translate(mutableMapOf("p" to page("hello")))
        other.seen shouldBe listOf("hello")
    }
}
