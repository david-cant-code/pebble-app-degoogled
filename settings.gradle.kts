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
include(":cactus")
include(":cactus-native")
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
