plugins {
    id("karasu.android.library")
    kotlin("android")
    alias(kotlinx.plugins.serialization)
}

android {
    namespace = "karasu.core.archive"
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.libarchive)
    implementation(libs.unifile)
}
