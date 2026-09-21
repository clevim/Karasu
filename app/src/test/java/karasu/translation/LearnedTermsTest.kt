package karasu.translation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import karasu.translation.model.SeriesNotes
import org.junit.jupiter.api.Test

/**
 * The notes go into the prompt of every later chapter, so what lands in them decides how the rest
 * of the series reads.
 */
class LearnedTermsTest {

    @Test
    fun `what the user wrote is kept as they wrote it`() {
        val merged = mergeLearnedTerms("Call her Yuki, never Yukiko.", mapOf("雪" to "Yuki"))!!

        assertTrue(merged.startsWith("Call her Yuki, never Yukiko."))
        assertTrue(merged.contains("雪 = Yuki"))
    }

    @Test
    fun `a term already decided keeps its translation`() {
        val first = mergeLearnedTerms("", mapOf("呪術" to "feitiçaria"))!!
        val second = mergeLearnedTerms(first, mapOf("呪術" to "bruxaria", "領域" to "domínio"))!!

        assertTrue(second.contains("呪術 = feitiçaria"), "the first answer stands")
        assertTrue(!second.contains("bruxaria"))
        assertTrue(second.contains("領域 = domínio"))
    }

    @Test
    fun `nothing new means nothing to write`() {
        val notes = mergeLearnedTerms("", mapOf("呪術" to "feitiçaria"))!!

        assertNull(mergeLearnedTerms(notes, mapOf("呪術" to "feitiçaria")))
        assertNull(mergeLearnedTerms("anything", emptyMap()))
    }

    @Test
    fun `the section stops growing at the cap`() {
        val cap = ChapterTranslator.MAX_LEARNED_TERMS
        val merged = mergeLearnedTerms("", (1..cap + 20).associate { "t$it" to "v$it" })!!

        assertEquals(cap, merged.lines().count { it.contains(" = ") })
        assertNull(mergeLearnedTerms(merged, mapOf("overflow" to "dropped")))
    }

    @Test
    fun `merging twice does not stack the heading`() {
        val once = mergeLearnedTerms("mine", mapOf("a" to "b"))!!
        val twice = mergeLearnedTerms(once, mapOf("c" to "d"))!!

        assertEquals(1, twice.split(ChapterTranslator.LEARNED_HEADING).size - 1)
        assertTrue(twice.startsWith("mine"))
    }

    @Test
    fun `the notes and the glossary come back apart`() {
        val raw = mergeLearnedTerms("Call her Yuki.", mapOf("雪" to "Yuki", "呪術" to "feitiçaria"))!!

        val parsed = parseSeriesNotes(raw)

        assertEquals("Call her Yuki.", parsed.notes)
        assertEquals(mapOf("雪" to "Yuki", "呪術" to "feitiçaria"), parsed.glossary)
    }

    @Test
    fun `notes with no glossary yet parse as just notes`() {
        val parsed = parseSeriesNotes("Call her Yuki.")

        assertEquals("Call her Yuki.", parsed.notes)
        assertTrue(parsed.glossary.isEmpty())
    }

    @Test
    fun `a batch only carries the terms it actually mentions`() {
        val notes = SeriesNotes(
            notes = "n",
            glossary = mapOf("雪" to "Yuki", "呪術" to "feitiçaria", "領域" to "domínio"),
        )

        val sent = notes.glossaryFor(listOf("雪が降る", "これは呪術だ"))

        assertEquals(mapOf("雪" to "Yuki", "呪術" to "feitiçaria"), sent)
        assertTrue(notes.glossaryFor(listOf("nothing here")).isEmpty())
    }
}
