package eu.kanade.tachiyomi.extension.api

import eu.kanade.tachiyomi.extension.ExtensionManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Keiyoushi's `index.pb` publishes absolute APK URLs on GitHub Releases. Rebuilding them as
 * `$repo/apk/$file` 404s, which surfaced in the app as every update stuck on "Retry".
 */
class ExtensionApkUrlTest {

    private fun info(apkName: String) = ExtensionManager.ExtensionInfo(
        apkName = apkName,
        pkgName = "eu.kanade.tachiyomi.extension.all.akuma",
        name = "Akuma",
        versionCode = 10,
        libVersion = 1.5,
        repoUrl = "https://raw.githubusercontent.com/keiyoushi/extensions/repo",
    )

    @Test
    fun `absolute apk url is used as-is`() {
        val url = "https://github.com/keiyoushi/extensions/releases/download/88e1412-0/tachiyomi-all.akuma-v1.4.10.apk"
        ExtensionApi().getApkUrl(info(url)) shouldBe url
    }

    @Test
    fun `relative apk name falls back to the legacy repo layout`() {
        ExtensionApi().getApkUrl(info("tachiyomi-all.akuma-v1.4.10.apk")) shouldBe
            "https://raw.githubusercontent.com/keiyoushi/extensions/repo/apk/tachiyomi-all.akuma-v1.4.10.apk"
    }
}
