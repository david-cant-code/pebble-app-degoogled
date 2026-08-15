import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Fork module: inert stand-ins for the io.github.coredevices.haversine
// artifact, the Ring satellite library. The real artifact is a prebuilt AAR
// with no public source that ships two native libraries per ABI into the
// APK, which is what stood between the fork and F-Droid's inclusion policy
// once the speech stack was rebuilt from source. The Ring runtime the
// library serves is dead in this fork (NoOpLibIndex at the Koin seam), so
// nothing observable changes: :libindex only needs the seven symbols below
// to compile.
//
// Wired by the dependencySubstitution rule in settings.gradle.kts, not by
// a dependency line: nothing names this project directly, so a lost rule
// brings the AAR back silently, which AppClasspathSentinelTest in
// :androidApp fails on. The seam and its rationale are in DESIGN_NOTES.md
// (Ring / Index AI).
//
// Module shape mirrors :krisp-stubs and :firebase-stubs (KMP Android library
// plugin, AGP 9). The iOS targets exist only because libindex declares them
// and KMP resolves a commonMain dependency for every target of the consumer;
// the iOS app itself is unmaintained in this fork.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
}

kotlin {
    android {
        namespace = "coredevices.haversinestubs"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        // The stub-behavior tests live in commonTest and the reflection-based
        // shape test in androidHostTest; without this the plugin would
        // silently skip both for the android compilation.
        withHostTestBuilder {}
    }

    iosArm64()

    iosSimulatorArm64()

    sourceSets {
        commonMain {
            dependencies {
                // The stubbed surface exposes StateFlow-typed properties, as
                // the real library's does, so the same runtime dep applies.
                api(libs.coroutines)
            }
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}
