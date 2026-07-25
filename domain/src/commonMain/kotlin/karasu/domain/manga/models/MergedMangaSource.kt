package karasu.domain.manga.models

/**
 * An extra source a manga can pull chapters from when its primary source fails.
 *
 * The manga's primary source stays in `mangas.source`, so nothing that reads
 * `manga.source` needs to know merges exist. This table only answers
 * "where else does this manga live, and in what order do we try them".
 */
data class MergedMangaSource(
    val id: Long,
    val mangaId: Long,
    val source: Long,
    val url: String,
    val priority: Int,
)
