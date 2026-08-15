import java.util.Properties

val properties = Properties()
if (file("local.properties").exists()) {
    file("local.properties").inputStream().use { properties.load(it) }
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://jitpack.io") }
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven(url = "https://jitpack.io")
    }
}

rootProject.name = "libpebbleroot"

include(":libpebble3")
include(":blobdbgen")
include(":blobannotations")
include(":composeApp")
include(":androidApp")
include(":pebble")
include(":util")
include(":mcp")
include(":index-ai")
include(":resampler")
// Fork modules: the whisper.cpp speech engine, built from source (KMP
// bindings plus the plain Android library that owns the NDK/CMake build
// and the pinned engine submodule).
include(":whisper")
include(":whisper-native")
include(":libindex")
// :experimental (the Ring/Index feature module) is unplugged from the fork's
// build: it carries firebase-auth/firestore/storage, the googleServices
// plugin, and Supabase. :libindex/:index-ai/:mcp stay included because the
// watch UI in :pebble compiles against :libindex, whose Room schema is
// entangled with :index-ai (which needs :mcp); their runtime is disabled at
// the Koin seam instead (no-op LibIndex, ring stubs in :composeApp).
// include(":experimental")
include(":krisp-stubs")
// Fork module: inert dev.gitlive.firebase.* stand-ins replacing the real
// gitlive firebase-auth/firebase-firestore artifacts (see the Firebase
// strip). Keeps upstream call sites compiling with no Firebase/GMS SDKs
// in the graph.
include(":firebase-stubs")
// Fork module: inert stand-ins for the io.github.coredevices.haversine Ring
// satellite library (a prebuilt AAR with bundled native libraries and no
// public source, incompatible with the F-Droid target). Wired by the
// dependency substitution rule below; the seam is described in
// DESIGN_NOTES.md (Ring / Index AI).
include(":haversine-stubs")

// Every project resolves the substitution, not just :libindex: the artifact
// is a transitive runtime dependency of everything that depends on libindex
// (:pebble, :composeApp, :androidApp), and a substitution rule only applies
// to the configurations of the project that declares it, so applying it in
// libindex alone would keep the real AAR on the app's runtime classpath.
// The coordinate is matched by group:name only, so an upstream version bump
// in libs.versions.toml still lands on the stub.
gradle.lifecycle.beforeProject {
    configurations.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.coredevices.haversine:haversine"))
                .using(project(":haversine-stubs"))
                .because("Gravel replaces the prebuilt Ring satellite AAR with :haversine-stubs")
        }
    }
}
