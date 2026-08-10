plugins {
    kotlin("jvm")
}

// Plain JVM, not an Android library: source/api consumes this from commonMain, and an AAR is not
// resolvable from there. It has no Android dependencies anyway.
dependencies {
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
}

kotlin {
    jvmToolchain(17)
}
