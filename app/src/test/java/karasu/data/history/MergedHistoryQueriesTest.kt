package karasu.data.history

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import karasu.data.Database
import org.junit.jupiter.api.Test

/**
 * A chapter read on a merged source lives on that source's hidden manga row. The recents queries
 * have to surface it under the library entry it was merged into, or reading through a merged
 * source silently drops out of History and "continue reading".
 */
class MergedHistoryQueriesTest {

    private val db = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        .also { Database.Schema.create(it) }
        .let { Database(it) }

    private fun manga(title: String, source: Long, url: String, favorite: Boolean): Long {
        db.mangasQueries.insert(
            source = source, url = url, artist = null, author = null, description = null, genre = null,
            title = title, status = 1, thumbnailUrl = null, favorite = favorite, lastUpdate = 0,
            initialized = true, viewer = 0, hideTitle = false, chapterFlags = 0, dateAdded = 0,
            filteredScanlators = null, updateStrategy = 0, coverLastModified = 0,
        )
        return db.mangasQueries.selectLastInsertedRowId().executeAsOne()
    }

    private fun chapter(mangaId: Long, number: Double, read: Boolean): Long {
        db.chaptersQueries.insert(
            mangaId = mangaId, url = "/c/$mangaId/$number", name = "Ch. $number", scanlator = null,
            read = read, bookmark = false, lastPageRead = 0, pagesLeft = 0, chapterNumber = number,
            sourceOrder = 0, dateFetch = 1_000, dateUpload = 1_000,
        )
        return db.chaptersQueries.selectLastInsertedRowId().executeAsOne()
    }

    @Test
    fun `a chapter read on a merged source shows under the library entry`() {
        val parent = manga("Series", source = 1, url = "/a", favorite = true)
        val child = manga("Series", source = 2, url = "/b", favorite = false)
        db.merged_mangaQueries.insert(manga_id = parent, source = 2, url = "/b", priority = 1)
        chapter(parent, 1.0, read = true)
        val readOnChild = chapter(child, 2.0, read = true)
        chapter(child, 3.0, read = false)
        db.historyQueries.upsert(historyChapterId = readOnChild, historyLastRead = 5_000, historyTimeRead = 60)

        val ungrouped = db.historyQueries.getRecentsUngrouped(search = "", apply_filter = 0, limit = 10, offset = 0).executeAsList()
        ungrouped.map { it._id } shouldBe listOf(parent)
        ungrouped.single().chapter_number shouldBe 2.0

        val bySeries = db.historyQueries.getRecentsBySeries(search = "", apply_filter = 0, limit = 10, offset = 0).executeAsList()
        bySeries.map { it._id } shouldBe listOf(parent)

        // Also carries the "new chapter" branch, so only the read branch's row is checked.
        val all = db.historyQueries.getRecentsAll(include_read = 0, search = "", apply_filter = 0, limit = 10, offset = 0).executeAsList()
        all.map { it._id }.distinct() shouldBe listOf(parent)
        all.any { it.history_chapter_id == readOnChild } shouldBe true
    }

    @Test
    fun `reading straight from a source that is not in the library still stays out`() {
        val loose = manga("Loose", source = 1, url = "/x", favorite = false)
        val read = chapter(loose, 1.0, read = true)
        db.historyQueries.upsert(historyChapterId = read, historyLastRead = 5_000, historyTimeRead = 60)
        db.historyQueries.getRecentsUngrouped(search = "", apply_filter = 0, limit = 10, offset = 0).executeAsList() shouldBe emptyList()
    }
}
