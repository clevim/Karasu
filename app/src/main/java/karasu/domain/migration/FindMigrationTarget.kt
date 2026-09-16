package karasu.domain.migration

import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.smartsearch.SmartSearchEngine
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.lang.toNormalized
import karasu.util.normalizedLevenshteinSimilarity

/**
 * How sure the app is that [sManga] on [source] is the same series.
 *
 * [confident] is what decides whether this is applied unattended. Migration moves read history,
 * bookmarks, categories and tracking onto the target, and that is not undoable, so the bar sits
 * where a wrong pick is genuinely unlikely rather than where most picks would clear it.
 */
data class MigrationTarget(
    val source: CatalogueSource,
    val sManga: SManga,
    val score: Double,
    val matchedAlias: String,
) {
    val confident: Boolean get() = score >= CONFIDENT_SCORE

    companion object {
        /**
         * Above this the two titles differ by little more than punctuation or a subtitle.
         *
         * Well above [SmartSearchEngine.MIN_NORMAL_ELIGIBLE_THRESHOLD], which is the bar for
         * "worth showing a human" — a 0.4 match is a plausible suggestion and a terrible thing to
         * act on by itself.
         */
        const val CONFIDENT_SCORE = 0.9
    }
}

/**
 * Looks for somewhere else to read a series, across [sources], under every name it is known by.
 *
 * The order of both loops is the whole design. Sources are tried in the order given — the caller
 * puts the preferred language first — and names in the order [MangaAliases] returns them, so a hit
 * on the primary language under the local title wins over an equally good hit anywhere else. The
 * paid-for alias lookup only runs once the free names have missed everywhere, which for most of a
 * library is never.
 */
class FindMigrationTarget(
    private val aliases: MangaAliases,
    private val searchEngine: SmartSearchEngine,
) {

    suspend fun await(manga: Manga, sources: List<CatalogueSource>): MigrationTarget? {
        if (sources.isEmpty()) return null

        val localNames = aliases.local(manga)
        best(localNames, sources)?.let { return it }

        // Nothing matched under any name the device already had. This is the case the user hits
        // when a source translated the title: the library says "O Rei dos Piratas" and every
        // other source in the world says "One Piece".
        val onlineNames = aliases.online(manga.title).filterNot { name ->
            localNames.any { it.equals(name, ignoreCase = true) }
        }
        return best(onlineNames, sources)
    }

    /**
     * The best target across every (source, name) pair, or null if nothing cleared the engine.
     *
     * Every pair is scored rather than returning the first hit: the first source to answer is not
     * the one that answered best, and picking the best is what lets [MigrationTarget.confident]
     * mean anything. A source that throws is skipped — one dead site must not end the search.
     */
    private suspend fun best(names: List<String>, sources: List<CatalogueSource>): MigrationTarget? {
        if (names.isEmpty()) return null
        var best: MigrationTarget? = null
        for (source in sources) {
            val hit = runCatching { searchEngine.normalSearchAliases(source, names) }.getOrNull()
                ?: continue
            val candidate = names
                .map { MigrationTarget(source, hit, score(it, hit.title), it) }
                .maxByOrNull { it.score }
                ?: continue
            if (best == null || candidate.score > best.score) best = candidate
            // A confident hit on an earlier source is the answer; the rest cannot beat it by
            // enough to be worth the requests.
            if (best.confident) return best
        }
        return best
    }

    private fun score(name: String, title: String) =
        normalizedLevenshteinSimilarity(name.toNormalized(), title.toNormalized())
}
