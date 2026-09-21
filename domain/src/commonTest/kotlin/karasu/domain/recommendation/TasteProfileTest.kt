package karasu.domain.recommendation

import io.kotest.matchers.shouldBe
import io.kotest.matchers.floats.shouldBeGreaterThan
import io.kotest.matchers.floats.shouldBeLessThan
import org.junit.jupiter.api.Test

class TasteProfileTest {

    private fun entry(
        title: String,
        tags: List<String>,
        readFraction: Float = 0f,
        favorite: Boolean = true,
        ratings: List<Rating> = emptyList(),
    ) = LibraryEntry(title.hashCode().toLong(), title, tags, readFraction, favorite, ratings)

    @Test
    fun `a finished series outweighs one only saved`() {
        val profile = buildTasteProfile(
            listOf(
                entry("read", listOf("Romance"), readFraction = 0.9f),
                entry("saved", listOf("Mecha")),
            ),
        )

        profile.tags["Romance"] shouldBe 1f
        profile.tags.getValue("Mecha") shouldBeLessThan 1f
    }

    @Test
    fun `half read already counts as read, which is the reader's own rule`() {
        val atTheLine = buildTasteProfile(listOf(entry("a", listOf("Isekai"), readFraction = FULLY_READ_AT)))
        val past = buildTasteProfile(listOf(entry("b", listOf("Isekai"), readFraction = 0.95f)))

        atTheLine.tags shouldBe past.tags
    }

    @Test
    fun `a tag on something dropped counts against it`() {
        val profile = buildTasteProfile(
            listOf(
                entry("liked", listOf("Romance"), readFraction = 1f),
                entry("dropped", listOf("Harem"), ratings = listOf(Rating(null, Rating.Status.DROPPED))),
            ),
        )

        profile.tags.getValue("Harem") shouldBeLessThan 0f
        profile.tags["Romance"] shouldBe 1f
    }

    @Test
    fun `a score scales the vote, it does not replace it`() {
        val plain = affinityOf(entry("a", listOf("Seinen"), readFraction = 1f))
        val praised = affinityOf(
            entry("a", listOf("Seinen"), readFraction = 1f, ratings = listOf(Rating(1f, Rating.Status.COMPLETED))),
        )
        val panned = affinityOf(
            entry("a", listOf("Seinen"), readFraction = 1f, ratings = listOf(Rating(0f, Rating.Status.COMPLETED))),
        )

        praised shouldBeGreaterThan plain
        panned shouldBeLessThan plain
        // Disliking one series is not evidence against everything it is tagged with.
        panned shouldBeGreaterThan 0f
    }

    /**
     * The point of the whole thing: what makes a tag matter is turning up again and again in what
     * was actually read. One enthusiastic score on one series must not outrank a pattern.
     */
    @Test
    fun `a tag that keeps recurring beats one backed by a single rave`() {
        val profile = buildTasteProfile(
            listOf(
                entry("a", listOf("Romance"), readFraction = 1f),
                entry("b", listOf("Romance"), readFraction = 1f),
                entry("c", listOf("Romance"), readFraction = 1f),
                entry("d", listOf("Mecha"), readFraction = 1f, ratings = listOf(Rating(1f, Rating.Status.COMPLETED))),
            ),
        )

        profile.tags["Romance"] shouldBe 1f
        profile.tags.getValue("Mecha") shouldBeLessThan 1f
    }

    @Test
    fun `two trackers agreeing counts once`() {
        val one = affinityOf(entry("a", listOf("X"), ratings = listOf(Rating(0.8f, Rating.Status.COMPLETED))))
        val both = affinityOf(
            entry("a", listOf("X"), ratings = listOf(Rating(0.8f, Rating.Status.COMPLETED), Rating(0.8f, Rating.Status.COMPLETED))),
        )

        both shouldBe one
    }

    @Test
    fun `format tags are kept out of the genres`() {
        val profile = buildTasteProfile(
            listOf(entry("a", listOf("Webtoon", "Romance"), readFraction = 1f)),
            isFormatTag = { it == "Webtoon" },
        )

        profile.tags.keys shouldBe setOf("Romance")
        profile.formats.keys shouldBe setOf("Webtoon")
    }

    @Test
    fun `nothing the reader gave up on is worth asking a tracker about`() {
        val profile = buildTasteProfile(
            listOf(
                entry("liked", listOf("A"), readFraction = 1f),
                entry("dropped", listOf("B"), ratings = listOf(Rating(null, Rating.Status.DROPPED))),
            ),
        )

        profile.seeds.map { it.title } shouldBe listOf("liked")
    }

    @Test
    fun `an empty library asks for nothing`() {
        buildTasteProfile(emptyList()).isEmpty shouldBe true
        buildTasteProfile(listOf(entry("a", listOf("A"), favorite = false))).isEmpty shouldBe true
    }
}

class AdjustedProfileTest {
    @Test
    fun `an override replaces the learned weight and a new tag joins, the rest keeps learning`() {
        val learned = TasteProfile(tags = mapOf("Romance" to 1f, "Comedy" to 0.4f))

        val adjusted = learned.adjusted(mapOf("romance" to -1f, "Mecha" to 0.8f))

        adjusted.tags shouldBe mapOf("Mecha" to 0.8f, "Comedy" to 0.4f, "Romance" to -1f)
        learned.adjusted(emptyMap()) shouldBe learned
    }
}

class PenalizedProfileTest {
    @Test
    fun `a dismissal shrinks a tag relative to how much the library backs it`() {
        val profile = TasteProfile(tags = mapOf("Isekai" to 1f, "Romance" to 1f, "Mecha" to 1f), support = mapOf("Isekai" to 40, "Romance" to 2, "Mecha" to 5))

        val penalized = profile.penalized(listOf(listOf("Isekai", "Romance"), listOf("isekai"), listOf("Isekai")))

        penalized.tags.getValue("Isekai") shouldBe 40f / 43f
        penalized.tags.getValue("Romance") shouldBe 2f / 3f
        penalized.tags.getValue("Mecha") shouldBe 1f
        profile.penalized(emptyList()) shouldBe profile
    }

    @Test
    fun `support counts the liked entries behind a tag`() {
        val profile = buildTasteProfile(
            listOf(
                LibraryEntry(1, "a", listOf("Isekai"), 1f, true),
                LibraryEntry(2, "b", listOf("isekai"), 1f, true),
                LibraryEntry(3, "c", listOf("Isekai"), 0f, true, listOf(Rating(null, Rating.Status.DROPPED))),
            ),
        )
        profile.support["Isekai"] shouldBe 2
    }
}

class RecencyAndPairsTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `what was read long ago counts less, never nothing`() {
        val recent = affinityOf(LibraryEntry(1, "a", listOf("X"), 1f, true, lastReadAt = now - 10 * day), now)
        val old = affinityOf(LibraryEntry(2, "b", listOf("X"), 1f, true, lastReadAt = now - 3 * 365 * day), now)
        val undated = affinityOf(LibraryEntry(3, "c", listOf("X"), 1f, true), now)

        old shouldBeLessThan recent
        old shouldBeGreaterThan 0.2f
        undated shouldBe 1f
    }

    @Test
    fun `a pair seen on more than one liked entry is a taste, and a candidate carrying it ranks higher`() {
        val profile = buildTasteProfile(
            listOf(
                LibraryEntry(1, "a", listOf("Romance", "Supernatural"), 1f, true),
                LibraryEntry(2, "b", listOf("Romance", "Supernatural"), 1f, true),
                LibraryEntry(3, "c", listOf("Romance", "Comedy"), 1f, true),
                LibraryEntry(4, "d", listOf("Supernatural", "Comedy"), 1f, true),
            ),
        )
        profile.pairs[pairKey("Romance", "Supernatural")] shouldBe 1f

        val ranked = profile.rank(listOf("pair" to listOf("Romance", "Supernatural"), "no pair" to listOf("Romance", "Comedy"))) { it.second }
        ranked.first().item.first shouldBe "pair"
    }

    @Test
    fun `an add from the window lifts its tags in proportion to their support`() {
        val profile = TasteProfile(tags = mapOf("Big" to 1f, "Small" to 0.5f), support = mapOf("Big" to 40, "Small" to 2))

        val boosted = profile.boosted(listOf(listOf("Small"), listOf("Small")))

        boosted.tags.getValue("Small") shouldBeGreaterThan 0.5f
        boosted.tags.getValue("Big") shouldBe 1f
        profile.boosted(emptyList()) shouldBe profile
    }
}
