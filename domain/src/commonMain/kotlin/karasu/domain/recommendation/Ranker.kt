package karasu.domain.recommendation

import kotlin.math.ln
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
 *
 * @param rarity how much each tag tells candidates apart, from [tagRarity]; missing tags count
 *   fully. Without it the reader's broadest tags win every time: "Action" is on most of their
 *   library *and* most of the candidates, so it lifts everything equally and the window fills
 *   with whatever happens to carry it.
 */
fun <T> TasteProfile.rank(
    candidates: List<T>,
    rarity: Map<String, Float> = emptyMap(),
    tagsOf: (T) -> List<String>,
): List<Recommendation<T>> {
    if (tags.isEmpty()) return emptyList()
    val weights = tags.entries.associate { (tag, weight) ->
        tag.lowercase() to if (weight > 0f) weight * (rarity[tag.lowercase()] ?: 1f) else weight
    }
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

/**
 * How much each tag tells the [candidates] apart, keyed lowercase: [RARITY_FLOOR] for a tag on
 * every one of them, 1 for a tag on just one. Inverse document frequency over the pool being
 * ranked, because that is the question the ranker asks — not how common a tag is in the world,
 * but whether it separates *these* candidates. Too few candidates to say anything, and every
 * tag counts fully.
 */
fun tagRarity(candidates: List<List<String>>): Map<String, Float> {
    val n = candidates.size
    if (n < RARITY_MIN_POOL) return emptyMap()
    val df = candidates
        .flatMap { tags -> tags.mapNotNull(::normalizeTag).map { it.lowercase() }.distinct() }
        .groupingBy { it }.eachCount()
    val top = ln(n.toFloat())
    return df.mapValues { (_, count) ->
        RARITY_FLOOR + (1f - RARITY_FLOOR) * (ln(n.toFloat() / count) / top).coerceIn(0f, 1f)
    }
}

/** What a tag on every candidate still counts for: it is the reader's taste, just not a decider. */
private const val RARITY_FLOOR = 0.35f

/** Below this many candidates frequencies are noise. */
private const val RARITY_MIN_POOL = 10
