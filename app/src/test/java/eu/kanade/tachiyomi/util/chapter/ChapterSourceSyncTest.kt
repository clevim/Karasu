package eu.kanade.tachiyomi.util.chapter

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChapterSourceSyncTest {

    private val now = 1_700_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `every chapter dated to the request is a source without dates`() {
        assertTrue(datesAreTheFetch(List(50) { now - it * 100L }, now))
        // Real dates spread over months are real.
        assertFalse(datesAreTheFetch(listOf(now - 60 * day, now - 30 * day, now - day), now))
        // A run posted in one day a year ago is odd but is a date, not the fetch.
        assertFalse(datesAreTheFetch(List(5) { now - 365 * day - it * 1000L }, now))
        // Too few to tell.
        assertFalse(datesAreTheFetch(listOf(now, now), now))
    }
}
