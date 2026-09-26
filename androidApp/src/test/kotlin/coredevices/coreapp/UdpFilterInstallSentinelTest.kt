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

    // UdpFilterInstalledTest checks on a device that the record is this process start's.
    @Test
    fun onCreateRecordsTheInstall() {
        val body = assertNotNull(
            Regex("""override fun onCreate\(\) \{\n(.*?)\n    \}""", RegexOption.DOT_MATCHES_ALL).find(source)?.groupValues?.get(1),
            "MainApplication no longer overrides onCreate",
        )
        assertTrue(
            Regex("""(?m)^ {8}recordUdpFilterInstall\(\s*udpFilterResult\s*,\s*noBackupFilesDir\s*\)\s*$""").containsMatchIn(body),
            "MainApplication.onCreate no longer records the filter's install",
        )
    }

    // All three files are upstream-owned. watchModule and LibPebble3.create fall back silently to
    // UnreportedNetworkDenyEnforcement: no PebbleKit JS for any Network-denied app.
    @Test
    fun theInstallResultReachesLibPebble() {
        val appModule = TrackedTree.file("composeApp/src/androidMain/kotlin/coredevices/coreapp/di/androidDefaultModule.kt").readText()
        assertTrue(
            Regex(
                """single<NetworkDenyEnforcement>\s*\{\s*udpFilterEnforcement\(\s*UdpSocketFilter\s*\.\s*lastResult\s*,\s*androidContext\(\)\s*\.\s*noBackupFilesDir\s*,\s*::\s*webViewMajorVersion\s*,?\s*\)\s*\}""",
            ).containsMatchIn(appModule),
            "androidDefaultModule no longer binds NetworkDenyEnforcement from the filter's install result",
        )
        val watchModule = TrackedTree.file("pebble/src/commonMain/kotlin/coredevices/pebble/watchModule.kt").readText()
        assertTrue(
            Regex("""networkDenyEnforcement\s*=\s*getOrNull\(\)\s*\?:\s*UnreportedNetworkDenyEnforcement\s*,""").containsMatchIn(watchModule),
            "watchModule no longer hands the app's NetworkDenyEnforcement, or the fail-closed fallback, to LibPebble3",
        )
        val libPebble = TrackedTree.file("libpebble3/src/commonMain/kotlin/io/rebble/libpebblecommon/connection/LibPebble.kt").readText()
        assertTrue(
            Regex("""networkDenyEnforcement:\s*NetworkDenyEnforcement\s*=\s*UnreportedNetworkDenyEnforcement\s*,""").containsMatchIn(libPebble),
            "LibPebble3.create no longer defaults to the fail-closed enforcement",
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

    // The probe stands in for the process's seccomp calls only while it makes every form of them,
    // and every call but the install's must attach to the calling thread alone (flags 0, no TSYNC).
    @Test
    fun theProbeCoversEveryFilterCallAndOnlyTheInstallSyncsThreads() {
        val filterSource = TrackedTree.file("socketfilter/src/main/cpp/socket_filter.c").readText()
        val callFlags = Regex("""(?<!static long )\bset_filter\(([^)]*)\)""")
        fun bodyOf(function: String): String = assertNotNull(
            Regex("""\b$function\([^)]*\) \{\n(.*?)\n\}""", RegexOption.DOT_MATCHES_ALL).find(filterSource)?.groupValues?.get(1),
            "socket_filter.c no longer defines $function",
        )
        fun flagsIn(text: String) = callFlags.findAll(text).map { it.groupValues[1].trim() }.toList()
        val probe = bodyOf("probe")
        val install = bodyOf("set_filter_synced")
        val check = flagsIn(bodyOf("check_resolver_on_this_thread"))
        assertEquals(listOf("0"), check, "the resolver check's set_filter flags")
        assertEquals((flagsIn(install) + check).toSet(), flagsIn(probe).toSet(), "the probe's flags against the install's and the check's")
        val elsewhere = flagsIn(filterSource.replace(probe, "").replace(install, ""))
        assertTrue(elsewhere.isNotEmpty() && elsewhere.all { it == "0" }, "set_filter calls outside the probe and the install: $elsewhere")
    }
}
