import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Gravel module: the Notebook feature (voice and typed notes, reminders). Pure logic lives in
// commonMain on the Kotlin standard library and kotlinx.serialization; androidMain holds what the
// platform has to supply.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "com.anopticlabs.gravel.notebook"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        withHostTestBuilder {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.serialization)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kermit)
            implementation(libs.coroutines)
        }
        androidMain.dependencies {
            implementation(project(":util"))
            implementation(libs.androidx.core.ktx)
            implementation(libs.koin.core)
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
