package karasu.domain.recommendation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class RankerTest {

    private val profile = TasteProfile(tags = mapOf("Romance" to 1f, "Comedy" to 0.5f, "Harem" to -0.8f))

    @Test
    fun `more of the profile ranks higher, and the reason is the matched tags`() {
        val ranked = profile.rank(
            listOf("one" to listOf("Romance"), "two" to listOf("romance", "Comedy")),
        ) { it.second }

        ranked.map { it.item.first } shouldBe listOf("two", "one")
        // Spelled the one way the profile spells it, however the source did.
        ranked.first().because shouldBe listOf("Romance", "Comedy")
    }

    @Test
    fun `a pile of unrelated tags dilutes a match`() {
        val ranked = profile.rank(
            listOf("tight" to listOf("Romance"), "loose" to listOf("Romance", "A", "B", "C", "D")),
        ) { it.second }

        ranked.map { it.item.first } shouldBe listOf("tight", "loose")
    }

    @Test
    fun `nothing in common, or mostly disliked, is left out`() {
        val ranked = profile.rank(
            listOf("none" to listOf("Mecha"), "harem" to listOf("Harem", "Comedy"), "empty" to emptyList()),
        ) { it.second }

        ranked shouldBe emptyList()
    }

    @Test
    fun `an empty profile recommends nothing`() {
        TasteProfile().rank(listOf(listOf("Romance"))) { it } shouldBe emptyList()
    }
}

class RarityTest {

    @Test
    fun `a tag on every candidate stops deciding, a rare one keeps its weight`() {
        val pool = List(20) { listOf("Action") } + List(2) { listOf("Action", "Time Travel") }
        val rarity = tagRarity(pool)
        (rarity.getValue("action") < 0.4f) shouldBe true
        (rarity.getValue("time travel") > 0.6f) shouldBe true

        // The broad tag weighs more in the profile, and without rarity it would win; with it,
        // the candidate carrying the specific taste comes first.
        val profile = TasteProfile(tags = mapOf("Action" to 1f, "Mecha" to 0.6f))
        val candidates = listOf("broad" to listOf("Action", "Comedy"), "specific" to listOf("Mecha", "Drama"))
        profile.rank(candidates) { it.second }.first().item.first shouldBe "broad"
        val skewed = tagRarity(List(30) { listOf("Action", "Comedy") } + listOf(listOf("Mecha", "Drama")))
        profile.rank(candidates, skewed) { it.second }.first().item.first shouldBe "specific"
    }

    @Test
    fun `too small a pool says nothing`() {
        tagRarity(listOf(listOf("Action"), listOf("Action"))) shouldBe emptyMap()
    }
}
