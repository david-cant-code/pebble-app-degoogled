import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fork module: Kotlin bindings for the whisper.cpp speech engine. The
// expect/actual split exists because the single runtime consumer
// (WhisperTranscriptionService in :util) is commonMain and must keep
// compiling for the unmaintained iOS targets; the iOS actuals are honest
// "unsupported" stubs, never implementations.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    android {
        namespace = "coredevices.whisper"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        withHostTestBuilder {}
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        androidMain.dependencies {
            // Re-exported so the app packages the JNI libraries whenever
            // these bindings are on the classpath, mirroring how the
            // engine module pair has always been wired in this project.
            api(project(":whisper-native"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
