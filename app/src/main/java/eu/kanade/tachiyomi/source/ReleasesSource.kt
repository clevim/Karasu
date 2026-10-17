package eu.kanade.tachiyomi.source

import android.content.Context
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.recommendation.ExternalIds
import karasu.domain.recommendation.TrackerExtrasStore
import karasu.domain.recommendation.titleKey
import karasu.domain.releases.ANILIST_GENRES
import karasu.domain.releases.RELEASE_STATUSES
import karasu.domain.releases.Release
import karasu.domain.releases.ReleaseFilters
import karasu.domain.releases.ReleasesRepository
import karasu.domain.releases.TYPE_MANGA
import karasu.domain.releases.TYPE_MANHUA
import karasu.domain.releases.TYPE_MANHWA
import karasu.domain.releases.filterReleases
import karasu.domain.releases.inLibrary
import karasu.domain.releases.releaseOrder
import karasu.domain.track.interactor.GetTrack
import karasu.i18n.MR
import karasu.util.lang.getString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import uy.kohesive.injekt.injectLazy

/**
 * What started in the last six months, most popular first, shown as a catalogue so the source
 * browser draws it — experimental. Like [RecommendationSource] it owns no manga: an entry here is
 * a series some catalogue knows, with no chapters, and tapping one opens its own page
 * ([eu.kanade.tachiyomi.ui.source.releases.ReleaseDetailsController]) rather than a manga's.
 *
 * Every filter works on the list already on disk ([ReleasesRepository]); none fetches.
 */
class ReleasesSource(private val context: Context) : CatalogueSource {

    private val repository: ReleasesRepository by injectLazy()
    private val getLibraryManga: GetLibraryManga by injectLazy()
    private val getTrack: GetTrack by injectLazy()
    private val extras: TrackerExtrasStore by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    override val id = ID
    override val name = context.getString(MR.strings.releases)
    override val lang = "other"
    override val supportsLatest = true

    override fun toString() = name

    override fun getFilterList(): FilterList {
        // AniList's fixed list first, so the filter is there on the first open (before any list)
        // and stays the same across refreshes; a MangaDex list adds its own on top.
        val genres = (ANILIST_GENRES + repository.read()?.items.orEmpty().flatMap { it.genres }).distinct()
        return FilterList(
            listOfNotNull(
                Period(context),
                TypeGroup(context),
                StatusGroup(context),
                HideLibrary(context, preferences.releasesHideLibrary().get()),
                OrderBy(context),
                genres.takeIf { it.isNotEmpty() }?.let { GenreGroup(context, it) },
            ),
        )
    }

    /** Most popular first, with the default filters (the library hidden, if the settings say so). */
    override suspend fun getPopularManga(page: Int) = page(page, "", getFilterList())

    /** Most recent start first. */
    override suspend fun getLatestUpdates(page: Int) = page(page, "", getFilterList(), latest = true)

    override suspend fun getSearchManga(page: Int, query: String, filters: FilterList) = page(page, query, filters)

    /** Fetches the list again now, whatever its age: the menu's refresh. */
    suspend fun refresh() {
        repository.snapshot(force = true)
    }

    /** The entry behind a browse row, for its page. */
    fun release(url: String): Release? = repository.read()?.items?.firstOrNull { it.key == url }

    private suspend fun page(page: Int, query: String, filters: FilterList, latest: Boolean = false): MangasPage {
        val snapshot = repository.snapshot()
        val sort = filters.filterIsInstance<OrderBy>().firstOrNull()?.state
        val hideLibrary = filters.filterIsInstance<HideLibrary>().firstOrNull()?.state ?: false
        val chosen = ReleaseFilters(
            months = filters.filterIsInstance<Period>().firstOrNull()?.months ?: 6,
            types = filters.filterIsInstance<TypeGroup>().firstOrNull()?.state.orEmpty().filter { it.state }.map { it.type }.toSet(),
            genres = filters.filterIsInstance<GenreGroup>().firstOrNull()?.state.orEmpty().filter { it.state }.map { it.genre }.toSet(),
            statuses = filters.filterIsInstance<StatusGroup>().firstOrNull()?.state.orEmpty().filter { it.state }.map { it.status }.toSet(),
            query = query,
        )
        // Built on the first page of each listing — a new filter, a refresh — and reused for the
        // pages after it rather than reading the library, its tracks and extras every 30 entries.
        // Needed either way: hidden, or marked with the "in library" badge.
        val library = matcher?.takeIf { page > 1 } ?: libraryMatcher().also { matcher = it }
        val order = if (latest) releaseOrder(1, false) else releaseOrder(sort?.index ?: 0, sort?.ascending ?: false)
        val hidden = if (hideLibrary) library else { _: Release -> false }
        val all = filterReleases(snapshot.items, chosen, System.currentTimeMillis(), hidden).sortedWith(order)
        val from = (page - 1) * PAGE_SIZE
        return MangasPage(all.drop(from).take(PAGE_SIZE).map { it.toSManga(library(it)) }, hasNextPage = from + PAGE_SIZE < all.size)
    }

    @Volatile
    private var matcher: ((Release) -> Boolean)? = null

    /** Whether the library has a release: by the catalogue ids its tracks and lookups found, or by name. */
    private suspend fun libraryMatcher(): (Release) -> Boolean {
        val library = getLibraryManga.await().map { it.manga }.distinctBy { it.id }
        val known = extras.all()
        val anilist = getTrack.awaitAll().filter { it.sync_id == TrackManager.ANILIST }.map { it.media_id.toString() }.toSet() +
            known.values.mapNotNull { it.ids[ExternalIds.ANILIST] }
        val mangaDex = known.values.mapNotNull { it.ids[ExternalIds.MANGADEX] }.toSet()
        val keys = library.flatMap { manga -> listOf(manga.title) + known[manga.id]?.allTitles.orEmpty() }
            .map { it.titleKey() }.filter { it.isNotEmpty() }.toSet()
        return { inLibrary(it, anilist, mangaDex, keys) }
    }

    private fun Release.toSManga(inLibrary: Boolean) = SManga.create().also {
        it.url = key
        it.title = title
        it.thumbnail_url = cover
        it.genre = genres.joinToString(", ").ifBlank { null }
        status?.let { status -> it.status = status }
        if (inLibrary) it.memo = buildJsonObject { put(IN_LIBRARY_MEMO, JsonPrimitive(true)) }
        it.initialized = true
    }

    private class Period(context: Context) : Filter.Select<String>(
        context.getString(MR.strings.releases_period),
        MONTHS.map { context.getString(MR.plurals.releases_months, it, it) }.toTypedArray(),
    ) {
        val months get() = MONTHS[state]
    }

    private class TypeFilter(name: String, val type: String) : Filter.CheckBox(name)

    private class TypeGroup(context: Context) : Filter.Group<TypeFilter>(
        context.getString(MR.strings.releases_type),
        listOf(
            TypeFilter(context.getString(MR.strings.manga), TYPE_MANGA),
            TypeFilter(context.getString(MR.strings.manhwa), TYPE_MANHWA),
            TypeFilter(context.getString(MR.strings.manhua), TYPE_MANHUA),
        ),
    )

    private class StatusFilter(name: String, val status: Int) : Filter.CheckBox(name)

    private class StatusGroup(context: Context) : Filter.Group<StatusFilter>(
        context.getString(MR.strings.status),
        RELEASE_STATUSES.zip(listOf(MR.strings.ongoing, MR.strings.completed, MR.strings.on_hiatus, MR.strings.cancelled))
            .map { (status, label) -> StatusFilter(context.getString(label), status) },
    )

    /** Shown in the reader's language, matched by the catalogue's own (English) [genre]. */
    private class GenreFilter(name: String, val genre: String) : Filter.CheckBox(name)

    private class GenreGroup(context: Context, genres: List<String>) : Filter.Group<GenreFilter>(
        context.getString(MR.strings.releases_genres),
        genres.map { GenreFilter(RELEASE_GENRE_NAMES[it]?.let { name -> context.getString(name) } ?: it, it) }.sortedBy { it.name.lowercase() },
    )

    private class HideLibrary(context: Context, default: Boolean) :
        Filter.CheckBox(context.getString(MR.strings.releases_hide_library), default)

    private class OrderBy(context: Context) : Filter.Sort(
        context.getString(MR.strings.order_by),
        arrayOf(
            context.getString(MR.strings.releases_sort_popularity),
            context.getString(MR.strings.releases_sort_start),
            context.getString(MR.strings.releases_sort_score),
            context.getString(MR.strings.title),
        ),
        Selection(0, false),
    )

    // Nothing ever asks: the browser opens a release's own page instead. Returned as is rather
    // than thrown, for a cover the browser tries to fill in.
    override suspend fun getMangaDetails(manga: SManga): SManga = manga
    override suspend fun getChapterList(manga: SManga): List<SChapter> = emptyList()
    override suspend fun getPageList(chapter: SChapter): List<Page> = emptyList()

    companion object {
        const val ID = 2L

        /** Set on a row the library already has (by catalogue id or name), for its badge. */
        const val IN_LIBRARY_MEMO = "karasu.releases.in_library"
        private const val PAGE_SIZE = 30
        private val MONTHS = listOf(6, 3, 1)

        /** The windows onto other data rather than catalogues: never searched, migrated to or merged. */
        val WINDOWS = setOf(RecommendationSource.ID, ID)
    }
}

/** How a release is addressed in a browse row's url: catalogue and id. */
val Release.key: String get() = "$origin:$id"

/** AniList's genres in the reader's language; one MangaDex adds keeps its English name. */
val RELEASE_GENRE_NAMES: Map<String, StringResource> = mapOf(
    "Action" to MR.strings.releases_genre_action,
    "Adventure" to MR.strings.releases_genre_adventure,
    "Comedy" to MR.strings.releases_genre_comedy,
    "Drama" to MR.strings.releases_genre_drama,
    "Ecchi" to MR.strings.releases_genre_ecchi,
    "Fantasy" to MR.strings.releases_genre_fantasy,
    "Horror" to MR.strings.releases_genre_horror,
    "Mahou Shoujo" to MR.strings.releases_genre_mahou_shoujo,
    "Mecha" to MR.strings.releases_genre_mecha,
    "Music" to MR.strings.releases_genre_music,
    "Mystery" to MR.strings.releases_genre_mystery,
    "Psychological" to MR.strings.releases_genre_psychological,
    "Romance" to MR.strings.releases_genre_romance,
    "Sci-Fi" to MR.strings.releases_genre_sci_fi,
    "Slice of Life" to MR.strings.releases_genre_slice_of_life,
    "Sports" to MR.strings.releases_genre_sports,
    "Supernatural" to MR.strings.releases_genre_supernatural,
    "Thriller" to MR.strings.releases_genre_thriller,
)
