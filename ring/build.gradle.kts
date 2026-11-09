import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Gravel module: the client for the Pebble Index 01 ring (commonMain: the
// protocol's byte layouts, the audio decoder and the sync session;
// androidMain: the Bluetooth link). Android only, like the Notebook.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    android {
        namespace = "com.anopticlabs.gravel.ring"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.coroutines)
            implementation(libs.kermit)
        }
        androidMain.dependencies {
            implementation(libs.kable)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.coroutines.test)
        }
    }
}
