package karasu.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TrackerStatusTest {

    @Test
    fun `reads every tracker's word, search results included`() {
        assertEquals(SManga.ON_HIATUS, trackerStatusOf("HIATUS")) // AniList
        assertEquals(SManga.ON_HIATUS, trackerStatusOf("on hiatus")) // MyAnimeList search result
        assertEquals(SManga.CANCELLED, trackerStatusOf("discontinued"))
        assertEquals(SManga.ONGOING, trackerStatusOf("Publishing")) // Kitsu search result
        assertEquals(SManga.COMPLETED, trackerStatusOf("FINISHED"))
        assertEquals(null, trackerStatusOf("NOT_YET_RELEASED"))
        assertEquals(null, trackerStatusOf(""))
    }

    @Test
    fun `the tracker wins unless the source says more`() {
        assertEquals(SManga.ON_HIATUS, libraryStatus(SManga.ONGOING, SManga.ON_HIATUS))
        assertEquals(SManga.CANCELLED, libraryStatus(SManga.UNKNOWN, SManga.CANCELLED))
        assertEquals(SManga.ONGOING, libraryStatus(SManga.COMPLETED, SManga.ONGOING))
        // Same word, or nothing said: the source's own stays.
        assertEquals(null, libraryStatus(SManga.ONGOING, SManga.ONGOING))
        assertEquals(null, libraryStatus(SManga.ONGOING, null))
        // "Publishing finished" and "licensed" are truer than a plain "finished".
        assertEquals(null, libraryStatus(SManga.PUBLISHING_FINISHED, SManga.COMPLETED))
        assertEquals(null, libraryStatus(SManga.LICENSED, SManga.COMPLETED))
    }
}
