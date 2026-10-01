package eu.kanade.tachiyomi.data.backup.models

import karasu.domain.extension.repo.model.ExtensionRepo
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * An extension repository. Numbered as Mihon numbers its own — the old `BackupExtensionRepos` and
 * the newer `BackupExtensionStore` agree on 1 to 5 — so a Mihon backup brings its repos along and
 * a backup from here restores in Mihon. Its later fields (6–8) are skipped on read.
 */
@Serializable
class BackupExtensionRepo(
    /** Mihon's newer backups store the index file's url here; [normalizedBaseUrl] strips it. */
    @ProtoNumber(1) var baseUrl: String,
    @ProtoNumber(2) var name: String,
    @ProtoNumber(3) var shortName: String? = null,
    @ProtoNumber(4) var website: String = "",
    @ProtoNumber(5) var signingKeyFingerprint: String,
) {
    val normalizedBaseUrl: String
        get() = INDEX_FILES.fold(baseUrl.trim().trimEnd('/')) { url, file -> url.removeSuffix("/$file") }
}

fun ExtensionRepo.toBackup() = BackupExtensionRepo(baseUrl, name, shortName, website, signingKeyFingerprint)

private val INDEX_FILES = listOf("index.min.json", "index.json", "index.pb", "repo.json")
