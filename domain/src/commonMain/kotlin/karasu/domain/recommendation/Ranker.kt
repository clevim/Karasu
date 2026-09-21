package karasu.domain.recommendation

import kotlin.math.sqrt

/**
 * One candidate the profile liked, and why.
 *
 * @param because the candidate's tags the profile is for, strongest first. Shown to the reader:
 *   a score nobody can explain is a score nobody can tune.
 */
data class Recommendation<T>(
    val item: T,
    val score: Float,
    val because: List<String>,
)

/**
 * Orders candidates by how much of the profile they carry, strongest first.
 *
 * The score is the sum of the matched tags' weights over the square root of how many tags the
 * candidate has: a candidate matching two liked tags beats one matching one, but a candidate
 * with thirty tags does not beat it by having thirty tags. On top, [PAIR_BONUS] of the weight of
 * every liked *pair* the candidate carries: "Romance" and "Supernatural" together is what the
 * reader likes, not either alone. Anything scoring zero or below — nothing in common, or more
 * disliked than liked — is left out rather than ranked last.
 */
fun <T> TasteProfile.rank(candidates: List<T>, tagsOf: (T) -> List<String>): List<Recommendation<T>> {
    if (tags.isEmpty()) return emptyList()
    val weights = tags.mapKeys { (tag, _) -> tag.lowercase() }
    return candidates.mapNotNull { candidate ->
        val its = tagsOf(candidate).mapNotNull(::normalizeTag).distinctBy { it.lowercase() }
        if (its.isEmpty()) return@mapNotNull null
        val matched = its.mapNotNull { tag -> weights[tag.lowercase()]?.let { tag to it } }
        var score = matched.map { (_, weight) -> weight }.sum() / sqrt(its.size.toFloat())
        if (pairs.isNotEmpty()) {
            val liked = matched.filter { (_, weight) -> weight > 0f }.map { (tag, _) -> tag }
            for (i in liked.indices) for (j in i + 1 until liked.size) {
                score += PAIR_BONUS * (pairs[pairKey(liked[i], liked[j])] ?: 0f)
            }
        }
        if (score <= 0f) return@mapNotNull null
        Recommendation(
            item = candidate,
            score = score,
            because = matched.filter { (_, weight) -> weight > 0f }
                .sortedByDescending { (_, weight) -> weight }
                .map { (tag, _) -> tag },
        )
    }.sortedByDescending { it.score }
}

/** How much a liked pair adds, per pair, on top of the tags themselves. */
private const val PAIR_BONUS = 0.25f
