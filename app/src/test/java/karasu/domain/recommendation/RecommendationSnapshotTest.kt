package karasu.domain.recommendation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * The window re-ranks the last snapshot against whatever the sliders say now, without a
 * network round trip. If this breaks, moving a slider silently changes nothing.
 */
class RecommendationSnapshotTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `fame only breaks ties, and a fourth slot goes to a surprise`() {
        val a = item("a", "Isekai").copy(score = 1f)
        val famous = item("b", "Isekai").copy(score = 1f, popularity = 500_000, averageScore = 85)
        val better = item("c", "Isekai").copy(score = 1.3f)
        val lifted = listOf(a, famous, better).map { it.withBonuses(now) }.sortedByDescending { it.score }
        assertEquals(listOf("c", "b", "a"), lifted.map { it.title })

        val main = (1..8).map { item("m$it") }
        val explore = listOf(item("x1").copy(explore = true), item("x2").copy(explore = true))
        val mixed = (main + explore).withExploreSlots().map { it.title }
        assertEquals("x1", mixed[4])
        assertEquals("x2", mixed[9])
        assertEquals(10, mixed.size)
    }

    @Test
    fun `a source that dates every chapter to the fetch has no dates at all`() {
        val fetchedNow = List(40) { now - it * 1000L }
        assertEquals(emptyList<Long>(), honestUploadDates(fetchedNow))
        assertEquals(true, uploadDatesUntrusted(fetchedNow))
        // No dates at all is just as uninformative as the fetch time on every chapter.
        assertEquals(true, uploadDatesUntrusted(listOf(0L, 0L, 0L)))
        assertEquals(false, uploadDatesUntrusted(listOf(0L, 0L)))
        assertEquals(false, uploadDatesUntrusted(listOf(now - 30 * day, now - 23 * day, now - 16 * day)))
        // Eleven "chapters" that are volumes on a dateless source are not a new series.
        assertEquals(false, item("vols", chapters = 11).copy(datesUntrusted = true).isNew(now))
        // Short is only news while it is still moving; a two-chapter crossover from years ago is not.
        assertEquals(true, item("fresh", chapters = 11, lastUpload = now - 10 * day).isNew(now))
        assertEquals(false, item("dead", chapters = 2, lastUpload = now - 3 * 365 * day).isNew(now))
        assertEquals(true, item("started", chapters = 40).copy(firstUpload = now - 30 * day).isNew(now))
        // Chapters that call themselves a one-shot are one.
        assertEquals(true, item("x", chapters = 2).copy(oneShotChapters = true).isOneShot)
        val real = listOf(now - 30 * day, now - 23 * day, now - 16 * day)
        assertEquals(real, honestUploadDates(real))
        // Too few to judge: kept as they are.
        assertEquals(listOf(now, now - 1000), honestUploadDates(listOf(now, now - 1000, 0)))
    }

    @Test
    fun `silence is forgiven in proportion to how long the series is`() {
        fun quiet(chapters: Int, days: Long, completed: Boolean = false) =
            item("x", chapters = chapters, lastUpload = now - days * day, completed = completed).isStale(now)

        assertEquals(true, quiet(chapters = 1, days = 365))
        assertEquals(false, quiet(chapters = 1, days = 60))
        assertEquals(false, quiet(chapters = 150, days = 365))
        assertEquals(true, quiet(chapters = 150, days = 2 * 365 + 60))
        assertEquals(true, quiet(chapters = 500, days = 2 * 365 + 60))
        assertEquals(false, quiet(chapters = 1, days = 3 * 365, completed = true))
        assertEquals(false, item("no dates", chapters = 1).isStale(now))
        // A finished one-shot is not "a completed series": it is out.
        assertEquals(true, item("One Piece: Episode A", chapters = 1, completed = true).isOneShot)
        assertEquals(true, item("Any", "Oneshot", chapters = 3).isOneShot)
        assertEquals(true, item("Any oneshot 2022", chapters = 5).isOneShot)
        assertEquals(false, item("Any", "Romance", chapters = 1).isOneShot)
        // Half the allowance in: not gone, but suspect.
        assertEquals(true, item("q", chapters = 1, lastUpload = now - 60 * day).isQuiet(now))
        assertEquals(false, item("q", chapters = 1, lastUpload = now - 20 * day).isQuiet(now))
    }

    private fun item(
        title: String,
        vararg tags: String,
        fetched: Boolean = true,
        chapters: Int? = null,
        lastUpload: Long? = null,
        completed: Boolean = false,
    ) = Recommended(
        sourceId = 1, url = title, title = title, thumbnail = null, tags = tags.toList(), score = 0f, because = emptyList(),
        chapters = chapters, lastUpload = lastUpload, completed = completed, fetched = fetched,
    )

    @Test
    fun `a changed profile reorders the same items and drops what it now dislikes`() {
        val built = TasteProfile(tags = mapOf("Isekai" to 1f, "Romance" to 0.5f))
        val snapshot = RecommendationSnapshot(
            builtAt = 0,
            profileTags = built.tags,
            items = listOf(item("a", "Isekai"), item("b", "Romance"), item("c", "Romance", "Isekai")),
        )

        // The reader pushed Isekai below zero on the tags screen.
        val now = built.adjusted(mapOf("Isekai" to -1f))
        val reranked = snapshot.rerank(now)

        assertEquals(listOf("b"), reranked.items.map { it.title })
        assertEquals(now.tags, reranked.profileTags)
    }

    @Test
    fun `the same profile is the same snapshot, and unfetched entries stay behind fetched ones`() {
        val profile = TasteProfile(tags = mapOf("Isekai" to 1f))
        val snapshot = RecommendationSnapshot(0, profile.tags, listOf(item("fetched", "Isekai"), item("trailing", "Isekai", fetched = false)))

        assertSame(snapshot, snapshot.rerank(profile))
        val reranked = snapshot.rerank(TasteProfile(tags = mapOf("Isekai" to 0.5f)))
        assertEquals(listOf("fetched", "trailing"), reranked.items.map { it.title })
    }
}
