// The KMP Android library plugin has no NDK support, so the CMake build of
// the whisper.cpp engine and its JNI shim live in this plain Android
// library, consumed by :whisper (the same split :cactus-native used for the
// engine this fork replaced). Unlike that predecessor there is no prebuilt
// engine binary here: the engine is the git submodule at
// src/main/cpp/whisper.cpp, pinned in the superproject index and compiled
// from source by src/main/cpp/CMakeLists.txt.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "coredevices.whisper.nativelib"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    // Pinned toolchain for the from-source engine build. Both values must
    // match what the F-Droid build recipe provisions (its ndk field and the
    // cmake package it installs; the recipe contract is in DESIGN_NOTES.md,
    // F-Droid section), because that buildserver has neither by default and
    // a mismatch is a hard configuration failure there; a pin also keeps
    // the compiled engine identical across machines. The NDK is the version
    // AGP resolves on its own for this AGP line, so nothing changes for
    // local builds. The CMake pin sits with the CMake block below.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk {
            // arm64 only, matching the app's real device population and the
            // armv8.2 feature floor the CMake build compiles against. The
            // Kotlin side gates every engine touch on isWhisperSupported(),
            // which reports false where these libraries are absent.
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            // The SDK's cmake package that satisfies the floor the fork's own
            // wrapper CMakeLists.txt above declares (cmake_minimum_required
            // 3.22); the engine submodule itself only asks for 3.5. Pinned
            // for the same reason as ndkVersion.
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
