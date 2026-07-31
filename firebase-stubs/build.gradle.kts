import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fork module: inert stand-ins for the dev.gitlive Firebase KMP artifacts
// (firebase-auth, firebase-firestore). It declares the same fully-qualified
// types the upstream call sites import, so those files compile unchanged
// while the real Firebase/GMS SDKs stay out of the dependency graph
// entirely. Re-adding a real gitlive artifact alongside this module trips
// duplicate-class errors, which is the intended tripwire (same pattern as
// the ring fork stubs). Module shape mirrors :krisp-stubs.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

android {
    namespace = "coredevices.firebasestubs"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    androidTarget {
        publishLibraryVariants("release", "debug")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // The jvm and iOS targets exist only so consumers can keep this
    // dependency in commonMain (KMP resolves commonMain deps for every
    // target; index-ai adds a jvm target on top of android + iOS). The iOS
    // app itself is unmaintained in this fork and still uses the real
    // Firebase pods.
    jvm()

    iosX64 {
    }

    iosArm64 {
    }

    iosSimulatorArm64 {
    }

    sourceSets {
        commonMain {
            dependencies {
                // Flow-returning auth/firestore surfaces and the
                // serialization-strategy overloads mirror gitlive's own
                // public signatures, so the same two runtime deps apply.
                api(libs.coroutines)
                api(libs.serialization)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.coroutines.test)
            }
        }
    }
}
