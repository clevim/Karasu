package karasu.domain.recommendation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MangaBakaAndCfTest {

    private val berserk = """
        {"id":1692,"type":"manga","title":"BERSERK","native_title":"ベルセルク","romanized_title":"BERSERK",
         "titles":[{"language":"pt","title":"Berserk Edição de Luxo"},{"language":"ru","title":"Берсерк"}],
         "secondary_titles":{"en":[{"title":"Berserk: The Black Swordsman"}]},
         "authors":["MIURA Kentaro"],"artists":["MIURA Kentaro"],"status":"releasing",
         "genres":["action","slice_of_life"],
         "tags_v2":[{"name":"Armed Combat","weight":"core","is_spoiler":false},
                    {"name":"Duels","weight":"incidental","is_spoiler":false},
                    {"name":"Griffith Betrays","weight":"defining","is_spoiler":true}],
         "source":{"anilist":{"id":30002},"my_anime_list":{"id":2},"manga_updates":{"id":"njeqwry"},"kitsu":{"id":8}}}
    """.trimIndent()

    private fun record(json: String) = parseMangaBakaRecord(Json.parseToJsonElement(json).jsonObject)!!

    @Test
    fun `a record keeps the ids, latin names, genres and only strong unspoiled tags`() {
        val r = record(berserk)
        assertEquals(
            mapOf("mangabaka" to "1692", "anilist" to "30002", "mal" to "2", "mu" to "njeqwry", "kitsu" to "8"),
            r.ids,
        )
        assertTrue("Berserk Edição de Luxo" in r.titles)
        assertTrue("ベルセルク" in r.titles, "the native title stays")
        assertTrue("Берсерк" !in r.titles, "other scripts would only fill the backup")
        assertEquals(listOf("Action", "Slice Of Life"), r.genres)
        assertTrue("Armed Combat" in r.tags && "Duels" !in r.tags && "Griffith Betrays" !in r.tags)
    }

    @Test
    fun `a novel is never the series`() {
        assertNull(parseMangaBakaRecord(Json.parseToJsonElement("""{"id":1,"type":"novel","title":"x"}""").jsonObject))
    }

    @Test
    fun `a near-identical name is enough, a close one needs the author`() {
        val kingdom = MangaBakaRecord(mapOf("mangabaka" to "1"), listOf("Kingdom"), listOf("HARA Yasuhisa"), emptyList(), emptyList(), null, "manga")
        val hearts = MangaBakaRecord(mapOf("mangabaka" to "2"), listOf("Kingdom Hearts"), listOf("AMANO Shiro"), emptyList(), emptyList(), null, "manga")
        assertEquals(kingdom, bestMatch(listOf("Kingdom"), null, listOf(hearts, kingdom)))
        assertNull(bestMatch(listOf("Kingdoms"), null, listOf(hearts)), "close is not enough alone")
        assertEquals(kingdom, bestMatch(listOf("Kingdoms!"), "Yasuhisa Hara", listOf(kingdom)), "the author confirms a close name")
    }

    @Test
    fun `cf favours what several liked seeds point to, and drops the library`() {
        fun seed(title: String, weight: Float) = ExternalSuggestions.Seed(title, weight)
        fun rec(provider: String, title: String, anilist: Long? = null, mal: Long? = null, strength: Float = 2f) =
            ExternalSuggestions.Rec(provider, ExternalSuggestions.Suggestion(seed = "", titles = listOf(title), genres = emptyList(), anilistId = anilist, malId = mal), strength)
        val a = seed("A", 1f)
        val b = seed("B", 0.5f)
        val raw = listOf(
            a to rec("AniList", "Vagabond", anilist = 10, mal = 100),
            b to rec("AniList", "Vagabond", anilist = 10, mal = 100),
            // MyAnimeList's name for it differs, its id does not: the same series.
            a to rec("MyAnimeList", "Vagabond (Official)", mal = 100),
            b to rec("AniList", "Once", anilist = 20),
            a to rec("AniList", "Already Mine", anilist = 30),
        )
        val merged = mergeRecommendations(raw, ExternalSuggestions.Known(anilist = setOf(30)))
        assertEquals(listOf("Vagabond", "Once"), merged.map { it.titles.first() })
        val vagabond = merged.first()
        assertEquals(1f, vagabond.cf)
        assertEquals(2, vagabond.votes)
        assertEquals("A", vagabond.seed, "credited to the seed it owes most to")
        assertTrue(merged[1].cf < 0.5f)
    }

    @Test
    fun `a co-recommended series the tags miss is kept, unless it carries a disliked tag`() {
        fun item(title: String, cf: Float, vararg tags: String) = Recommended(
            sourceId = 1, url = title, title = title, thumbnail = null, tags = tags.toList(), score = 0f,
            because = emptyList(), fetched = true, cf = cf,
        )
        val profile = TasteProfile(tags = mapOf("Romance" to 1f, "Gore" to -1f))
        val ranked = rankItems(listOf(item("tagged", 0f, "Romance"), item("thin", 0.8f, "Sports"), item("gory", 0.9f, "Gore")), profile)
        assertEquals(listOf("tagged", "thin"), ranked.map { it.title })
        val lifted = ranked.map { it.withBonuses(0L) }.sortedByDescending { it.score }
        assertEquals("tagged", lifted.first().title, "a full tag match still beats a thin co-recommendation")
    }
}
