package com.anopticlabs.gravel.music

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import kotlin.test.assertTrue

/**
 * The album art check refuses URIs served by the app's own providers; this checks that the set
 * includes the four authorities the merged manifest declares today, with both PebbleKit providers
 * disabled.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.music.OwnProviderAuthoritiesTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class OwnProviderAuthoritiesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val pm = context.packageManager

    @Test
    fun includesEveryOwnProviderEvenWhenDisabled() {
        val providers = listOf(
            "io.rebble.libpebblecommon.pebblekit.classic.PebbleKitProvider",
            "io.rebble.libpebblecommon.pebblekit.two.PebbleKitProvider",
        ).map { ComponentName(context.packageName, it) }
        val before = providers.associateWith { pm.getComponentEnabledSetting(it) }
        try {
            providers.forEach {
                pm.setComponentEnabledSetting(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            val authorities = ownProviderAuthorities(context)
            listOf(
                "${context.packageName}.fileprovider",
                "${context.packageName}.androidx-startup",
                "${context.packageName}.pebblekit",
                "com.getpebble.android.provider.basalt",
            ).forEach { assertTrue(it in authorities, "$it missing from $authorities") }
        } finally {
            before.forEach { (component, state) ->
                pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
            }
        }
    }
}
