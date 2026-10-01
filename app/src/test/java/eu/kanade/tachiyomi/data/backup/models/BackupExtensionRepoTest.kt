package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class BackupExtensionRepoTest {

    /** Mihon's current `BackupExtensionStore`, field for field. */
    @Serializable
    class MihonStore(
        @ProtoNumber(1) val indexUrl: String,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val badgeLabel: String?,
        @ProtoNumber(5) val signingKey: String,
        @ProtoNumber(4) val contactWebsite: String,
        @ProtoNumber(6) val contactDiscord: String?,
        @ProtoNumber(7) val isLegacy: Boolean?,
        @ProtoNumber(8) val extensionListUrl: String?,
    )

    @Test
    fun `a repo from a current Mihon backup reads, at its base url`() {
        val bytes = ProtoBuf.encodeToByteArray(
            MihonStore.serializer(),
            MihonStore(
                indexUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json",
                name = "Keiyoushi",
                badgeLabel = "KS",
                signingKey = "9add655a",
                contactWebsite = "https://keiyoushi.github.io",
                contactDiscord = "https://discord.gg/x",
                isLegacy = false,
                extensionListUrl = "https://example.org/list",
            ),
        )
        val repo = ProtoBuf.decodeFromByteArray(BackupExtensionRepo.serializer(), bytes)

        assertEquals("https://raw.githubusercontent.com/keiyoushi/extensions/repo", repo.normalizedBaseUrl)
        assertEquals("Keiyoushi", repo.name)
        assertEquals("KS", repo.shortName)
        assertEquals("https://keiyoushi.github.io", repo.website)
        assertEquals("9add655a", repo.signingKeyFingerprint)
    }

    @Test
    fun `a base url is left alone`() {
        val repo = BackupExtensionRepo("https://example.org/repo/", "n", signingKeyFingerprint = "k")
        assertEquals("https://example.org/repo", repo.normalizedBaseUrl)
    }
}
