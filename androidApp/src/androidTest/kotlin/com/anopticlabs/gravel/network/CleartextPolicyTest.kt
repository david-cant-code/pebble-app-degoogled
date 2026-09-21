package com.anopticlabs.gravel.network

import android.security.NetworkSecurityPolicy
import org.junit.Test
import kotlin.test.assertFalse

/**
 * Asks the platform what the installed app's network security config resolves to. The
 * source-level pin is NetworkSecurityConfigTest; this is the device-level one, and the only
 * one that sees platform-added configs such as the implicit localhost config the config
 * file's comment describes.
 */
class CleartextPolicyTest {
    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun cleartextIsDeniedAppWide() {
        assertFalse(policy.isCleartextTrafficPermitted)
    }

    @Test
    fun cleartextIsDeniedForLoopbackHosts() {
        listOf("localhost", "localhost.", "ip6-localhost", "127.0.0.1", "127.0.0.5", "::1", "[::1]", "::ffff:127.0.0.1").forEach { host ->
            assertFalse(policy.isCleartextTrafficPermitted(host), "cleartext permitted for $host")
        }
    }

    @Test
    fun cleartextIsDeniedForPrivateAndLinkLocalHosts() {
        listOf("10.0.0.1", "172.16.0.1", "192.168.1.1", "169.254.1.1", "fd00::1", "fe80::1").forEach { host ->
            assertFalse(policy.isCleartextTrafficPermitted(host), "cleartext permitted for $host")
        }
    }
}
