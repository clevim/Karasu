package karasu.domain.category.models

import co.touchlab.kermit.Logger
import kotlinx.serialization.json.Json

/**
 * Translates the category a transition points at between ids and names.
 *
 * Rules store target ids, which are local to one database. Backups match categories by name —
 * that is how manga↔category links already survive a restore — so a rule crossing devices has
 * to travel by name too, or it would point at whatever category happens to hold that id on the
 * new device and quietly move manga into it.
 */
object CategoryRuleTargets {

    private val json = Json { ignoreUnknownKeys = true }

    /** Targets rewritten from ids to names, for storing in a backup. */
    fun idsToNames(rule: String?, idToName: Map<Long, String>): String? = edit(rule) { transitions ->
        transitions.map { it.copy(targetName = idToName[it.target]) }
    }

    /**
     * Targets rewritten from names back to this database's ids.
     *
     * A transition whose category didn't come along is dropped rather than kept pointing
     * somewhere arbitrary.
     */
    fun namesToIds(rule: String?, nameToId: Map<String, Long>): String? = edit(rule) { transitions ->
        transitions.mapNotNull { transition ->
            val name = transition.targetName ?: return@mapNotNull transition
            val id = nameToId[name] ?: return@mapNotNull null
            transition.copy(target = id, targetName = null)
        }
    }

    /**
     * Transitions pointing at a deleted category dropped, everything else left byte for byte alone.
     *
     * SQLite hands a freed rowid to the next category created, so a rule left pointing at a
     * removed id doesn't just stop working — it eventually starts moving manga into whichever
     * category inherits that id. Rules that don't mention it, and rules that don't parse, are
     * returned untouched: an unrelated delete is no reason to rewrite or discard them.
     */
    fun withoutTarget(rule: String?, removed: Long): String? {
        val raw = rule?.takeIf { it.isNotBlank() } ?: return rule
        return try {
            val transitions = json.decodeFromString<CategoryRule>(raw).transitions
            if (transitions.none { it.target == removed }) return rule
            val kept = transitions.filterNot { it.target == removed }
            if (kept.isEmpty()) null else json.encodeToString(CategoryRule(kept))
        } catch (e: Exception) {
            Logger.e(e) { "Leaving unreadable category rule alone" }
            rule
        }
    }

    private fun edit(
        rule: String?,
        transform: (List<CategoryTransition>) -> List<CategoryTransition>,
    ): String? {
        val raw = rule?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val edited = transform(json.decodeFromString<CategoryRule>(raw).transitions)
            if (edited.isEmpty()) null else json.encodeToString(CategoryRule(edited))
        } catch (e: Exception) {
            Logger.e(e) { "Dropping unreadable category rule" }
            null
        }
    }
}
