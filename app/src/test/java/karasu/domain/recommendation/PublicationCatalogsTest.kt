package karasu.domain.recommendation

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublicationCatalogsTest {

    // Trimmed from what api.mangadex.org/manga?title=Upando%20Sozinho returned on 2026-10-17:
    // the sequel came first.
    private fun mangaDex(id: String, status: String, title: String, en: String, pt: String, al: String, mu: String) = Json.parseToJsonElement(
        """
        {"id":"$id","attributes":{"title":{"ko-ro":"$title"},"originalLanguage":"ko","status":"$status",
         "altTitles":[{"ko":"나 혼자만 레벨업"},{"pt-br":"$pt"},{"en":"$en"}],
         "links":{"al":"$al","mu":"$mu","raw":"https://page.kakao.com/"},
         "tags":[{"attributes":{"name":{"en":"Action"},"group":"genre"}},{"attributes":{"name":{"en":"Long Strip"},"group":"format"}}]}}
        """,
    ).jsonObject

    private val ragnarok = parseMangaDexManga(mangaDex("ade0306c", "hiatus", "Na Honjaman Level Up: Ragnarok", "Solo Leveling: Ragnarok", "Upando Sozinho: Ragnarok", "179445", "m13i58t"))!!
    private val original = parseMangaDexManga(mangaDex("32d76d19", "completed", "Na Honjaman Level-Up", "Solo Leveling", "Upando sozinho", "105398", "6z1uqw7"))!!

    @Test
    fun `reads a MangaDex entry`() {
        assertEquals(SManga.COMPLETED, original.status)
        assertEquals(listOf("Action"), original.tags) // "Long Strip" is a format, not a tag
        assertEquals("Solo Leveling", original.titles.first()) // English first, for the trackers
        assertEquals("15180124327", original.ids[ExternalIds.MANGA_UPDATES]) // base 36 slug, as the API wants it
        assertEquals("105398", original.ids[ExternalIds.ANILIST])
    }

    @Test
    fun `matches by a known id, else by an exact name, never the first result blindly`() {
        val candidates = listOf(ragnarok, original)
        // The Brazilian source's own title, accents and case aside, picks the original over the sequel.
        assertEquals(original, matchCatalog(candidates, emptyMap(), listOf("Upando Sozinho")))
        // A bound AniList track settles it even when no name matches.
        assertEquals(ragnarok, matchCatalog(candidates, mapOf(ExternalIds.ANILIST to "179445"), listOf("Algo Diferente")))
        // Nothing exact: nothing at all.
        assertEquals(null, matchCatalog(candidates, emptyMap(), listOf("Upando")))
        // Two series with the same name: not a guess.
        val twin = original.copy(ids = mapOf(ExternalIds.MANGADEX to "other"))
        assertEquals(null, matchCatalog(listOf(original, twin), emptyMap(), listOf("Solo Leveling")))
    }

    @Test
    fun `reads MangaUpdates' prose status`() {
        assertEquals(SManga.ON_HIATUS, mangaUpdatesStatusOf("39 Volumes + Volume 0 (Hiatus)      \n"))
        assertEquals(SManga.COMPLETED, mangaUpdatesStatusOf("200 Chapters + Prologue (Complete)  \n15 Volumes (Complete)  \n\nPrologue: 1 Chapter"))
        assertEquals(SManga.ONGOING, mangaUpdatesStatusOf("12 Volumes (Ongoing)\nSide story (Complete)"))
        assertEquals(SManga.CANCELLED, mangaUpdatesStatusOf("3 Volumes (Discontinued)"))
        assertEquals(SManga.ON_HIATUS, mangaUpdatesStatusOf("Complete (Hiatus)"))
        assertEquals(SManga.ONGOING, mangaUpdatesStatusOf("Ongoing"))
        assertEquals(SManga.ONGOING, mangaUpdatesStatusOf("43 Volumes (Ongoing)\n\n*[MORI Kouji](https://example.org) took over"))
        assertEquals(SManga.ONGOING, mangaUpdatesStatusOf("Ongoing\n*[notes](https://example.org)"))
        assertEquals(null, mangaUpdatesStatusOf("45 Chapters"))
        // Whole words only: "ended" is in none of these.
        assertEquals(SManga.ON_HIATUS, mangaUpdatesStatusOf("12 Volumes (Suspended)"))
        assertEquals(SManga.CANCELLED, mangaUpdatesStatusOf("2 Volumes (Dropped)"))
        assertEquals(SManga.CANCELLED, mangaUpdatesStatusOf("1 Volume (Axed)"))
        assertEquals(SManga.COMPLETED, mangaUpdatesStatusOf("10 Volumes (Ended)"))
        assertEquals(null, mangaUpdatesStatusOf("5 Volumes (Extended edition)"))
        assertEquals(SManga.ONGOING, mangaUpdatesStatusOf("3 Volumes (Intended as a trilogy)\n4 Volumes (Ongoing)"))
        assertEquals(null, mangaUpdatesStatusOf(null))
    }

    @Test
    fun `reads a MangaUpdates series, keeping only the categories readers agree on`() {
        val series = Json.parseToJsonElement(
            """
            {"series_id":49449837876,"title":"Hunter x Hunter","status":"39 Volumes + Volume 0 (Hiatus)","completed":false,
             "associated":[{"title":"HUNTER×HUNTER"},{"title":"ハンター×ハンター"}],
             "genres":[{"genre":"Action"}],
             "categories":[{"category":"Character Growth","votes":92,"votes_plus":94,"votes_minus":2},
                           {"category":"Bad Idea","votes":4,"votes_plus":4,"votes_minus":0},
                           {"category":"Contested","votes":12,"votes_plus":20,"votes_minus":8}]}
            """,
        ).jsonObject as JsonObject
        val record = parseMangaUpdatesSeries(series)!!
        assertEquals(SManga.ON_HIATUS, record.status)
        assertEquals(listOf("Action", "Character Growth"), record.tags)
        assertEquals(listOf("Hunter x Hunter", "HUNTER×HUNTER"), record.titles)
        assertEquals("49449837876", record.ids[ExternalIds.MANGA_UPDATES])
    }

    @Test
    fun `MangaDex decides, except its default ongoing`() {
        assertEquals(SManga.ON_HIATUS, catalogStatus(SManga.ON_HIATUS, SManga.ONGOING))
        assertEquals(SManga.ON_HIATUS, catalogStatus(SManga.ONGOING, SManga.ON_HIATUS))
        assertEquals(SManga.COMPLETED, catalogStatus(SManga.COMPLETED, SManga.ON_HIATUS))
        assertEquals(SManga.CANCELLED, catalogStatus(null, SManga.CANCELLED))
        assertEquals(SManga.ONGOING, catalogStatus(SManga.ONGOING, null))
    }

    // What api.mangaupdates.com/v1/series/search {"search":"Howling"} returned on 2026-10-17, trimmed.
    private fun muHit(id: Long, title: String, hit: String, type: String, year: Int) = parseMangaUpdatesHit(
        Json.parseToJsonElement("""{"hit_title":"$hit","record":{"series_id":$id,"title":"$title","type":"$type","year":"$year"}}""").jsonObject,
    )!!

    @Test
    fun `tells same-named series apart by the qualifier, the kind of comic and the year`() {
        val old = muHit(16915667277, "Howling", "Howling", "Manga", 2006)
        val wanke = muHit(72627801573, "Howlring", "Howling (WANKE)", "Manhwa", 2026)
        val hinata = muHit(69934554803, "Howling (HINATA Takeshi)", "Howling (HINATA Takeshi)", "Manga", 2001)
        val hyeon = muHit(56313035156, "Howling: The City of Nightmare", "Howling (Hyeon Jaegwon)", "Manhwa", 2015)
        val candidates = listOf(old, wanke, hinata, hyeon)
        // Four series are "Howling" once the qualifiers go: with nothing else known, none is picked.
        assertEquals(null, matchCatalog(candidates, emptyMap(), listOf("Howling")))
        // A manhwa (the source's tags) leaves two; the one that started when the chapters did wins.
        assertEquals(null, matchCatalog(candidates, emptyMap(), listOf("Howling"), MatchHints(type = "manhwa")))
        assertEquals(wanke, matchCatalog(candidates, emptyMap(), listOf("Howling"), MatchHints(type = "manhwa", firstYear = 2026)))
        // Chapters from 2020 rule out the 2026 one.
        assertEquals(hyeon, matchCatalog(listOf(wanke, hyeon), emptyMap(), listOf("Howling"), MatchHints(type = "manhwa", firstYear = 2020)))
        // Two mangas, neither from the year of the first chapter: still not a guess.
        assertEquals(null, matchCatalog(candidates, emptyMap(), listOf("Howling"), MatchHints(type = "manga", firstYear = 2026)))
        // A series that started after the entry's first chapter cannot be it.
        assertEquals(hinata, matchCatalog(candidates, emptyMap(), listOf("Howling"), MatchHints(type = "manga", firstYear = 2003)))
        // Its own name needs no help.
        assertEquals(wanke, matchCatalog(candidates, emptyMap(), listOf("Howlring")))
        assertEquals("WANKE", "Howling (WANKE)".qualifier())
        assertEquals("oshinoko", "[Oshi no Ko]".titleKey())
        assertEquals(null, matchCatalog(listOf(muHit(1, "Life Howling", "Life Howling (Novel)", "Manga", 2015)), emptyMap(), listOf("Life Howling")))
    }

    @Test
    fun `bound trackers' ids win, wrong ones go`() {
        val bound = mapOf(ExternalIds.ANILIST to "105398")
        // A MangaDex entry learned by name that links to another AniList series is not this one.
        assertEquals(false, agreesWith(ragnarok, bound))
        assertEquals(true, agreesWith(original, bound))
        assertEquals(true, agreesWith(original, emptyMap()))
        val merged = mergeIds(
            stored = mapOf(ExternalIds.MANGADEX to "dead", ExternalIds.ANILIST to "999", ExternalIds.MAL to "1"),
            found = mapOf(ExternalIds.MANGA_UPDATES to "15180124327", ExternalIds.ANILIST to "777"),
            bound = bound,
            dropped = setOf(ExternalIds.MANGADEX),
        )
        assertEquals(mapOf(ExternalIds.ANILIST to "105398", ExternalIds.MAL to "1", ExternalIds.MANGA_UPDATES to "15180124327"), merged)
    }
}
