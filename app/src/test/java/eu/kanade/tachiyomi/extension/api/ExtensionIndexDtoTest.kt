package eu.kanade.tachiyomi.extension.api

import io.kotest.matchers.shouldBe
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test

/**
 * Guards the field numbers in [ExtensionIndexDto] against Keiyoushi's real `index.pb` layout,
 * including unknown fields (`Resources.jar_url` = 501) that must be skipped, not fatal.
 */
class ExtensionIndexDtoTest {

    @Test
    fun `decodes keiyoushi index`() {
        val index = ProtoBuf.decodeFromByteArray<ExtensionIndexDto>(SAMPLE.hexToByteArray())

        index.name shouldBe "Keiyoushi"
        index.signingKey shouldBe "9add"
        index.contact?.discord shouldBe "https://discord.gg/x"

        val extensions = index.extensionList!!.extensions
        extensions.size shouldBe 2

        with(extensions.first()) {
            name shouldBe "AHottie"
            packageName shouldBe "eu.kanade.tachiyomi.extension.all.ahottie"
            extensionLib shouldBe "1.6"
            versionCode shouldBe 4L
            versionName shouldBe "1.6.4"
            contentWarning shouldBe ExtensionIndexDto.ContentWarning.NSFW
            resources!!.apkUrl.substringAfterLast('/') shouldBe "tachiyomi-all.ahottie-v1.6.4.apk"
            sources.single().id shouldBe 6289731484943315811L
            sources.single().language shouldBe "all"
        }

        // extension-lib 1.2 is below LIB_VERSION_MIN, kept in the DTO and filtered at mapping time
        extensions[1].extensionLib shouldBe "1.2"
        extensions[1].contentWarning shouldBe ExtensionIndexDto.ContentWarning.SAFE
    }

    companion object {
        // protoc --encode=Index against the real repo schema; trimmed to two extensions
        private const val SAMPLE =
            "0a094b6569796f7573686912034b45491a043961646422330a1b68747470733a2f2f6b6569796f757368692e6769" +
                "746875622e696f121468747470733a2f2f646973636f72642e67672f78aa06eb030add020a0741486f7474696512" +
                "2965752e6b616e6164652e7461636869796f6d692e657874656e73696f6e2e616c6c2e61686f747469651ae7010a" +
                "5a68747470733a2f2f63646e2e6a7364656c6976722e6e65742f67682f6b6569796f757368692f657874656e7369" +
                "6f6e73407265706f2f61706b2f7461636869796f6d692d616c6c2e61686f747469652d76312e362e342e61706b12" +
                "2668747470733a2f2f63646e2e6a7364656c6976722e6e65742f67682f782f69636f6e2e706e67aa1f6068747470" +
                "733a2f2f7261772e67697468756275736572636f6e74656e742e636f6d2f6b6569796f757368692f657874656e73" +
                "696f6e732f7265706f2f6a61722f7461636869796f6d692d616c6c2e61686f747469652d76312e362e342e6a6172" +
                "2203312e3628043205312e362e343803422d08e3e68be5e0aee7a457120741486f747469651a03616c6c22136874" +
                "7470733a2f2f61686f747469652e746f700a88010a074f6c6420457874122565752e6b616e6164652e7461636869" +
                "796f6d692e657874656e73696f6e2e616c6c2e6f6c641a2c0a2a68747470733a2f2f782f61706b2f746163686979" +
                "6f6d692d616c6c2e6f6c642d76312e322e332e61706b2203312e3228033205312e322e3338014218080112034f6c" +
                "641a02656e220b68747470733a2f2f6f6c64"
    }
}
