package eu.kanade.tachiyomi.data.backup.restore

import android.content.Context
import android.net.Uri
import eu.kanade.tachiyomi.data.backup.BackupNotifier
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionRepo
import karasu.domain.extension.repo.ExtensionRepoRepository
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaBackupRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.PreferenceBackupRestorer
import eu.kanade.tachiyomi.util.BackupUtil
import eu.kanade.tachiyomi.util.system.createFileInCacheDir
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import karasu.i18n.MR
import karasu.util.lang.getString
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class BackupRestorer(
    val context: Context,
    val notifier: BackupNotifier,
    private val categoriesBackupRestorer: CategoriesBackupRestorer = CategoriesBackupRestorer(),
    private val mangaBackupRestorer: MangaBackupRestorer = MangaBackupRestorer(),
    private val preferenceBackupRestorer: PreferenceBackupRestorer = PreferenceBackupRestorer(context),
    private val extensionRepos: ExtensionRepoRepository = Injekt.get(),
) {
    private var restoreAmount = 0
    private var restoreProgress = 0

    /**
     * Mapping of source ID to source name from backup data
     */
    private var sourceMapping: Map<Long, String> = emptyMap()

    private val errors = mutableListOf<Pair<Date, String>>()

    suspend fun restore(uri: Uri) {
        val startTime = System.currentTimeMillis()
        restoreProgress = 0
        errors.clear()

        performRestore(uri)

        val endTime = System.currentTimeMillis()
        val time = endTime - startTime

        val logFile = writeErrorLog()

        notifier.showRestoreComplete(time, errors.size, logFile.parent, logFile.name)
    }

    private suspend fun performRestore(uri: Uri) {
        val backup = BackupUtil.decodeBackup(context, uri)

        restoreAmount = backup.backupManga.size + 3 // +3 for categories, app prefs, source prefs

        sourceMapping = backup.backupSources.associate { it.sourceId to it.name }

        coroutineScope {
            // Restore categories
            if (backup.backupCategories.isNotEmpty()) {
                ensureActive()
                categoriesBackupRestorer.restoreCategories(backup.backupCategories) {
                    restoreProgress += 1
                    showRestoreProgress(restoreProgress, restoreAmount, context.getString(MR.strings.categories))
                }
            }

            ensureActive()
            restoreExtensionRepos(backup.backupExtensionRepos)

            ensureActive()
            preferenceBackupRestorer.restoreAppPreferences(backup.backupPreferences) {
                restoreProgress += 1
                showRestoreProgress(restoreProgress, restoreAmount, context.getString(MR.strings.app_settings))
            }
            ensureActive()
            preferenceBackupRestorer.restoreSourcePreferences(backup.backupSourcePreferences) {
                restoreProgress += 1
                showRestoreProgress(restoreProgress, restoreAmount, context.getString(MR.strings.source_settings))
            }

            // Restore individual manga
            backup.backupManga.forEach {
                ensureActive()
                mangaBackupRestorer.restoreManga(
                    it,
                    backup.backupCategories,
                    onComplete = { manga ->
                        restoreProgress += 1
                        showRestoreProgress(restoreProgress, restoreAmount, manga.title)
                    },
                    onError = { manga, e ->
                        val sourceName = sourceMapping[manga.source] ?: manga.source.toString()
                        errors.add(Date() to "${manga.title} [$sourceName]: ${e.message}")
                    },
                )
                if (it.notes.isNotBlank()) {
                    errors.add(Date() to "${it.title}: notes not imported, this app has no notes. They were: ${it.notes}")
                }
            }
        }
        // TODO: optionally trigger online library + tracker update
    }

    /**
     * Adds the repos this install does not have yet. One already here — by url, or by signing key
     * under another url — is left as it is: the user may have moved it on purpose.
     */
    private suspend fun restoreExtensionRepos(repos: List<BackupExtensionRepo>) {
        repos.forEach { repo ->
            val baseUrl = repo.normalizedBaseUrl
            try {
                if (extensionRepos.getRepository(baseUrl) != null) return@forEach
                if (extensionRepos.getRepositoryBySigningKeyFingerprint(repo.signingKeyFingerprint) != null) return@forEach
                extensionRepos.insertRepository(baseUrl, repo.name, repo.shortName, repo.website, repo.signingKeyFingerprint)
            } catch (e: Exception) {
                errors.add(Date() to "Extension repo $baseUrl: ${e.message}")
            }
        }
    }

    /**
     * Called to update dialog in [BackupConst]
     *
     * @param progress restore progress
     * @param amount total restoreAmount of manga
     * @param title title of restored manga
     */
    private fun showRestoreProgress(
        progress: Int,
        amount: Int,
        title: String,
    ) {
        notifier.showRestoreProgress(title, progress, amount)
    }

    internal fun writeErrorLog(): File {
        try {
            if (errors.isNotEmpty()) {
                val file = context.createFileInCacheDir("karasu_restore.txt")
                val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

                file.bufferedWriter().use { out ->
                    errors.forEach { (date, message) ->
                        out.write("[${sdf.format(date)}] $message\n")
                    }
                }
                return file
            }
        } catch (e: Exception) {
            // Empty
        }
        return File("")
    }
}
