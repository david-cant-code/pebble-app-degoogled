package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Source sentinel for the UDP socket filter's install call in `MainApplication`, an upstream-owned
 * file a sync merge could resolve to upstream's text. It matches text only: a commented-out copy
 * of the call would satisfy it, and UdpFilterInstalledTest is the run-time check.
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
}
