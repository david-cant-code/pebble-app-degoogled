// Gravel's process-wide filter that refuses the creation of UDP sockets, built from the C
// source in src/main/cpp. A plain Android library for the reason :whisper-native's build file
// gives; the Kotlin API is Android-only and lives in the same module.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.anopticlabs.gravel.socketfilter"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    // Same NDK and CMake pins as :whisper-native, for the reason given there: the F-Droid
    // recipe provisions exactly these (DESIGN_NOTES.md, F-Droid section).
    // FdroidGuardrailsTest checks that the two modules agree.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            // The app's own ABI set. socket_filter.c stops the build with an #error for an
            // ABI it has no syscall constants for.
            abiFilters += setOf("armeabi-v7a", "arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlin.test)
    androidTestImplementation(libs.kotlin.test.junit)
}
