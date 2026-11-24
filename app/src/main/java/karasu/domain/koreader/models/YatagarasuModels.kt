package karasu.domain.koreader.models

import karasu.translation.model.PageTranslation
import kotlinx.serialization.Serializable

@Serializable
data class YatagarasuSuwayomiHealth(
    val configured: Boolean = false,
    val reachable: Boolean = false,
    val version: String = "",
)

@Serializable
data class YatagarasuTranslateHealth(
    val configured: Boolean = false,
    val provider: String = "",
    val model: String = "",
)

@Serializable
data class YatagarasuHealth(
    val name: String = "yatagarasu",
    val version: String = "1",
    val entries: Int = 0,
    val capabilities: List<String> = emptyList(),
    val suwayomi: YatagarasuSuwayomiHealth = YatagarasuSuwayomiHealth(),
    val translate: YatagarasuTranslateHealth = YatagarasuTranslateHealth(),
)

@Serializable
data class SyncCategory(
    val name: String,
    val order: Int = 0,
    val flags: Long = 0L,
)

@Serializable
data class SyncMergedSource(
    val sourceId: Long,
    val url: String,
    val priority: Int = 0,
    val updatesEnabled: Boolean = true,
)

@Serializable
data class SyncChapter(
    val url: String,
    val name: String = "",
    val chapterNumber: Double = 0.0,
    val scanlator: String = "",
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val lastPageRead: Int = 0,
    val updatedAt: Long = 0L,
)

@Serializable
data class SyncMangaKey(
    val sourceId: Long,
    val url: String,
)

@Serializable
data class SyncManga(
    val sourceId: Long,
    val url: String,
    val title: String = "",
    val favorite: Boolean = true,
    val categories: List<String> = emptyList(),
    val updatedAt: Long = 0L,
    val mergedSources: List<SyncMergedSource> = emptyList(),
    val chapters: List<SyncChapter> = emptyList(),
)

@Serializable
data class SyncExtension(
    val pkgName: String,
    val name: String = "",
    val lang: String = "",
    val versionCode: Long = 0L,
)

@Serializable
data class SyncPayload(
    val deviceId: String = "",
    val lastSyncAt: Long = 0L,
    val serverTime: Long = 0L,
    val categories: List<SyncCategory> = emptyList(),
    val mangas: List<SyncManga> = emptyList(),
    val deletedMangaKeys: List<SyncMangaKey> = emptyList(),
    val extensionRepos: List<String> = emptyList(),
    val installedExtensions: List<SyncExtension> = emptyList(),
    val missingExtensions: List<SyncExtension> = emptyList(),
)

@Serializable
data class RemoteBackupInfo(
    val id: String,
    val name: String = "",
    val size: Long = 0L,
    val createdAt: Long = 0L,
)

@Serializable
data class RemoteBackupListResponse(
    val backups: List<RemoteBackupInfo> = emptyList(),
)

@Serializable
data class RemoteSeriesNotes(
    val notes: String = "",
    val glossary: Map<String, String> = emptyMap(),
)

@Serializable
data class RemoteTranslateRequest(
    val fromLang: String,
    val toLang: String,
    val seriesNotes: RemoteSeriesNotes = RemoteSeriesNotes(),
    val pages: Map<String, PageTranslation>,
)

@Serializable
data class RemoteTranslateResponse(
    val pages: Map<String, PageTranslation> = emptyMap(),
    val learned: Map<String, String> = emptyMap(),
)
