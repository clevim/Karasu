package karasu.translation.translator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class TranslationLanguagesTest {

    @Test
    fun `the regional tag still resolves to a language ML Kit knows`() {
        // A null here would silently fall back to English for every Brazilian user.
        assertEquals("pt", TranslationLanguages.mlkitCode("pt-BR"))
        assertEquals("ja", TranslationLanguages.mlkitCode("ja"))
    }

    @Test
    fun `targets offer the Brazilian variant and never plain pt as well`() {
        assertTrue(TranslationLanguages.targets.contains("pt-BR"))
        assertFalse(TranslationLanguages.targets.contains("pt"))
    }

    @Test
    fun `prompt names are English and keep the region`() {
        assertEquals("Portuguese (Brazil)", TranslationLanguages.promptName("pt-BR"))
        assertEquals("Japanese", TranslationLanguages.promptName("ja"))
    }

    @Test
    fun `display names follow the reader's locale`() {
        assertEquals("Japonês", TranslationLanguages.displayName("ja", Locale.forLanguageTag("pt-BR")))
        assertEquals("Japanese", TranslationLanguages.displayName("ja", Locale.ENGLISH))
    }
}
