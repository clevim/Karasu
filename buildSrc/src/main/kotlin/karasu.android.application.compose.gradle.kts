import karasu.build.configureCompose

plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    configureCompose(this)
}
