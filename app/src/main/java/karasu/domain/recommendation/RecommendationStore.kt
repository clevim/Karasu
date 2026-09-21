package karasu.domain.recommendation

import android.content.Context
import java.io.File
import kotlinx.serialization.json.Json

/**
 * The last built [RecommendationSnapshot], on disk, so the window opens without touching the
 * network. One JSON file: a few hundred entries, rewritten once a night.
 */
class RecommendationStore(context: Context) {

    private val file = File(context.filesDir, "recommendations.json")

    @Volatile
    private var cached: Pair<Long, RecommendationSnapshot>? = null

    fun read(): RecommendationSnapshot? {
        if (!file.exists()) return null
        val stamp = file.lastModified()
        cached?.takeIf { (at, _) -> at == stamp }?.let { (_, snapshot) -> return snapshot }
        return runCatching { Json.decodeFromString<RecommendationSnapshot>(file.readText()) }
            .getOrNull()
            ?.also { cached = stamp to it }
    }

    @Synchronized
    fun write(snapshot: RecommendationSnapshot) {
        file.writeText(Json.encodeToString(snapshot))
        cached = file.lastModified() to snapshot
    }

    /**
     * Writes [next] only if what is on disk is still the build it was derived from. The window
     * re-ranks a snapshot it read a moment ago while the job may be adding a batch to a newer
     * one; without this check the re-rank would put the older snapshot back and lose the batch.
     */
    @Synchronized
    fun writeIfUnchanged(derivedFrom: Long, next: RecommendationSnapshot): Boolean {
        if (read()?.builtAt != derivedFrom) return false
        write(next)
        return true
    }

    @Synchronized
    fun clear() {
        file.delete()
        cached = null
    }
}
