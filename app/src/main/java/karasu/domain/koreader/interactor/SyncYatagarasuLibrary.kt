package karasu.domain.koreader.interactor

import android.os.Build
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.extension.ExtensionManager
import karasu.data.koreader.KoreaderApi
import karasu.domain.category.interactor.GetCategories
import karasu.domain.chapter.interactor.GetChapter
import karasu.domain.chapter.interactor.UpdateChapter
import karasu.domain.chapter.models.ChapterUpdate
import karasu.domain.extension.repo.interactor.CreateExtensionRepo
import karasu.domain.extension.repo.interactor.GetExtensionRepo
import karasu.domain.koreader.KoreaderPreferences
import karasu.domain.koreader.models.SyncCategory
import karasu.domain.koreader.models.SyncChapter
import karasu.domain.koreader.models.SyncExtension
import karasu.domain.koreader.models.SyncManga
import karasu.domain.koreader.models.SyncMangaKey
import karasu.domain.koreader.models.SyncMergedSource
import karasu.domain.koreader.models.SyncPayload
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.manga.merged.interactor.MergedSources
import kotlinx.coroutines.CancellationException

/**
 * Bidirectional library reconcile against the Yatagarasu companion hub (and, when enabled on the
 * server, the bundled official Suwayomi-Server).
 *
 * Entities are matched by natural keys — manga by `(sourceId, url)` and chapters by `url` — so
 * database row IDs never collide across devices or backup restores.
 */
class SyncYatagarasuLibrary(
    private val api: KoreaderApi,
    private val preferences: KoreaderPreferences,
    private val getLibraryManga: GetLibraryManga,
    private val getCategories: GetCategories,
    private val getChapter: GetChapter,
    private val updateChapter: UpdateChapter,
    private val mergedSources: MergedSources,
    private val getExtensionRepo: GetExtensionRepo,
    private val createExtensionRepo: CreateExtensionRepo,
    private val extensionManager: ExtensionManager,
) {

    data class Summary(
        val chaptersUpdated: Int = 0,
        val mergedAdded: Int = 0,
        val reposAdded: Int = 0,
        val missingExtensions: Int = 0,
    )

    suspend fun await(): Summary {
        if (!api.isConfigured || !preferences.syncLibrary().get()) return Summary()

        val library = getLibraryManga.await()
        val categories = getCategories.await()
        val categoryById = categories.associateBy { it.id }

        // Group library rows by manga ID because a manga in multiple categories appears once per category.
        val grouped = library.groupBy { it.manga.id!! }
        val localKeys = mutableSetOf<String>()
        val syncMangas = mutableListOf<SyncManga>()

        for ((mangaId, entries) in grouped) {
            val manga = entries.first().manga
            val key = naturalKey(manga.source, manga.url)
            localKeys += key

            val catNames = entries.mapNotNull { categoryById[it.category]?.name }.distinct()
            val merges = mergedSources.await(mangaId).map {
                SyncMergedSource(
                    sourceId = it.source,
                    url = it.url,
                    priority = it.priority,
                    updatesEnabled = it.updatesEnabled,
                )
            }
            val chapters = getChapter.awaitAllRaw(mangaId, filterScanlators = false).map { ch ->
                SyncChapter(
                    url = ch.url,
                    name = ch.name,
                    chapterNumber = ch.chapter_number.toDouble(),
                    scanlator = ch.scanlator.orEmpty(),
                    read = ch.read,
                    bookmark = ch.bookmark,
                    lastPageRead = ch.last_page_read,
                    updatedAt = maxOf(ch.date_fetch, ch.date_upload),
                )
            }
            syncMangas += SyncManga(
                sourceId = manga.source,
                url = manga.url,
                title = manga.title,
                favorite = manga.favorite,
                categories = catNames,
                updatedAt = manga.last_update,
                mergedSources = merges,
                chapters = chapters,
            )
        }

        // Detect manga removed from library since the last reconcile so the hub doesn't resurrect them.
        val previousKeys = preferences.syncedMangaKeys().get()
        val deletedKeys = (previousKeys - localKeys).mapNotNull { parseKey(it) }

        val localRepos = getExtensionRepo.getAll()
        val installedExts = extensionManager.installedExtensionsFlow.value.map {
            SyncExtension(
                pkgName = it.pkgName,
                name = it.name,
                lang = it.lang,
                versionCode = it.versionCode,
            )
        }

        val payload = SyncPayload(
            deviceId = "${Build.MANUFACTURER}-${Build.MODEL}",
            lastSyncAt = preferences.lastLibrarySyncAt().get(),
            categories = categories.map {
                SyncCategory(name = it.name, order = it.order, flags = it.flags.toLong())
            },
            mangas = syncMangas,
            deletedMangaKeys = deletedKeys,
            extensionRepos = localRepos.map { it.baseUrl },
            installedExtensions = installedExts,
        )

        val merged = api.reconcileSync(payload)

        // Apply merged state back to local DB.
        var chaptersUpdated = 0
        var mergedAdded = 0
        val localMangaByKey = grouped.values.associate { list ->
            val m = list.first().manga
            naturalKey(m.source, m.url) to m
        }

        for (remoteManga in merged.mangas) {
            val localManga = localMangaByKey[naturalKey(remoteManga.sourceId, remoteManga.url)] ?: continue
            val mangaId = localManga.id ?: continue

            // 1. Apply chapter read/bookmark/page updates.
            val localChapters = getChapter.awaitAllRaw(mangaId, filterScanlators = false)
            val remoteByUrl = remoteManga.chapters.associateBy { it.url }
            val updates = mutableListOf<ChapterUpdate>()
            for (ch in localChapters) {
                val remoteCh = remoteByUrl[ch.url] ?: continue
                val newRead = ch.read || remoteCh.read
                val newBookmark = ch.bookmark || remoteCh.bookmark
                val newPage = maxOf(ch.last_page_read, remoteCh.lastPageRead)
                if (newRead != ch.read || newBookmark != ch.bookmark || newPage != ch.last_page_read) {
                    updates += ChapterUpdate(
                        id = ch.id!!,
                        read = newRead,
                        bookmark = newBookmark,
                        lastPageRead = newPage.toLong(),
                    )
                }
            }
            if (updates.isNotEmpty()) {
                updateChapter.awaitAll(updates)
                chaptersUpdated += updates.size
            }

            // 2. Apply missing merged sources.
            for (ms in remoteManga.mergedSources) {
                if (mergedSources.addAtEnd(mangaId, ms.sourceId, ms.url, localManga.source)) {
                    mergedAdded++
                }
            }
        }

        // 3. Add missing extension repos from the hub.
        var reposAdded = 0
        val existingRepoUrls = localRepos.map { it.baseUrl.trimEnd('/') }.toSet()
        for (repoUrl in merged.extensionRepos) {
            val normalized = repoUrl.trimEnd('/')
            if (normalized.isBlank() || normalized in existingRepoUrls) continue
            try {
                if (createExtensionRepo.await(normalized) is CreateExtensionRepo.Result.Success) {
                    reposAdded++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to add synced extension repo $normalized" }
            }
        }

        preferences.syncedMangaKeys().set(localKeys)
        preferences.lastLibrarySyncAt().set(merged.serverTime.takeIf { it > 0 } ?: System.currentTimeMillis())

        return Summary(
            chaptersUpdated = chaptersUpdated,
            mergedAdded = mergedAdded,
            reposAdded = reposAdded,
            missingExtensions = merged.missingExtensions.size,
        )
    }

    private fun naturalKey(sourceId: Long, url: String) = "$sourceId|$url"

    private fun parseKey(raw: String): SyncMangaKey? {
        val idx = raw.indexOf('|')
        if (idx <= 0) return null
        val sid = raw.substring(0, idx).toLongOrNull() ?: return null
        val url = raw.substring(idx + 1)
        return SyncMangaKey(sid, url)
    }
}
