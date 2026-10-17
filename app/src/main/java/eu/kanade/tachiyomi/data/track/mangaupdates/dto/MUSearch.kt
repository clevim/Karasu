package eu.kanade.tachiyomi.data.track.mangaupdates.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MUSearchResult(
    val results: List<MUSearchResultItem>,
)

@Serializable
data class MUSearchResultItem(
    val record: MURecord,
    /** The name the search matched, which is one of the series' other names when not its title. */
    @SerialName("hit_title")
    val hitTitle: String? = null,
)
