package karasu.data.manga

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import karasu.data.Database
import org.junit.jupiter.api.Test

/**
 * A merged source's manga row is not a favourite, so every "clear entries outside the library"
 * sweep matched it — taking that source's chapters and read state with it and leaving a merge that
 * still listed the source and quietly brought nothing.
 */
class MergedCleanupQueriesTest {

    private val db = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        .also { Database.Schema.create(it) }
        .let { Database(it) }

    private fun manga(source: Long, url: String, favorite: Boolean): Long {
        db.mangasQueries.insert(
            source = source, url = url, artist = null, author = null, description = null, genre = null,
            title = "Series", status = 1, thumbnailUrl = null, favorite = favorite, lastUpdate = 0,
            initialized = true, viewer = 0, hideTitle = false, chapterFlags = 0, dateAdded = 0,
            filteredScanlators = null, updateStrategy = 0, coverLastModified = 0, memo = "{}",
        )
        return db.mangasQueries.selectLastInsertedRowId().executeAsOne()
    }

    private fun ids() = db.mangasQueries.findAll().executeAsList().map { it._id }

    @Test
    fun `clearing a source keeps the rows its merges point at`() {
        val parent = manga(source = 1, url = "/a", favorite = true)
        val child = manga(source = 2, url = "/b", favorite = false)
        manga(source = 2, url = "/loose", favorite = false)
        db.merged_mangaQueries.insert(manga_id = parent, source = 2, url = "/b", priority = 1)

        db.mangasQueries.deleteNotInLibraryBySourceIds(listOf(2))

        // The loose row of the same source is exactly what the sweep is for, and it goes.
        ids() shouldBe listOf(parent, child)
    }

    @Test
    fun `the keep-read sweeps spare it too`() {
        val parent = manga(source = 1, url = "/a", favorite = true)
        val child = manga(source = 2, url = "/b", favorite = false)
        db.merged_mangaQueries.insert(manga_id = parent, source = 2, url = "/b", priority = 1)

        db.mangasQueries.deleteNotInLibraryAndNotReadBySourceIds(listOf(2))
        db.mangasQueries.deleteNotInLibraryAndNotReadByIds(listOf(child))

        ids() shouldBe listOf(parent, child)
    }
}
