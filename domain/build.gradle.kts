plugins {
    id("karasu.android.library")
    kotlin("multiplatform")
    alias(kotlinx.plugins.serialization)
}

kotlin {
    androidTarget()
    sourceSets {
        commonMain {
            dependencies {
                implementation(projects.source.api)
            }
        }
        commonTest {
            dependencies {
                implementation(libs.bundles.test)
                implementation(kotlinx.coroutines.test)
            }
        }
        androidMain {
            dependencies {
            }
        }
    }
}

android {
    namespace = "karasu.domain"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}
