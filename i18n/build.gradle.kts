import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import karasu.build.generatedBuildDir

plugins {
    id("karasu.android.library")
    kotlin("multiplatform")
    alias(libs.plugins.moko)
}

kotlin {
    androidTarget()
//    iosX64()
//    iosArm64()
//    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            dependencies {
                api(libs.moko.resources)
                api(libs.moko.resources.compose)
            }
        }
        androidMain {
        }
//        iosMain {
//        }
    }
}

val generatedAndroidResourceDir = generatedBuildDir.resolve("android/res")

android {
    namespace = "karasu.i18n"

    sourceSets {
        val main by getting
        main.res.srcDirs(
            "src/commonMain/resources",
            generatedAndroidResourceDir,
        )
    }
}

multiplatformResources {
    resourcesPackage.set("karasu.i18n")
}

tasks {
   val localesConfigTask = project.registerLocalesConfigTask(generatedAndroidResourceDir)
   preBuild {
       dependsOn(localesConfigTask)
   }

    withType<KotlinCompile> {
        compilerOptions.freeCompilerArgs.addAll(
            "-Xexpect-actual-classes",
        )
    }
}
