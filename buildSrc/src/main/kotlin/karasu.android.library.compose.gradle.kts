import karasu.build.configureCompose

plugins {
    id("com.android.library")
}

android {
    configureCompose(this)
}
