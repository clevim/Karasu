package karasu.translation.model

/**
 * What the translator has been told about a series.
 *
 * The two halves are treated differently on purpose.
 *
 * [notes] is what the reader wrote, and it is an *instruction*: it changes how a line should come
 * out, so two translations made under different notes are different translations and the cache has
 * to tell them apart.
 *
 * [glossary] is what earlier chapters settled on. It is derived from translations the cache
 * already holds, so it can never contradict them — and it grows a little with almost every
 * chapter. Letting it into the cache key would open a fresh namespace each time a chapter learned
 * a word, and the cache would never hit again: the feature meant to save tokens would spend them.
 */
data class SeriesNotes(
    val notes: String = "",
    val glossary: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = notes.isBlank() && glossary.isEmpty()

    /**
     * The entries worth sending with [texts]: a term nowhere in the lines being translated is
     * nothing but tokens, and the whole glossary is resent with every batch of every chapter.
     */
    fun glossaryFor(texts: List<String>): Map<String, String> =
        glossary.filterKeys { term -> texts.any { it.contains(term, ignoreCase = true) } }
}
