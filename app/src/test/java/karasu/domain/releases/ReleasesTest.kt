package karasu.domain.releases

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Calendar

class ReleasesTest {

    /** 2026-10-17, the day the AniList snippet below was fetched. */
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 17, 12, 0) }.timeInMillis

    private fun release(id: String, country: String, status: Int?, start: Int, popularity: Int?, score: Int? = null, genres: List<String> = emptyList()) =
        Release(ORIGIN_ANILIST, id, "T$id", listOf("T$id"), country = country, status = status, start = start, popularity = popularity, score = score, genres = genres, url = "u$id")

    private val items = listOf(
        release("jp", "JP", SManga.ONGOING, 20260601, 500, 70, listOf("Action")),
        release("kr", "KR", SManga.ON_HIATUS, 20260920, 900, 80, listOf("Action", "Drama")),
        release("cn", "CN", SManga.COMPLETED, 20260801, null, 60),
        release("tw", "TW", SManga.CANCELLED, 20260501, 100, null),
    )

    @Test
    fun `filters by period, type, status and genre without fetching`() {
        fun ids(filters: ReleaseFilters) = filterReleases(items, filters, now).map { it.id }
        assertEquals(listOf("jp", "kr", "cn", "tw"), ids(ReleaseFilters()))
        assertEquals(listOf("kr", "cn"), ids(ReleaseFilters(months = 3)))
        assertEquals(listOf("kr"), ids(ReleaseFilters(months = 1)))
        // Taiwan counts as manhua.
        assertEquals(listOf("cn", "tw"), ids(ReleaseFilters(types = setOf(TYPE_MANHUA))))
        assertEquals(listOf("kr", "tw"), ids(ReleaseFilters(statuses = setOf(SManga.ON_HIATUS, SManga.CANCELLED))))
        assertEquals(listOf("kr"), ids(ReleaseFilters(genres = setOf("action", "Drama"))))
        assertEquals(listOf("jp", "cn", "tw"), filterReleases(items, ReleaseFilters(), now) { it.id == "kr" }.map { it.id })
    }

    @Test
    fun `sorts both ways, with what has no value last both ways`() {
        fun ids(index: Int, ascending: Boolean) = items.sortedWith(releaseOrder(index, ascending)).map { it.id }
        assertEquals(listOf("kr", "jp", "tw", "cn"), ids(0, false))
        assertEquals(listOf("tw", "jp", "kr", "cn"), ids(0, true))
        assertEquals(listOf("kr", "cn", "jp", "tw"), ids(1, false))
        assertEquals(listOf("tw", "jp", "cn", "kr"), ids(1, true))
        assertEquals(listOf("kr", "jp", "cn", "tw"), ids(2, false))
        assertEquals(listOf("cn", "jp", "kr", "tw"), ids(2, true))
        assertEquals(listOf("Ttw", "Tkr", "Tjp", "Tcn"), items.sortedWith(releaseOrder(3, false)).map { it.title })
        assertEquals(listOf("Tcn", "Tjp", "Tkr", "Ttw"), items.sortedWith(releaseOrder(3, true)).map { it.title })
    }

    @Test
    fun `search names put Portuguese and Latin names first and drop duplicates`() {
        val names = listOf("Got Dropped into a Ghost Story, Still Gotta Work", "괴담에 떨어져도 출근을 해야 하는구나", "Goedame Tteoreojyeodo Chulgeuneul Haeya Haneunguna", "got dropped into a ghost story still gotta work")
        assertEquals(
            listOf("Caí numa História de Terror", "Got Dropped into a Ghost Story, Still Gotta Work", "Goedame Tteoreojyeodo Chulgeuneul Haeya Haneunguna", "괴담에 떨어져도 출근을 해야 하는구나"),
            searchNames(names, listOf("Caí numa História de Terror")),
        )
    }

    // Trimmed from graphql.anilist.co's answer to the window's query on 2026-10-17. The first
    // entry is real too: AniList's startDate_greater let a 2021 series through.
    private val aniList = Json.parseToJsonElement(
        """
        {"data": {"Page": {"media": [{"id": 131140, "siteUrl": "https://anilist.co/manga/131140", "title": {"romaji": "Yuusha Party ni Kawaii Ko ga Ita no de, Kokuhaku Shitemita.", "english": "There Was a Cute Girl in the Hero's Party, So I Tried Confessing to Her", "native": "勇者パーティーにかわいい子がいたので、告白してみた。"}, "synonyms": [], "coverImage": {"extraLarge": "https://s4.anilist.co/file/anilistcdn/media/manga/cover/large/bx131140-HPBnm2gA4gUs.jpg", "large": "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/bx131140-HPBnm2gA4gUs.jpg"}, "countryOfOrigin": "JP", "status": "RELEASING", "startDate": {"year": 2021, "month": 1, "day": 25}, "genres": ["Adventure"], "popularity": 7803, "averageScore": 66},
         {"id": 212562, "siteUrl": "https://anilist.co/manga/212562", "title": {"romaji": "Goedame Tteoreojyeodo Chulgeuneul Haeya Haneunguna", "english": null, "native": "괴담에 떨어져도 출근을 해야 하는구나"}, "synonyms": ["Got Dropped into a Ghost Story, Still Gotta Work"], "coverImage": {"extraLarge": "https://s4.anilist.co/file/anilistcdn/media/manga/cover/large/bx212562-huzVxCEUCcfM.jpg", "large": "https://s4.anilist.co/file/anilistcdn/media/manga/cover/medium/bx212562-huzVxCEUCcfM.jpg"}, "countryOfOrigin": "KR", "status": "RELEASING", "startDate": {"year": 2026, "month": 6, "day": 5}, "genres": ["Drama", "Fantasy", "Horror"], "popularity": 6876, "averageScore": 83}]}}}
        """,
    ).jsonObject

    @Test
    fun `parses AniList's list`() {
        val parsed = parseAniListReleases(aniList, since = monthsAgo(now, 6))
        assertEquals(listOf("212562"), parsed.map { it.id })
        val item = parsed.single()
        assertEquals("Got Dropped into a Ghost Story, Still Gotta Work", item.title) // no English title: its English synonym
        assertEquals(TYPE_MANHWA, item.type)
        assertEquals(SManga.ONGOING, item.status)
        assertEquals(20260605, item.start)
        assertEquals(6876, item.popularity)
        assertEquals("https://s4.anilist.co/file/anilistcdn/media/manga/cover/large/bx212562-huzVxCEUCcfM.jpg", item.cover)
        assertEquals(3, item.names.size)
    }

    // Trimmed from api.mangadex.org/manga?createdAtSince=…&order[followedCount]=desc&includes[]=cover_art.
    private val mangaDex = Json.parseToJsonElement(
        """
        {"result":"ok","data":[{"id":"5272da37-6c69-4ecc-a1e0-d25db7b3e80f","type":"manga","attributes":{
          "title":{"ja-ro":"Shujinkou no Osananajimi ga, Wakiyaku no Ore ni Guigui Kuru"},"altTitles":[{"en":"The Protagonist's Childhood Friend"}],
          "originalLanguage":"ja","status":"ongoing","year":2026,"createdAt":"2026-06-29T09:43:47+00:00",
          "tags":[{"attributes":{"name":{"en":"Romance"},"group":"genre"}},{"attributes":{"name":{"en":"School Life"},"group":"theme"}}]},
          "relationships":[{"type":"cover_art","attributes":{"fileName":"3ef08942-c50b-4dc8-bda3-c22259e57dc7.jpg"}}]}]}
        """,
    ).jsonObject

    @Test
    fun `falls back to MangaDex when AniList fails, and says so`() = runBlocking {
        val md = parseMangaDexReleases(mangaDex)
        val fromMangaDex = fetchWithFallback(now, 100, { throw java.io.IOException("AniList down") }, { md })!!
        assertEquals(ORIGIN_MANGADEX, fromMangaDex.origin)
        val item = fromMangaDex.items.single()
        assertEquals("The Protagonist's Childhood Friend", item.title)
        assertEquals(TYPE_MANGA, item.type)
        assertEquals(20260629, item.start)
        assertEquals(listOf("Romance"), item.genres)
        assertEquals("https://uploads.mangadex.org/covers/5272da37-6c69-4ecc-a1e0-d25db7b3e80f/3ef08942-c50b-4dc8-bda3-c22259e57dc7.jpg.512.jpg", item.cover)
        // An empty answer is as good as a failure; AniList, when it answers, is the one used.
        assertEquals(ORIGIN_MANGADEX, fetchWithFallback(now, 100, { emptyList() }, { md })!!.origin)
        assertEquals(ORIGIN_ANILIST, fetchWithFallback(now, 100, { parseAniListReleases(aniList, 0) }, { md })!!.origin)
        assertNull(fetchWithFallback(now, 100, { emptyList() }, { throw java.io.IOException() }))
    }

    @Test
    fun `cleans AniList's description`() {
        assertEquals("One line.\nAnother & more", cleanDescription("One <i>line</i>.<br>Another &amp; more<br><br><br>"))
    }

    @Test
    fun `takes the English synonym when AniList has no English title`() {
        // Real entries of 2026-10-17's list.
        assertEquals("A Painter Who Draws Dungeons", englishSynonym(listOf("A Painter Who Draws Dungeons", "The Dungeon Painter")))
        assertEquals("Got Dropped into a Ghost Story, Still Gotta Work", englishSynonym(listOf("GSGW", "Got Dropped into a Ghost Story, Still Gotta Work")))
        assertEquals("Goodbye, Lara", englishSynonym(listOf("Goodbye, Lara")))
        assertNull(englishSynonym(listOf("GSGW", "외전")))
        val media = Json.parseToJsonElement(
            """{"id":1,"title":{"romaji":"Dungeon-eul Geurineun Hwaga","english":null,"native":"던전을 그리는 화가"},"synonyms":["The Dungeon Painter"]}""",
        ).jsonObject
        assertEquals("The Dungeon Painter", parseAniListRelease(media)!!.title)
    }
}
