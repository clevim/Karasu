package eu.kanade.tachiyomi.source

import karasu.domain.recommendation.Recommended
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RecommendationOrderTest {

    private fun item(title: String, score: Float = 0f, chapters: Int? = null, firstUpload: Long? = null) = Recommended(
        sourceId = 1, url = title, title = title, thumbnail = null, tags = emptyList(), score = score,
        because = emptyList(), chapters = chapters, firstUpload = firstUpload,
    )

    private fun order(index: Int, ascending: Boolean, items: List<Recommended>) =
        (recommendationOrder(index, ascending)?.let(items::sortedWith) ?: items).map { it.title }

    @Test
    fun `the arrow flips every order, and missing values stay last both ways`() {
        val items = listOf(item("short", 0.2f, 5, 300), item("long", 0.9f, 200, 100), item("unknown", 0.5f))

        assertEquals(listOf("long", "short", "unknown"), order(2, ascending = false, items))
        assertEquals(listOf("short", "long", "unknown"), order(2, ascending = true, items))

        assertEquals(listOf("short", "long", "unknown"), order(1, ascending = false, items))
        assertEquals(listOf("long", "short", "unknown"), order(1, ascending = true, items))

        assertEquals(listOf("short", "unknown", "long"), order(0, ascending = true, items))
        assertNull(recommendationOrder(0, ascending = false), "best match first is the order the window already has")
    }
}
