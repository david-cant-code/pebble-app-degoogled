import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure-Kotlin Index 01 library. Selected with -Pgravel.ring=open; the
// default substitution stays on :haversine-stubs. No native code, no Ktor.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    targets.configureEach {
        compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions {
                    freeCompilerArgs.add("-Xexpect-actual-classes")
                }
            }
        }
    }
    android {
        namespace = "coredevices.haversineopen"
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
        all {
            languageSettings {
                optIn("kotlin.time.ExperimentalTime")
                optIn("kotlin.uuid.ExperimentalUuidApi")
            }
        }
        commonMain {
            dependencies {
                api(libs.coroutines)
                implementation(libs.kable)
                implementation(libs.kermit)
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
