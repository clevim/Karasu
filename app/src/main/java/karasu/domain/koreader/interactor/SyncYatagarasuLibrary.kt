package karasu.domain.koreader.interactor

import android.os.Build
import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.database.models.create
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.TrackPreferences
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
import karasu.domain.koreader.models.SyncTrack
import karasu.domain.koreader.models.SyncTrackerAccount
import karasu.domain.manga.interactor.GetLibraryManga
import karasu.domain.manga.interactor.GetManga
import karasu.domain.manga.interactor.InsertManga
import karasu.domain.manga.interactor.UpdateManga
import karasu.domain.manga.merged.interactor.MergedSources
import karasu.domain.manga.models.MangaUpdate
import karasu.domain.track.interactor.GetTrack
import karasu.domain.track.interactor.InsertTrack
import kotlinx.coroutines.CancellationException

/**
 * Full bidirectional library reconcile against the Yatagarasu companion hub (and, when enabled on
 * the server, the bundled official Suwayomi-Server).
 *
 * Syncs both directions:
 * - Karasu → Server: favorites, categories, chapter read/bookmark/page state, merged sources,
 *   tracking records, logged-in tracker accounts, extension repos, installed extensions, and
 *   explicit library removals.
 * - Server → Karasu: new categories, new library manga, remote library removals, chapter progress,
 *   merged sources, tracking records, logged-in tracker accounts, extension repos, and missing
 *   extensions notice.
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
    private val getTrack: GetTrack,
    private val insertTrack: InsertTrack,
    private val trackManager: TrackManager,
    private val trackPreferences: TrackPreferences,
) {

    data class Summary(
        val mangaAdded: Int = 0,
        val mangaRemoved: Int = 0,
        val chaptersUpdated: Int = 0,
        val mergedAdded: Int = 0,
        val reposAdded: Int = 0,
        val missingExtensions: Int = 0,
        val totalLibraryManga: Int = 0,
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
            val tracks = getTrack.awaitAllByMangaId(mangaId).map { tr ->
                SyncTrack(
                    trackerId = tr.sync_id.toInt(),
                    remoteId = tr.media_id,
                    libraryId = tr.library_id ?: 0L,
                    title = tr.title,
                    lastChapterRead = tr.last_chapter_read.toDouble(),
                    totalChapters = tr.total_chapters.toInt(),
                    score = tr.score.toDouble(),
                    status = tr.status,
                    trackingUrl = tr.tracking_url,
                    startedReadingDate = tr.started_reading_date,
                    finishedReadingDate = tr.finished_reading_date,
                    updatedAt = manga.last_update,
                )
            }
            syncMangas += SyncManga(
                sourceId = manga.source,
                url = manga.url,
                title = manga.title,
                thumbnailUrl = manga.thumbnail_url.orEmpty(),
                favorite = manga.favorite,
                categories = catNames,
                updatedAt = manga.last_update,
                mergedSources = merges,
                chapters = chapters,
                tracks = tracks,
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
        val nowMs = System.currentTimeMillis()
        val loggedTrackers = trackManager.services
            .filter { it.isLogged }
            .map { svc ->
                SyncTrackerAccount(
                    trackerId = svc.id.toInt(),
                    name = svc::class.simpleName.orEmpty(),
                    username = svc.getUsername(),
                    password = svc.getPassword(),
                    token = trackPreferences.trackToken(svc).get(),
                    authExpired = trackPreferences.trackAuthExpired(svc).get(),
                    updatedAt = nowMs,
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
            trackerAccounts = loggedTrackers,
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
                    val newThumb = remoteManga.thumbnailUrl.takeIf {
                        existing.thumbnail_url.isNullOrBlank() && it.isNotBlank()
                    }
                    if (id != null && (!existing.favorite || newThumb != null)) {
                        updateManga.await(
                            MangaUpdate(
                                id = id,
                                favorite = if (!existing.favorite) true else null,
                                dateAdded = if (!existing.favorite) System.currentTimeMillis() else null,
                                thumbnailUrl = newThumb,
                            ),
                        )
                        if (!existing.favorite) {
                            mangaAdded++
                        }
                        if (newThumb != null) {
                            existing.thumbnail_url = newThumb
                        }
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
                        thumbnail_url = remoteManga.thumbnailUrl.takeIf { it.isNotBlank() }
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
            } else if (localManga.thumbnail_url.isNullOrBlank() && remoteManga.thumbnailUrl.isNotBlank()) {
                val id = localManga.id
                if (id != null) {
                    updateManga.await(MangaUpdate(id = id, thumbnailUrl = remoteManga.thumbnailUrl))
                    localManga.thumbnail_url = remoteManga.thumbnailUrl
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

            // Apply synced tracking records (missing links or further reading progress).
            if (remoteManga.tracks.isNotEmpty()) {
                val localTracks = getTrack.awaitAllByMangaId(mangaId)
                val localTrackByKey = localTracks.associateBy { it.sync_id.toInt() to it.media_id }
                for (remoteTrack in remoteManga.tracks) {
                    if (remoteTrack.trackerId <= 0 || remoteTrack.remoteId <= 0L) continue
                    val existingTrack = localTrackByKey[remoteTrack.trackerId to remoteTrack.remoteId]
                    try {
                        if (existingTrack == null) {
                            val createdTrack = Track.create(remoteTrack.trackerId.toLong()).apply {
                                manga_id = mangaId
                                media_id = remoteTrack.remoteId
                                library_id = remoteTrack.libraryId.takeIf { it > 0L }
                                title = remoteTrack.title.ifBlank { remoteManga.title.ifBlank { localManga.title } }
                                last_chapter_read = remoteTrack.lastChapterRead.toFloat()
                                total_chapters = remoteTrack.totalChapters.toLong()
                                score = remoteTrack.score.toFloat()
                                status = remoteTrack.status
                                tracking_url = remoteTrack.trackingUrl
                                started_reading_date = remoteTrack.startedReadingDate
                                finished_reading_date = remoteTrack.finishedReadingDate
                            }
                            insertTrack.await(createdTrack)
                        } else if (remoteTrack.lastChapterRead.toFloat() > existingTrack.last_chapter_read) {
                            existingTrack.last_chapter_read = remoteTrack.lastChapterRead.toFloat()
                            if (remoteTrack.totalChapters > 0) {
                                existingTrack.total_chapters = maxOf(
                                    existingTrack.total_chapters,
                                    remoteTrack.totalChapters.toLong(),
                                )
                            }
                            if (remoteTrack.score > 0.0) {
                                existingTrack.score = remoteTrack.score.toFloat()
                            }
                            if (remoteTrack.status > 0) {
                                existingTrack.status = remoteTrack.status
                            }
                            if (remoteTrack.trackingUrl.isNotBlank() && existingTrack.tracking_url.isBlank()) {
                                existingTrack.tracking_url = remoteTrack.trackingUrl
                            }
                            if (remoteTrack.startedReadingDate > 0L && existingTrack.started_reading_date == 0L) {
                                existingTrack.started_reading_date = remoteTrack.startedReadingDate
                            }
                            if (remoteTrack.finishedReadingDate > 0L && existingTrack.finished_reading_date == 0L) {
                                existingTrack.finished_reading_date = remoteTrack.finishedReadingDate
                            }
                            insertTrack.await(existingTrack)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.w(e) { "Failed to apply synced track ${remoteTrack.trackerId}/${remoteTrack.remoteId}" }
                    }
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

        // 5. Restore logged-in tracker accounts from the hub when not logged in locally.
        for (acc in merged.trackerAccounts) {
            if (acc.authExpired) continue
            if (acc.token.isBlank() && acc.username.isBlank()) continue
            val service = trackManager.getService(acc.trackerId.toLong()) ?: continue
            if (service.isLogged) continue
            if (acc.username.isNotBlank()) {
                trackPreferences.trackUsername(service).set(acc.username)
            }
            if (acc.password.isNotBlank()) {
                trackPreferences.trackPassword(service).set(acc.password)
            }
            if (acc.token.isNotBlank()) {
                trackPreferences.trackToken(service).set(acc.token)
            }
            trackPreferences.trackAuthExpired(service).set(false)
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
            totalLibraryManga = finalKeys.size,
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
