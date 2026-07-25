import karasu.build.configureAndroid
import karasu.build.configureTest

plugins {
    id("com.android.library")
}

android {
    configureAndroid(this)
    configureTest()
}
