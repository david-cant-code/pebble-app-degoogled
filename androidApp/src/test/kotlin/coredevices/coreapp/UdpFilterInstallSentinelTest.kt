package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source sentinels for the UDP socket filter. The install call is in `MainApplication`, an
 * upstream-owned file a sync merge could resolve to upstream's text; that check matches text only,
 * and UdpFilterInstalledTest is the run-time check.
 */
class UdpFilterInstallSentinelTest {

    private val source by lazy {
        TrackedTree.file("composeApp/src/androidMain/kotlin/coredevices/coreapp/MainApplication.kt").readText()
    }

    @Test
    fun attachBaseContextInstallsTheFilterBeforeAnythingElse() {
        val body = assertNotNull(
            Regex("""override fun attachBaseContext\(base: Context\) \{\n(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL)
                .find(source)?.groupValues?.get(1),
            "MainApplication no longer overrides attachBaseContext",
        )
        val statements = body.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") }
        assertTrue(
            statements == listOf(
                "super.attachBaseContext(base)",
                "if (runningInIsolatedProcess()) return",
                "udpFilterResult = UdpSocketFilter.install()",
            ),
            "attachBaseContext must hold the super call, the isolated-process check and the install, in that order: $statements",
        )
    }

    // Both files are upstream-owned, and both lookups of the binding fall back silently
    // (getOrNull), to no PebbleKit JS for any Network-denied app.
    @Test
    fun theInstallResultReachesLibPebble() {
        val appModule = TrackedTree.file("composeApp/src/androidMain/kotlin/coredevices/coreapp/di/androidDefaultModule.kt").readText()
        assertTrue(
            Regex(
                """single<NetworkDenyEnforcement>\s*\{\s*FixedNetworkDenyEnforcement\(\s*UdpSocketFilter\s*\.\s*lastResult\s*==\s*InstallResult\s*\.\s*Installed\s*\)\s*\}""",
            ).containsMatchIn(appModule),
            "androidDefaultModule no longer binds NetworkDenyEnforcement from the filter's install result",
        )
        val watchModule = TrackedTree.file("pebble/src/commonMain/kotlin/coredevices/pebble/watchModule.kt").readText()
        assertTrue(
            Regex("""networkDenyEnforcement\s*=\s*getOrNull\(\)""").containsMatchIn(watchModule),
            "watchModule no longer hands the app's NetworkDenyEnforcement to LibPebble3",
        )
    }

    // VerifyApkContents reports nothing for an empty list or a misspelled descriptor, so a run
    // of it cannot show that these two inputs still say what they should.
    @Test
    fun theApkCheckNamesTheFilterLibraryAndTheUdpSocketTypes() {
        val build = TrackedTree.file("androidApp/build.gradle.kts").readText()
        val required = assertNotNull(
            Regex("""requiredEntries\.set\(\s*listOf\(([^)]*)\)""").find(build)?.groupValues?.get(1),
            "androidApp/build.gradle.kts no longer sets requiredEntries",
        )
        for (abi in listOf("arm64-v8a", "armeabi-v7a")) {
            assertTrue("\"lib/$abi/libgravelsocketfilter.so\"" in required, "requiredEntries no longer names the $abi library: $required")
        }
        val releaseTypes = assertNotNull(
            Regex("""forbiddenDexTypes\.set\(\s*if \(variant\.buildType == "release"\) \{\s*listOf\(([^)]*)\)""")
                .find(build)?.groupValues?.get(1),
            "androidApp/build.gradle.kts no longer sets forbiddenDexTypes for release",
        )
        val types = listOf("Ljava/net/DatagramSocket;", "Ljava/net/DatagramPacket;", "Ljava/net/MulticastSocket;", "Ljava/nio/channels/DatagramChannel;")
        for (type in types) {
            assertTrue("\"$type\"" in releaseTypes, "the release dex check no longer names $type: $releaseTypes")
        }
    }

    // The probe stands in for the install only while both make the same seccomp call.
    @Test
    fun theProbeAndTheInstallPassTheSameFlags() {
        val filterSource = TrackedTree.file("socketfilter/src/main/cpp/socket_filter.c").readText()
        val flags = Regex("""(?<!static long )\bset_filter\(([^)]*)\)""").findAll(filterSource).map { it.groupValues[1].trim() }.toList()
        assertEquals(2, flags.size, "expected the probe's and the install's set_filter calls: $flags")
        assertEquals(1, flags.distinct().size, "the probe and the install pass different flags: $flags")
    }
}
