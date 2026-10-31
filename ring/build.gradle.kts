import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Gravel module: the client for the Pebble Index 01 ring (commonMain: the
// protocol's byte layouts and the audio decoder). Android only, like the
// Notebook.
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
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
