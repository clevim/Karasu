package karasu.domain.chapter.interactor

import kotlin.math.floor
import kotlin.math.roundToInt

/** A run of chapter numbers the list skips over, both ends included. */
data class ChapterGap(val from: Int, val to: Int) {
    val size: Int get() = to - from + 1
}

/**
 * Chapter numbers a list is missing between the first it has and the last.
 *
 * A gap is the thing you find out about three hours later, halfway through a re-read, when 22 is
 * followed by 24. Sources drop chapters quietly — a takedown, a bad parse, a scanlator that never
 * posted it — and nothing about a chapter list says anything is absent, because absence has no row.
 *
 * Only whole numbers count. Extras are numbered 11.5 and 11.1 and are genuinely optional, so
 * treating a missing 11.5 as a hole would flag most of the library. Anything before the first
 * chapter held or after the last is not a gap either: those are just the ends of what you have.
 */
internal fun findChapterGaps(numbers: List<Float>): List<ChapterGap> {
    // 11.5 belongs to chapter 11, which is present as far as this is concerned.
    val present = numbers.filter { it >= 0f }.mapTo(HashSet()) { floor(it).roundToInt() }
    if (present.size < 2) return emptyList()

    val gaps = mutableListOf<ChapterGap>()
    var runStart: Int? = null
    for (number in present.min()..present.max()) {
        when {
            number in present -> {
                runStart?.let { gaps += ChapterGap(it, number - 1) }
                runStart = null
            }
            runStart == null -> runStart = number
        }
    }
    return gaps
}
