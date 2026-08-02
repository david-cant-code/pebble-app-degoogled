package io.rebble.libpebblecommon.pebblekit.classic

import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppInstance
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppRoot
import io.rebble.libpebblecommon.metadata.pbw.appinfo.CompanionApp
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Classic PebbleKit broadcasts watch data with no permission, so narrowing delivery to the
 * watchapp's declared companions is the only thing standing between an app's messages and every
 * receiver on the device. An empty result is load-bearing in the other direction: it means
 * "broadcast as before", so a watchapp predating companion declarations keeps working.
 */
class AndroidCompanionPackagesTest {

    @Test
    fun `returns the declared packages`() {
        val info = appInfo(CompanionApp(android = root("com.example.one", "com.example.two")))

        assertEquals(listOf("com.example.one", "com.example.two"), info.androidCompanionPackages())
    }

    @Test
    fun `is empty when no companion is declared`() {
        assertEquals(emptyList(), appInfo(companion = null).androidCompanionPackages())
    }

    @Test
    fun `is empty when the declaration has no android section`() {
        assertEquals(emptyList(), appInfo(CompanionApp(android = null)).androidCompanionPackages())
    }

    @Test
    fun `is empty when the android section declares no apps`() {
        val info = appInfo(CompanionApp(android = AndroidCompanionAppRoot()))

        assertEquals(emptyList(), info.androidCompanionPackages())
    }

    @Test
    fun `skips entries with no package name`() {
        // appinfo.json can carry a companion entry that only names a store URL.
        val android = AndroidCompanionAppRoot(
            apps = listOf(
                AndroidCompanionAppInstance(pkg = null),
                AndroidCompanionAppInstance(pkg = "com.example.one"),
            )
        )

        assertEquals(listOf("com.example.one"), appInfo(CompanionApp(android)).androidCompanionPackages())
    }

    @Test
    fun `deduplicates repeated packages`() {
        // Otherwise the same companion would receive one copy of every message per entry.
        val info = appInfo(CompanionApp(android = root("com.example.one", "com.example.one")))

        assertEquals(listOf("com.example.one"), info.androidCompanionPackages())
    }

    private fun root(vararg packages: String) =
        AndroidCompanionAppRoot(apps = packages.map { AndroidCompanionAppInstance(pkg = it) })

    private fun appInfo(companion: CompanionApp?) = PbwAppInfo(
        uuid = "00000000-0000-0000-0000-000000000001",
        shortName = "test",
        versionLabel = "1.0",
        resources = Resources(),
        companionApp = companion,
    )
}
