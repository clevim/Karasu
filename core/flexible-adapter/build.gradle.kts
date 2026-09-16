plugins {
    id("karasu.android.library")
}

// Vendored copy of arkon/FlexibleAdapter @ c8013533 (flexible-adapter + flexible-adapter-ui merged).
// JitPack can no longer build that commit (its grabver plugin depended on jcenter), so the source
// lives here. Pure Java, no Kotlin. See LICENSE (Apache-2.0).
android {
    namespace = "eu.davidea.flexibleadapter"
}

dependencies {
    implementation(androidx.appcompat)
    implementation(androidx.recyclerview)
    implementation(libs.material)
}
