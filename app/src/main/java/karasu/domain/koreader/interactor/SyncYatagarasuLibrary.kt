package karasu.domain.koreader.interactor

import android.os.Build
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.extension.ExtensionManager
import karasu.data.koreader.KoreaderApi
import karasu.domain.category.interactor.GetCategories
import karasu.domain.category.interactor.InsertCategories
import karasu.domain.category.interactor.SetMangaCategories
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
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.InsertManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.manga.models.MangaUpdate
import kotlinx.coroutines.CancellationException

/**
 * Full bidirectional library reconcile against the Yatagarasu companion hub (and, when enabled on
 * the server, the bundled official Suwayomi-Server).
 *
 * Syncs both directions:
 * - Karasu → Server: favorites, categories, chapter read/bookmark/page state, merged sources,
 *   extension repos, installed extensions, and explicit library removals.
 * - Server → Karasu: new categories, new library manga, remote library removals, chapter progress,
 *   merged sources, extension repos, and missing extensions notice.
 */
class SyncYatagarasuLibrary(
    private val api: KoreaderApi,
    private val preferences: KoreaderPreferences,
    private val getLibraryManga: GetLibraryManga,
    private val getManga: GetManga,
    private val insertManga: InsertManga,
    private val updateManga: UpdateManga,
    private val getCategories: GetCategories,
    private val insertCategories: InsertCategories,
    private val setMangaCategories: SetMangaCategories,
    private val getChapter: GetChapter,
    private val updateChapter: UpdateChapter,
    private val mergedSources: MergedSources,
    private val getExtensionRepo: GetExtensionRepo,
    private val createExtensionRepo: CreateExtensionRepo,
    private val extensionManager: ExtensionManager,
) {

    data class Summary(
        val mangaAdded: Int = 0,
        val mangaRemoved: Int = 0,
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
                // Send 0 for untouched chapters so a fresh chapter fetch never overwrites read state
                // recorded on another device or Suwayomi.
                val hasProgress = ch.read || ch.bookmark || ch.last_page_read > 0
                SyncChapter(
                    url = ch.url,
                    name = ch.name,
                    chapterNumber = ch.chapter_number.toDouble(),
                    scanlator = ch.scanlator.orEmpty(),
                    read = ch.read,
                    bookmark = ch.bookmark,
                    lastPageRead = ch.last_page_read,
                    updatedAt = if (hasProgress) maxOf(ch.date_fetch, ch.date_upload) else 0L,
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

        // 1. Pull new categories from the hub.
        val catByName = categories.associateBy { it.name.lowercase() }.toMutableMap()
        var categoriesChanged = false
        for (remoteCat in merged.categories) {
            val name = remoteCat.name.trim()
            if (name.isBlank() || name.lowercase() in catByName) continue
            try {
                val created = Category.create(name).apply {
                    order = remoteCat.order
                    flags = remoteCat.flags.toInt()
                }
                insertCategories.awaitOne(created)
                categoriesChanged = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(e) { "Failed to create synced category $name" }
            }
        }
        val refreshedCatByName = if (categoriesChanged) {
            getCategories.await().associateBy { it.name.lowercase() }
        } else {
            catByName
        }

        // 2. Apply merged manga (including new manga added on server/other devices).
        var mangaAdded = 0
        var chaptersUpdated = 0
        var mergedAdded = 0
        val localMangaByKey = grouped.values.associate { list ->
            val m = list.first().manga
            naturalKey(m.source, m.url) to m
        }.toMutableMap()

        val remoteKeys = mutableSetOf<String>()

        for (remoteManga in merged.mangas) {
            if (!remoteManga.favorite) continue
            val key = naturalKey(remoteManga.sourceId, remoteManga.url)
            remoteKeys += key

            var localManga = localMangaByKey[key]
            if (localManga == null) {
                // Pull new library manga added on Yatagarasu / Suwayomi / another device.
                val existing = getManga.awaitByUrlAndSource(remoteManga.url, remoteManga.sourceId)
                if (existing != null) {
                    val id = existing.id
                    if (id != null && !existing.favorite) {
                        updateManga.await(
                            MangaUpdate(
                                id = id,
                                favorite = true,
                                dateAdded = System.currentTimeMillis(),
                            ),
                        )
                        mangaAdded++
                    }
                    localManga = existing
                } else {
                    val created = Manga.create(
                        remoteManga.url,
                        remoteManga.title.ifBlank { remoteManga.url },
                        remoteManga.sourceId,
                    ).apply {
                        favorite = true
                        date_added = System.currentTimeMillis()
                    }
                    val insertedId = insertManga.await(created)
                    if (insertedId != null) {
                        created.id = insertedId
                        localManga = created
                        mangaAdded++
                    }
                }
                if (localManga != null) {
                    localMangaByKey[key] = localManga
                }
            }

            val mangaId = localManga?.id ?: continue

            // Assign synced categories when present.
            if (remoteManga.categories.isNotEmpty()) {
                val catIds = remoteManga.categories.mapNotNull { refreshedCatByName[it.lowercase()]?.id?.toLong() }
                if (catIds.isNotEmpty()) {
                    setMangaCategories.await(mangaId, catIds)
                }
            }

            // Apply chapter read/bookmark/page updates.
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

            // Apply missing merged sources.
            for (ms in remoteManga.mergedSources) {
                if (mergedSources.addAtEnd(mangaId, ms.sourceId, ms.url, localManga.source)) {
                    mergedAdded++
                }
            }
        }

        // 3. Apply remote library deletions: previously synced local manga that disappeared from hub.
        var mangaRemoved = 0
        val remotelyDeleted = (previousKeys intersect localKeys) - remoteKeys
        for (delKey in remotelyDeleted) {
            val localManga = localMangaByKey[delKey] ?: continue
            val mangaId = localManga.id ?: continue
            updateManga.await(MangaUpdate(id = mangaId, favorite = false))
            mangaRemoved++
        }

        // 4. Add missing extension repos from the hub.
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

        val missingLabels = merged.missingExtensions
            .mapNotNull { ext ->
                val displayName = ext.name.ifBlank { ext.pkgName }.trim()
                if (displayName.isBlank()) {
                    null
                } else if (ext.lang.isBlank()) {
                    displayName
                } else {
                    "$displayName (${ext.lang})"
                }
            }
            .toSet()
        preferences.missingExtensions().set(missingLabels)
        preferences.missingExtensionPkgs().set(
            merged.missingExtensions.map { it.pkgName }.filter { it.isNotBlank() }.toSet(),
        )

        val finalKeys = (localKeys - remotelyDeleted) + remoteKeys
        preferences.syncedMangaKeys().set(finalKeys)
        preferences.lastLibrarySyncAt().set(merged.serverTime.takeIf { it > 0 } ?: System.currentTimeMillis())

        return Summary(
            mangaAdded = mangaAdded,
            mangaRemoved = mangaRemoved,
            chaptersUpdated = chaptersUpdated,
            mergedAdded = mergedAdded,
            reposAdded = reposAdded,
            missingExtensions = missingLabels.size,
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
