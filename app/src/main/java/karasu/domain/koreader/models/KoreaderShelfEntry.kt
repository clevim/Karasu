package karasu.domain.koreader.models

import eu.kanade.tachiyomi.data.database.models.Chapter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One chapter as the shelf container knows it.
 *
 * [chapterId] is Karasu's own row id and is what the shelf is keyed by, but it is not trusted on
 * its own: a restored backup renumbers chapters, so id 1234 can come back meaning something else
 * entirely. [chapterUrl] travels with it purely so the reply can be checked against the row that
 * id now points at before anything is marked read.
 */
@Serializable
data class KoreaderShelfEntry(
    @SerialName("chapterId") val chapterId: Long,
    @SerialName("chapterUrl") val chapterUrl: String,
    @SerialName("read") val read: Boolean = false,
)

@Serializable
data class KoreaderShelf(
    @SerialName("entries") val entries: List<KoreaderShelfEntry> = emptyList(),
)

/**
 * Whether this entry is allowed to mark [chapter] read.
 *
 * The url check is the whole point: ids are local and a restored backup reuses them, so matching
 * on id alone would let the shelf mark a chapter the user never sent. Kept as its own function
 * because it is the one place here where being wrong corrupts the library rather than just
 * failing a sync.
 */
fun KoreaderShelfEntry.canMarkRead(chapter: Chapter?): Boolean =
    read && chapter != null && !chapter.read && chapter.url == chapterUrl

/** Sent alongside the CBZ so the shelf, and KOReader, have something readable to show. */
@Serializable
data class KoreaderUpload(
    @SerialName("chapterId") val chapterId: Long,
    @SerialName("chapterUrl") val chapterUrl: String,
    @SerialName("mangaTitle") val mangaTitle: String,
    @SerialName("chapterName") val chapterName: String,
    @SerialName("chapterNumber") val chapterNumber: Float,
    @SerialName("sourceId") val sourceId: Long,
)
