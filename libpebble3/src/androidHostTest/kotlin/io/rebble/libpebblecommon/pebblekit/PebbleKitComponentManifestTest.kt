package io.rebble.libpebblecommon.pebblekit

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Fork: pins that every component name [PebbleKitComponentState] hands the system is declared in
 * a library manifest; the names are literals, so a renamed class would otherwise leave one
 * pointing at nothing, and its component at the manifest default.
 */
class PebbleKitComponentManifestTest {

    private val manifests = listOf(
        File("src/androidMain/AndroidManifest.xml"),
        File("../pebble/src/androidMain/AndroidManifest.xml"),
    )

    @Test
    fun everyManagedComponentIsDeclaredInAManifest() {
        val declared = manifests.flatMap { manifest ->
            assertTrue(manifest.isFile, "manifest not found at ${manifest.absolutePath}")
            Regex("""android:name="([^"]+)"""").findAll(manifest.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
        val managed = PebbleKitComponentState.desiredStates(PebbleKitToggles(classic = true, pebbleKit2 = true)).keys
        assertTrue(managed.isNotEmpty(), "no components are managed")
        managed.forEach { className ->
            assertTrue(
                className in declared,
                "$className is toggled by PebbleKitComponentState but declared in no library manifest",
            )
        }
    }
}
