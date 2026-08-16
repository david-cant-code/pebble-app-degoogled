import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    // Fork: no jvmToolchain pin. F-Droid's buildserver ships a single JDK (21)
    // with Gradle toolchain provisioning disabled, so the build has to run on
    // whichever JDK 17+ launches Gradle. The bytecode target stays 17 through
    // the explicit jvmTarget on each JVM-flavoured target below.
    android {
        namespace = "coredevices.blobannotations"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.valueOf("JVM_${libs.versions.jvm.toolchain.get()}"))
        }
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.valueOf("JVM_${libs.versions.jvm.toolchain.get()}"))
        }
    }

    val xcfName = "libpebble-annotations"

    iosArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }

    iosSimulatorArm64 {
        binaries.framework {
            baseName = xcfName
        }
    }
}
