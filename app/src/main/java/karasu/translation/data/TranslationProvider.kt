package karasu.translation.data

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.util.storage.DiskUtil
import karasu.domain.storage.StorageManager
import uy.kohesive.injekt.injectLazy

/**
 * Where chapter translations are stored, mirroring the downloads layout:
 * /<root>/translations/<source>/<manga>/<chapter>.json
 */
class TranslationProvider {

    private val storageManager: StorageManager by injectLazy()

    private val translationDir: UniFile?
        get() = storageManager.getTranslationsDirectory()

    fun getMangaDir(manga: Manga, source: Source): UniFile? = translationDir
        ?.createDirectory(getSourceDirName(source))
        ?.createDirectory(getMangaDirName(manga))

    fun findSourceDir(source: Source): UniFile? = translationDir?.findFile(getSourceDirName(source))

    fun findMangaDir(manga: Manga, source: Source): UniFile? =
        findSourceDir(source)?.findFile(getMangaDirName(manga))

    fun findTranslationFile(chapter: Chapter, manga: Manga, source: Source): UniFile? =
        findMangaDir(manga, source)?.findFile(getTranslationFileName(chapter))

    private fun getSourceDirName(source: Source): String = DiskUtil.buildValidFilename(source.toString())

    private fun getMangaDirName(manga: Manga): String = DiskUtil.buildValidFilename(manga.title)

    fun getTranslationFileName(chapter: Chapter): String {
        val name = chapter.name.ifBlank { "Chapter" }
        return DiskUtil.buildValidFilename(
            if (chapter.scanlator.isNullOrBlank()) "$name.json" else "${chapter.scanlator}_$name.json",
        )
    }
}
