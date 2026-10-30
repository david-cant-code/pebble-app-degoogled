// Gravel module: the NDK/CMake build of the llama.cpp text engine and its
// JNI shim, consumed by :llm; a plain Android library for the same reason
// as :whisper-native. The engine is the git submodule at
// src/main/cpp/llama.cpp, pinned to an upstream release tag.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.anopticlabs.gravel.llm.nativelib"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    // Both pins must match :whisper-native and :socketfilter (FdroidGuardrailsTest).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            // arm64 only, like the speech engine.
            abiFilters += "arm64-v8a"
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
