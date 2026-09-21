package eu.kanade.tachiyomi.source

import android.content.Context
import android.text.format.DateUtils
import eu.kanade.tachiyomi.data.recommendation.RecommendationJob
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import karasu.domain.recommendation.BuildRecommendations.Companion.key
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.recommendation.GetTasteProfile
import karasu.domain.recommendation.RecommendationFeedback
import karasu.domain.recommendation.RecommendationFeedbackStore
import karasu.domain.recommendation.normalizeTag
import karasu.domain.recommendation.RecommendationStore
import karasu.domain.recommendation.Recommended
import karasu.domain.recommendation.rerank
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import uy.kohesive.injekt.injectLazy

/**
 * The recommendations, shown as if they were a catalogue so the source browser draws them.
 *
 * A shop window, not a catalogue: it owns no manga row. Every entry it returns carries the real
 * source in [SOURCE_MEMO], and the browser resolves it there before showing it, so the row opened,
 * added to the library or backed up is always the real one. That is also why the fetch methods
 * below throw — nothing ever asks this source for details, chapters or pages.
 *
 * What it shows is the last [RecommendationStore] snapshot, built by the nightly job. When there
 * is none yet the job is started and the page comes back empty: the browser is watching the job
 * and reloads when it lands, which beats a spinner nobody can leave. Moving a slider on the tags
 * screen re-ranks the snapshot without going back to the sources; only the job fetches again.
 */
class RecommendationSource(private val context: Context) : CatalogueSource {

    private val getTasteProfile: GetTasteProfile by injectLazy()
    private val store: RecommendationStore by injectLazy()
    private val feedback: RecommendationFeedbackStore by injectLazy()
    private val getLibraryManga: GetLibraryManga by injectLazy()

    override val id = ID
    override val name = context.getString(MR.strings.recommendations)
    override val lang = "other"
    override val supportsLatest = true

    override fun toString() = name

    /**
     * The tags group is the profile's own tags, strongest first, so the reader can narrow the
     * window to "Romance and Comedy" without leaving it. Read from the snapshot rather than the
     * database: this is called synchronously, and the snapshot already carries the profile it
     * was ranked against.
     */
    override fun getFilterList(): FilterList {
        val snapshot = store.read()
        // Only tags at least one entry carries: a tag with nothing behind it is a checkbox that
        // empties the list.
        val present = snapshot?.items.orEmpty()
            .flatMap { it.tags }.mapNotNull { normalizeTag(it)?.lowercase() }.toSet()
        val tags = snapshot?.profileTags?.filterValues { it > 0f }?.keys
            ?.filter { it.lowercase() in present }
            ?.take(TAG_FILTERS).orEmpty()
        return FilterList(
            listOfNotNull(
                HideUntrustedDates(context),
                IncludeLibrary(context),
                OrderBy(context),
                tags.takeIf { it.isNotEmpty() }?.let { TagGroup(context, it) },
            ),
        )
    }

    /** Everything, best match first. */
    override suspend fun getPopularManga(page: Int) = page(page, "", FilterList())

    /** Few chapters or a recent start. */
    override suspend fun getLatestUpdates(page: Int) = page(page, "", FilterList(), onlyNew = true)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = page(page, query, filters)

    private suspend fun page(page: Int, query: String, filters: FilterList, onlyNew: Boolean = false): MangasPage {
        val includeLibrary = filters.filterIsInstance<IncludeLibrary>().firstOrNull()?.state ?: false
        // On by default, so the popular and latest tabs (no filters) hide them too.
        val hideUntrusted = filters.filterIsInstance<HideUntrustedDates>().firstOrNull()?.state ?: true
        val order = filters.filterIsInstance<OrderBy>().firstOrNull()?.state?.index ?: 0
        // Every checked tag must be on the entry: checking two narrows, it does not widen.
        val wanted = filters.filterIsInstance<TagGroup>().firstOrNull()?.state
            ?.filter { it.state }?.map { it.name.lowercase() }.orEmpty()
        val now = System.currentTimeMillis()

        var items = items().asSequence()
            .filter { it.chapters != 0 && !it.isOneShot && !it.isStale(now) }
            .filter { !hideUntrusted || !it.datesUntrusted }
            .filter { includeLibrary || !it.fromLibrary }
            .filter { !onlyNew || it.isNew(now) }
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .filter { item ->
                wanted.isEmpty() || item.tags.mapNotNull { normalizeTag(it)?.lowercase() }.let { its -> wanted.all { it in its } }
            }
        items = when (order) {
            // A source that reports no upload dates leaves firstUpload null; the chapter count is
            // the next best clue to how new a series is, and keeps the order from being a no-op.
            1 -> items.sortedWith(compareByDescending<Recommended> { it.firstUpload ?: 0L }.thenBy { it.chapters ?: Int.MAX_VALUE })
            2 -> items.sortedWith(compareBy<Recommended> { it.chapters ?: Int.MAX_VALUE }.thenByDescending { it.score })
            else -> items // Already by score, unfetched last.
        }

        val all = items.toList()
        val from = (page - 1) * PAGE_SIZE
        return MangasPage(all.drop(from).take(PAGE_SIZE).map { it.toSManga() }, hasNextPage = from + PAGE_SIZE < all.size)
    }

    private suspend fun items(): List<Recommended> {
        val profile = getTasteProfile.await()
        val snapshot = store.read() ?: run {
            // Unique work: if a rebuild is already running this is a no-op.
            RecommendationJob.runNow(context, RecommendationJob.MODE_FULL)
            return emptyList()
        }
        // Ten series added since the last build is a window that no longer knows the library.
        // Asked once; the job is unique, so page after page does not queue it again.
        val librarySize = getLibraryManga.await().distinctBy { it.manga.id }.size
        if (snapshot.librarySize > 0 && librarySize - snapshot.librarySize >= REBUILD_AFTER_ADDS) RecommendationJob.runNow(context, RecommendationJob.MODE_FULL)
        val hidden = feedback.hidden()
        val hiddenKeys = hidden.map { it.titleKey }.toSet()
        // A changed profile (a slider, a merge, other categories) re-ranks on the spot; the
        // result is written back so the filter sheet, which reads the file, sees the same tags.
        val reranked = snapshot.rerank(profile)
        if (reranked !== snapshot) store.writeIfUnchanged(snapshot.builtAt, reranked)
        return reranked.items.filterNot { item ->
            item.title.key() in hiddenKeys || hidden.any { it.sourceId == item.sourceId && it.url == item.url }
        }
    }

    /**
     * One line on why an entry is here and what it is: the reason, the size, when it last moved.
     * For the long-press dialog, where the grade shows nothing but a cover.
     */
    fun describe(sourceId: Long, url: String): String? {
        val item = store.read()?.items?.firstOrNull { it.sourceId == sourceId && it.url == url } ?: return null
        val now = System.currentTimeMillis()
        val parts = buildList {
            item.via?.let { add(context.getString(MR.strings.recommendation_like, it)) }
                ?: item.because.takeIf { it.isNotEmpty() }?.let { add(it.joinToString(" · ")) }
            if (item.fromCatalog && item.averageScore != null) add(context.getString(MR.strings.recommendation_anilist_score, item.averageScore))
            item.chapters?.let { add(context.getString(MR.plurals.recommendation_chapters, it, it)) }
            item.lastUpload?.takeIf { it > 0 }?.let {
                add(context.getString(MR.strings.recommendation_last_chapter, DateUtils.getRelativeTimeSpanString(it, now, DateUtils.DAY_IN_MILLIS)))
            }
            if (item.completed) add(context.getString(MR.strings.completed))
            if (item.isNew(now)) add(context.getString(MR.strings.recommendation_badge_new))
            if (item.isQuiet(now)) add(context.getString(MR.strings.recommendation_badge_quiet))
            if (item.explore) add(context.getString(MR.strings.recommendation_badge_explore))
            if (item.datesUntrusted) add(context.getString(MR.strings.recommendation_badge_untrusted_dates))
        }
        return parts.joinToString(" · ").ifBlank { null }
    }

    /** When the list on disk was built, or null when there is none. The browser compares it to what it shows. */
    fun currentBuild(): Long? = store.read()?.builtAt

    /** The reader's verdict on one entry. Hides it; "not interested" also teaches the profile. */
    fun feedback(sourceId: Long, url: String, kind: RecommendationFeedback.Kind) {
        val item = store.read()?.items?.firstOrNull { it.sourceId == sourceId && it.url == url }
        feedback.add(
            RecommendationFeedback(
                sourceId = sourceId,
                url = url,
                titleKey = (item?.title ?: url).key(),
                kind = kind,
                because = item?.because.orEmpty(),
                at = System.currentTimeMillis(),
            ),
        )
    }

    private fun Recommended.toSManga() = SManga.create().also {
        it.url = url
        it.title = title
        it.thumbnail_url = thumbnail
        // The row the browser creates copies this in: the real tags, not the reason.
        it.genre = tags.joinToString(", ").ifBlank { null }
        it.memo = buildJsonObject {
            put(SOURCE_MEMO, JsonPrimitive(sourceId))
            put(
                BECAUSE_MEMO,
                JsonPrimitive(
                    via?.let { context.getString(MR.strings.recommendation_like, it) }
                        ?: because.joinToString(" · "),
                ),
            )
        }
    }

    private class IncludeLibrary(context: Context) : Filter.CheckBox(context.getString(MR.strings.recommendation_include_library))

    private class HideUntrustedDates(context: Context) :
        Filter.CheckBox(context.getString(MR.strings.recommendation_hide_untrusted_dates), state = true)

    private class TagFilter(name: String) : Filter.CheckBox(name)

    private class TagGroup(context: Context, tags: List<String>) :
        Filter.Group<TagFilter>(context.getString(MR.strings.recommendation_filter_tags), tags.map(::TagFilter))

    private class OrderBy(context: Context) : Filter.Sort(
        context.getString(MR.strings.order_by),
        arrayOf(
            context.getString(MR.strings.recommendation_sort_affinity),
            context.getString(MR.strings.recommendation_sort_newest),
            context.getString(MR.strings.recommendation_sort_shortest),
        ),
        Selection(0, false),
    )

    override suspend fun getMangaDetails(manga: SManga): SManga = throw UnsupportedOperationException("Not a catalogue")
    override suspend fun getChapterList(manga: SManga): List<SChapter> = throw UnsupportedOperationException("Not a catalogue")
    override suspend fun getPageList(chapter: SChapter): List<Page> = throw UnsupportedOperationException("Not a catalogue")

    companion object {
        const val ID = 1L

        /** Memo key holding the id of the source the recommended manga really belongs to. */
        const val SOURCE_MEMO = "karasu.recommendation.source"

        /** Memo key holding the reason, ready to show: the tags it was recommended for. */
        const val BECAUSE_MEMO = "karasu.recommendation.because"

        private const val PAGE_SIZE = 30

        /** How many of the profile's tags the filter sheet offers. */
        private const val TAG_FILTERS = 40

        /** New library entries since the snapshot that make it stale enough to rebuild now. */
        private const val REBUILD_AFTER_ADDS = 10
    }
}
