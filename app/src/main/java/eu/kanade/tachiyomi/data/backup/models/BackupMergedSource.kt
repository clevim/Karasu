package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * A source merged into a manga.
 *
 * Only the link is backed up. The manga row it points at isn't a favourite, so the backup
 * never contains it; restore recreates a bare row and the first refresh fills it in.
 */
@Serializable
data class BackupMergedSource(
    @ProtoNumber(1) var source: Long,
    @ProtoNumber(2) var url: String,
    @ProtoNumber(3) var priority: Int = 0,
)
