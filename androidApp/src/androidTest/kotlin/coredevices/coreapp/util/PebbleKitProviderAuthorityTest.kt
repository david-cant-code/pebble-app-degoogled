package coredevices.coreapp.util

import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins both exported PebbleKit ContentProvider authorities to this package.
 * PebbleKit 2 clients derive the authority from the companion's package name
 * (packageName + ".pebblekit"); classic PebbleKit clients use the fixed basalt
 * authority. Either authority string is build-valid, so a merge that reverts
 * the manifest to another value would compile fine and only fail at runtime
 * when a client app cannot find the provider.
 */
class PebbleKitProviderAuthorityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun pebbleKit2AuthorityDerivesFromThisPackage() {
        val authority = "${context.packageName}.pebblekit"
        val provider = resolveProvider(authority)
        assertNotNull(provider, "no exported provider at $authority")
        assertEquals(context.packageName, provider.packageName)
    }

    @Test
    fun classicBasaltAuthorityResolvesToThisPackage() {
        val authority = "com.getpebble.android.provider.basalt"
        val provider = resolveProvider(authority)
        assertNotNull(provider, "no exported provider at $authority")
        assertEquals(context.packageName, provider.packageName)
    }

    private fun resolveProvider(authority: String): ProviderInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveContentProvider(
                authority,
                PackageManager.ComponentInfoFlags.of(0),
            )
        } else {
            // The int overload is deprecated from API 33; minSdk is below that.
            @Suppress("DEPRECATION")
            context.packageManager.resolveContentProvider(authority, 0)
        }
}
