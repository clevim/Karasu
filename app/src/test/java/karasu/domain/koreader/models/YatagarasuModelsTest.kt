package karasu.domain.koreader.models

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class YatagarasuModelsTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun `SyncManga preserves thumbnailUrl and defaults cleanly for older payloads`() {
        val manga = SyncManga(
            sourceId = 1234L,
            url = "/manga/berserk",
            title = "Berserk",
            thumbnailUrl = "https://cdn.example.com/covers/berserk.jpg",
        )
        val encoded = json.encodeToString(manga)
        val decoded = json.decodeFromString<SyncManga>(encoded)
        assertEquals("https://cdn.example.com/covers/berserk.jpg", decoded.thumbnailUrl)

        val legacy = json.decodeFromString<SyncManga>("""{"sourceId":1234,"url":"/manga/berserk"}""")
        assertEquals("", legacy.thumbnailUrl)
    }

    @Test
    fun `YatagarasuHealth parses imageTranslator mode and active state`() {
        val activeJson = """
            {
              "name": "yatagarasu",
              "version": "2.1.0",
              "imageTranslator": {
                "configured": true,
                "mode": "gemini"
              }
            }
        """.trimIndent()
        val activeHealth = json.decodeFromString<YatagarasuHealth>(activeJson)
        assertTrue(activeHealth.imageTranslator.isActive)
        assertEquals("gemini", activeHealth.imageTranslator.mode)

        val modeOnlyHealth = json.decodeFromString<YatagarasuHealth>(
            """{"imageTranslator":{"mode":"cotrans"}}""",
        )
        assertTrue(modeOnlyHealth.imageTranslator.isActive)

        val disabledHealth = json.decodeFromString<YatagarasuHealth>(
            """{"imageTranslator":{"configured":false,"mode":"off"}}""",
        )
        assertFalse(disabledHealth.imageTranslator.isActive)
        assertFalse(YatagarasuHealth().imageTranslator.isActive)
    }
}
