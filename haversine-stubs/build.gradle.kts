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
// Wiring differs from :firebase-stubs on purpose. That module is named on
// upstream dependency lines the fork already edits; here the only consumer
// is libindex/build.gradle.kts, an upstream file the fork has never touched,
// so settings.gradle.kts substitutes the maven coordinate for this project
// instead. libindex keeps its upstream dependency line byte-for-byte, and
// a future upstream version bump of the artifact still lands on the stub.
// The AAR's absence from the shipped classpath is pinned by
// AppClasspathSentinelTest in :androidApp.
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

        // The stub-behavior tests live in commonTest; without this the
        // plugin would silently skip them for the android compilation.
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
                implementation(libs.coroutines.test)
            }
        }
    }
}
