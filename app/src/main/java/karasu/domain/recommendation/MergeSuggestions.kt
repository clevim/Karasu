package karasu.domain.recommendation

import android.content.Context
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.smartsearch.SmartSearchEngine
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.RecommendationSource
import eu.kanade.tachiyomi.source.SourceManager
import eu.kanade.tachiyomi.util.lang.toNormalized
import java.io.File
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.migration.MangaAliases
import karasu.util.normalizedLevenshteinSimilarity
import karasu.domain.manga.failures.ReadFailures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The same series, found on another of the reader's sources. */
@Serializable
data class MergeSuggestion(val sourceId: Long, val url: String, val title: String)

/** What the last look found for one entry, and when it looked. */
@Serializable
data class MergeCheck(val checkedAt: Long, val found: List<MergeSuggestion>)

/**
 * ponytail: one JSON file. Rebuilt by the nightly pass, so nothing here needs a backup; the
 * "not this one" list is the exception and small enough to live in the same file.
 */
class MergeSuggestionsStore(context: Context) {

    @Serializable
    private data class State(val checks: Map<Long, MergeCheck> = emptyMap(), val ignored: Set<String> = emptySet())

    private val file = File(context.filesDir, "merge_suggestions.json")

    @Volatile
    private var cached: State? = null

    private fun state(): State = cached ?: runCatching {
        if (file.exists()) Json.decodeFromString<State>(file.readText()) else State()
    }.getOrDefault(State()).also { cached = it }

    private fun write(next: State) {
        file.writeText(Json.encodeToString(next))
        cached = next
    }

    fun check(mangaId: Long?): MergeCheck? = mangaId?.let { state().checks[it] }

    fun put(mangaId: Long, check: MergeCheck) = write(state().copy(checks = state().checks + (mangaId to check)))

    /** What to offer for [mangaId]: found, and not waved away before. */
    fun pending(mangaId: Long?): List<MergeSuggestion> = check(mangaId)?.found.orEmpty()
        .filter { "$mangaId:${it.sourceId}" !in state().ignored }

    /** The reader said no to this source for this entry; do not offer it again. */
    fun ignore(mangaId: Long, sourceId: Long) = write(state().copy(ignored = state().ignored + "$mangaId:$sourceId"))
}

/**
 * Looks, overnight, for library entries on other sources the reader has, so a merge is one tap
 * instead of a search. Everything migration already knows how to do — the names a series goes
 * by, the search, the confidence — pointed at the sources the reader has *not* got it on.
 *
 * Bounded: [PER_NIGHT] entries a night, the ones looked at longest ago first, searched only on
 * sources in the same language as their own, all sources of one entry at once, each search cut
 * off at [SEARCH_TIMEOUT_MS] and the whole pass at [STAGE_BUDGET_MS] — a source that hangs must
 * cost one timeout, not the night. A miss is remembered too, so the same series is not searched
 * again for [RECHECK_MS].
 */
class FindMergeCandidates(
    private val getLibraryManga: GetLibraryManga,
    private val mergedSources: MergedSources,
    private val sourceManager: SourceManager,
    private val preferences: PreferencesHelper,
    private val aliases: MangaAliases,
    private val store: MergeSuggestionsStore,
    private val readFailures: ReadFailures,
) {
    suspend fun await(now: Long = System.currentTimeMillis(), onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }) {
        // A source that failed today is skipped, not waited on again.
        val enabled = enabledSources().filterNot { readFailures.isSourceFailing(it.id) }
        if (enabled.size < 2) return
        val due = getLibraryManga.await().distinctBy { it.manga.id }.map { it.manga }
            .filter { (store.check(it.id)?.checkedAt ?: 0L) < now - RECHECK_MS }
            .sortedBy { store.check(it.id)?.checkedAt ?: 0L }
            .take(PER_NIGHT)
        if (due.isEmpty()) return
        onProgress(0, due.size)

        val engine = SmartSearchEngine(Job() + Dispatchers.IO)
        val deadline = System.currentTimeMillis() + STAGE_BUDGET_MS
        due.forEachIndexed { index, manga ->
            // Out of time: the rest keeps its old check date and comes first tomorrow.
            if (System.currentTimeMillis() > deadline) return
            val id = manga.id ?: return@forEachIndexed
            val own = sourceManager.get(manga.source)
            val taken = mergedSources.await(id).map { it.source }.toSet() + manga.source
            // Re-checked per entry: a source that timed out on the last one is out for tonight.
            val targets = enabled.filter { it.id !in taken && (own == null || it.lang == own.lang) && !readFailures.isSourceFailing(it.id) }
            val names = aliases.local(manga).take(NAMES)
            val found = coroutineScope {
                targets.map { source ->
                    async {
                        val hit = withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                            runCatching { engine.normalSearchAliases(source, names) }
                                .onFailure {
                                    Logger.w(it) { "Merge lookup failed on ${source.name} for ${manga.title}" }
                                    readFailures.recordSource(source.id)
                                }
                                .getOrNull()
                        }
                        if (hit == null) readFailures.recordSource(source.id)
                        hit?.takeIf { h -> names.any { normalizedLevenshteinSimilarity(it.toNormalized(), h.title.toNormalized()) >= CONFIDENT } }
                            ?.let { MergeSuggestion(source.id, it.url, it.title) }
                    }
                }.awaitAll().filterNotNull()
            }
            store.put(id, MergeCheck(now, found))
            onProgress(index + 1, due.size)
        }
    }

    private fun enabledSources(): List<CatalogueSource> {
        val languages = preferences.enabledLanguages().get()
        val hidden = preferences.hiddenSources().get()
        return sourceManager.getCatalogueSources().filter {
            it.lang in languages && it.id.toString() !in hidden && it.id != LocalSource.ID && it.id != RecommendationSource.ID
        }
    }

    companion object {
        /** Entries looked at per night. Requests ≈ this × same-language sources × names tried. */
        private const val PER_NIGHT = 20

        /** Names tried per source before giving up on it. */
        private const val NAMES = 2

        /** A found title this close to one of the names is the same series, not a lookalike. */
        private const val CONFIDENT = 0.9

        /** How long a look, hit or miss, stays fresh. */
        private const val RECHECK_MS = 30L * 24 * 60 * 60 * 1000

        /** One search's allowance. Past this the source is treated as failing for the night. */
        private const val SEARCH_TIMEOUT_MS = 20_000L

        /** The whole pass's allowance; what is left waits for the next night. */
        private const val STAGE_BUDGET_MS = 8L * 60 * 1000
    }
}
