package eu.kanade.tachiyomi.data.track.anilist.dto

import kotlinx.serialization.Serializable

@Serializable
data class ALSearchItem(
    val id: Long,
    val title: ALItemTitle,
    val coverImage: ItemCover,
    val description: String?,
    val format: String,
    val status: String?,
    val startDate: ALFuzzyDate,
    val chapters: Long?,
    val averageScore: Int?,
    val synonyms: List<String> = emptyList(),
) {
    fun toALManga(): ALManga = ALManga(
        remoteId = id,
        title = title.userPreferred,
        imageUrl = coverImage.large,
        description = description,
        format = format.replace("_", "-"),
        publishingStatus = status ?: "",
        startDateFuzzy = startDate.toEpochMilli(),
        totalChapters = chapters ?: 0,
        averageScore = averageScore ?: -1,
        alternativeTitles = (listOfNotNull(title.english, title.romaji, title.native) + synonyms)
            .filter { it != title.userPreferred }
            .distinct(),
    )
}

@Serializable
data class ALItemTitle(
    val userPreferred: String,
    val romaji: String? = null,
    val english: String? = null,
    val native: String? = null,
)

@Serializable
data class ItemCover(
    val large: String,
)
