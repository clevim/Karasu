package karasu.domain.migration

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.jsonMime
import eu.kanade.tachiyomi.network.parseAs
import karasu.domain.recommendation.TrackerExtrasStore
import karasu.domain.track.interactor.GetTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The names a series is worth being searched under.
 *
 * Migration fails on the title far more often than on anything else: a pt-BR source translates
 * the name, an English one uses the official release title, and a third romanises the Japanese.
 * Searching the local title alone finds nothing in the very cases migration exists for.
 *
 * Deliberately two calls rather than one list. [local] is free — it reads what is already on the
 * device — and covers most of the library. [online] costs a network round trip per manga, so it
 * is only worth paying once the free names have all missed.
 */
class MangaAliases(
    private val getTrack: GetTrack,
    private val network: NetworkHelper,
    private val extras: TrackerExtrasStore = Injekt.get(),
) {

    /**
     * Names already on the device: the library title, then whatever the bound trackers call it.
     *
     * Local title first because it is the one the user recognises, and because a manga that was
     * added from a source with a sane title usually migrates on the first try.
     */
    suspend fun local(manga: Manga): List<String> {
        val tracked = runCatching { getTrack.awaitAllByMangaId(manga.id) }
            .getOrDefault(emptyList())
            .map { it.title }
        // Then every other name the bound trackers listed, fetched overnight: the scanlation
        // group's own Portuguese title is exactly the kind of name only they know.
        val known = extras.get(manga.id)?.titles.orEmpty()
        return (listOf(manga.title) + tracked + known).clean()
    }

    /**
     * Every name AniList knows for [title], including synonyms.
     *
     * Unauthenticated on purpose: this asks nothing about a user, only what a series is called,
     * and requiring a linked tracker would leave out exactly the untracked entries that have
     * nothing but a translated title to go on. Failure is not an error — it means this manga gets
     * the same treatment it would have had before, so it returns empty rather than throwing.
     */
    suspend fun online(title: String): List<String> = runCatching {
        val payload = buildJsonObject {
            put("query", ALIAS_QUERY)
            putJsonObject("variables") { put("query", title) }
        }
        network.client
            .newCall(POST(ANILIST_API, body = payload.toString().toRequestBody(jsonMime)))
            .awaitSuccess()
            .parseAs<AliasResult>()
            .data.page.media
            .flatMap { it.names() }
            .clean()
    }.getOrElse { e ->
        Logger.d(e) { "No alias lookup for $title" }
        emptyList()
    }

    /** Blank and case-duplicate names are searches that cost a request and answer nothing. */
    private fun List<String>.clean() = map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    @Serializable
    private data class AliasResult(val data: Data) {
        @Serializable
        data class Data(@SerialName("Page") val page: Page)

        @Serializable
        data class Page(val media: List<Media> = emptyList())

        @Serializable
        data class Media(val title: Title? = null, val synonyms: List<String> = emptyList()) {
            fun names() = listOfNotNull(title?.romaji, title?.english, title?.native) + synonyms
        }

        @Serializable
        data class Title(
            val romaji: String? = null,
            val english: String? = null,
            val native: String? = null,
        )
    }

    companion object {
        private const val ANILIST_API = "https://graphql.anilist.co/"

        /**
         * Few results on purpose: past the first handful the matches stop being the same series,
         * and every extra name is another search against every candidate source.
         */
        private val ALIAS_QUERY =
            """
            |query Alias(${'$'}query: String) {
                |Page (perPage: 3) {
                    |media(search: ${'$'}query, type: MANGA, format_not_in: [NOVEL]) {
                        |title { romaji english native }
                        |synonyms
                    |}
                |}
            |}
            """.trimMargin()
    }
}
