import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Gravel module: Kotlin bindings for the llama.cpp text engine behind the
// Notebook (commonMain: the pure decisions of both sides of the process
// boundary; androidMain: the Binder protocol, service and client). Android
// only, like the Notebook.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    android {
        namespace = "com.anopticlabs.gravel.llm"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        withHostTestBuilder {}
    }

    sourceSets {
        androidMain.dependencies {
            // Re-exported so the app packages the JNI libraries, as :whisper does.
            api(project(":llm-native"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
