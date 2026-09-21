package karasu.domain.recommendation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TagNamesTest {
    @Test
    fun `one name per genre across languages and spellings`() {
        canonicalTag("Ação") shouldBe "Action"
        canonicalTag(" acao ") shouldBe "Action"
        canonicalTag("ACTION") shouldBe "Action"
        canonicalTag("Comédia") shouldBe "Comedy"
        canonicalTag("Slice_of_Life") shouldBe "Slice of Life"
    }

    @Test
    fun `metadata dressed as a tag is dropped, a keyed genre keeps its value`() {
        cleanTag("Classificação: Sugestivo") shouldBe null
        cleanTag("Serialização: Shonen Jump") shouldBe null
        cleanTag("Gênero: Ação") shouldBe "Ação"
        cleanTag("Demografia: Seinen") shouldBe "Seinen"
        cleanTag("Neko Scans") shouldBe null
        cleanTag("Projeto Ryuu") shouldBe null
        cleanTag("2019") shouldBe null
        cleanTag("Romance") shouldBe "Romance"
        normalizeTag("Gênero: Ação") shouldBe "Action"
    }

    @Test
    fun `a reader's merge folds tags into one name, on top of the table`() {
        TagMerges.user = mapOf("reincarnation" to "Isekai", "regression" to "Isekai", "isekai" to "Fantasy")
        try {
            canonicalTag("Reencarnação") shouldBe "Fantasy"
            canonicalTag("Regression") shouldBe "Fantasy"
            canonicalTag("Romance") shouldBe "Romance"
        } finally {
            TagMerges.user = emptyMap()
        }
    }

    @Test
    fun `an unknown tag keeps its spelling`() {
        canonicalTag("  Cozinha de Bordo ") shouldBe "Cozinha de Bordo"
    }

    @Test
    fun `the profile merges spellings into one weight`() {
        val profile = buildTasteProfile(
            listOf(
                LibraryEntry(1, "a", listOf("Ação"), 1f, true),
                LibraryEntry(2, "b", listOf("Action"), 1f, true),
                LibraryEntry(3, "c", listOf("Mecha"), 1f, true),
            ),
        )
        profile.tags["Action"] shouldBe 1f
        profile.tags["Mecha"] shouldBe 0.5f
    }
}
