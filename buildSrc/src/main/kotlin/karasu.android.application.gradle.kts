import karasu.build.configureAndroid
import karasu.build.configureTest

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    defaultConfig {
        targetSdk = AndroidConfig.TARGET_SDK
    }
    configureAndroid(this)
    configureTest()
}
