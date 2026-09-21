package eu.kanade.tachiyomi.util.chapter

import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.mapper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.model.memoToString
import eu.kanade.tachiyomi.source.model.toMemo
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import karasu.data.Database
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `SChapter.memo` is the source's own blob and some sources can't fetch a chapter without it
 * (Asura Scans keeps the series' rotating slug there), so it has to survive the database and a
 * changed one has to reach it.
 */
class ChapterMemoTest {

    @Test
    fun `a memo survives the round trip through the database column`() {
        val db = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            .also { Database.Schema.create(it) }
            .let { Database(it) }
        val memo = JsonObject(mapOf("mangaSlug" to JsonPrimitive("solo-leveling-a1b2c3")))

        db.mangasQueries.insert(
            source = 1, url = "/a", artist = null, author = null, description = null, genre = null,
            title = "Series", status = 1, thumbnailUrl = null, favorite = true, lastUpdate = 0,
            initialized = true, viewer = 0, hideTitle = false, chapterFlags = 0, dateAdded = 0,
            filteredScanlators = null, updateStrategy = 0, coverLastModified = 0,
            memo = JsonObject(mapOf("slug" to JsonPrimitive("series-9f8e"))).memoToString(),
        )
        val mangaId = db.mangasQueries.selectLastInsertedRowId().executeAsOne()
        db.chaptersQueries.insert(
            mangaId = mangaId, url = "/c/1", name = "Ch. 1", scanlator = null, read = false,
            bookmark = false, lastPageRead = 0, pagesLeft = 0, chapterNumber = 1.0, sourceOrder = 0,
            dateFetch = 0, dateUpload = 0, memo = memo.memoToString(),
        )

        val stored = db.chaptersQueries.getChaptersByMangaId(mangaId, 0, Chapter::mapper).executeAsOne()
        assertEquals(memo, stored.memo)

        // Same contract on the manga row: some sources keep the id they need there instead.
        val storedManga = db.mangasQueries.findById(mangaId, Manga::mapper).executeAsOne()
        assertEquals(JsonObject(mapOf("slug" to JsonPrimitive("series-9f8e"))), storedManga.memo)
    }

    @Test
    fun `an empty or unreadable column reads back as no memo`() {
        assertEquals(JsonObject(emptyMap()), "".toMemo())
        assertEquals(JsonObject(emptyMap()), "{}".toMemo())
        assertEquals(JsonObject(emptyMap()), "not json".toMemo())
        assertEquals("{}", JsonObject(emptyMap()).memoToString())
    }

    /**
     * A rotated memo has to reach the database, but it is not a chapter list that changed. A
     * source that rotates its id hands back a new one on every refresh, so counting it as news
     * would rewrite every row's source order and float the manga to the top of Updates each time.
     * `syncChaptersWithSource` keeps the two apart; this pins the half that can be tested without
     * a database behind it.
     */
    @Test
    fun `a rotated memo is not a changed chapter list`() {
        val stored = chapter(JsonObject(mapOf("mangaSlug" to JsonPrimitive("old"))))
        val rotated = chapter(JsonObject(mapOf("mangaSlug" to JsonPrimitive("new"))))
        val renamed = chapter(stored.memo).apply { name = "Chapter 1 (v2)" }

        assertFalse(shouldUpdateDbChapter(stored, rotated), "a memo alone is not news")
        assertTrue(stored.memo != rotated.memo, "but it still has to be written")
        assertTrue(shouldUpdateDbChapter(stored, renamed), "a real change still counts")
    }

    private fun chapter(memo: JsonObject) = Chapter.create().apply {
        url = "/series/solo-leveling/chapter/1"
        name = "Chapter 1"
        chapter_number = 1f
        this.memo = memo
    }
}
