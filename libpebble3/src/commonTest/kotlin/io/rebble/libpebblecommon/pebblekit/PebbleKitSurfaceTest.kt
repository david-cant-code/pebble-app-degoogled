package io.rebble.libpebblecommon.pebblekit

import com.russhwolf.settings.MapSettings
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigHolder
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppInstance
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppRoot
import io.rebble.libpebblecommon.metadata.pbw.appinfo.CompanionApp
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Fork: pins the surface routing rule, the shipped defaults (a config saved before the toggles
 * existed included), the session gate the PebbleKit toggles hang on, the toggle flow each entry
 * point follows, and that a PebbleKit 2 watchapp never gets a classic session.
 */
class PebbleKitSurfaceTest {

    @Test
    fun aWatchappWithNoCompanionDeclarationIsClassic() {
        assertEquals(PebbleKitSurface.Classic, appInfo(companion = null).pebbleKitSurface())
    }

    @Test
    fun aCompanionDeclarationWithoutAPackageIsClassic() {
        val companion = CompanionApp(android = AndroidCompanionAppRoot(url = "https://example.invalid/app"))
        assertEquals(PebbleKitSurface.Classic, appInfo(companion).pebbleKitSurface())
        val nullPackage = CompanionApp(
            android = AndroidCompanionAppRoot(apps = listOf(AndroidCompanionAppInstance(pkg = null))),
        )
        assertEquals(PebbleKitSurface.Classic, appInfo(nullPackage).pebbleKitSurface())
    }

    @Test
    fun aDeclaredCompanionPackageIsPebbleKit2() {
        assertEquals(PebbleKitSurface.PebbleKit2, appInfo(declaring("com.example.companion")).pebbleKitSurface())
        assertEquals(PebbleKitSurface.PebbleKit2, appInfo(declaring("com.example.one", "com.example.two")).pebbleKitSurface())
        // Any declared package routes, whichever entry carries it.
        val mixed = CompanionApp(
            android = AndroidCompanionAppRoot(
                apps = listOf(AndroidCompanionAppInstance(pkg = null), AndroidCompanionAppInstance(pkg = "com.example.companion")),
            ),
        )
        assertEquals(PebbleKitSurface.PebbleKit2, appInfo(mixed).pebbleKitSurface())
    }

    @Test
    fun theToggleFlowFollowsEachSurfacesOwnToggle() = runTest {
        val config = MutableStateFlow(LibPebbleConfig(watchConfig = WatchConfig(classicPebbleKitEnabled = true, pebbleKit2Enabled = false)))
        val flow = WatchConfigFlow(config)
        assertTrue(flow.pebbleKitSurfaceEnabled(PebbleKitSurface.Classic).first())
        assertFalse(flow.pebbleKitSurfaceEnabled(PebbleKitSurface.PebbleKit2).first())
        config.value = LibPebbleConfig(watchConfig = WatchConfig(classicPebbleKitEnabled = false, pebbleKit2Enabled = true))
        assertFalse(flow.pebbleKitSurfaceEnabled(PebbleKitSurface.Classic).first())
        assertTrue(flow.pebbleKitSurfaceEnabled(PebbleKitSurface.PebbleKit2).first())
    }

    @Test
    fun aConfigSavedBeforeTheTogglesExistedLoadsWithClassicOff() {
        // What an install upgraded from a version without the toggles has stored; the key and the
        // Json settings match LibPebbleConfigHolder's and the Koin binding's.
        val settings = MapSettings("libpebble.settings" to """{"watchConfig":{"calendarPins":false}}""")
        val loaded = LibPebbleConfigHolder(LibPebbleConfig(), settings, Json { ignoreUnknownKeys = true }).config.value
        assertFalse(loaded.watchConfig.calendarPins, "the saved config was not loaded")
        assertFalse(loaded.watchConfig.classicPebbleKitEnabled)
        assertTrue(loaded.watchConfig.pebbleKit2Enabled)
    }

    @Test
    fun defaultsShipClassicOffAndPebbleKit2On() {
        val defaults = WatchConfig()
        assertFalse(defaults.classicPebbleKitEnabled)
        assertTrue(defaults.pebbleKit2Enabled)
        assertFalse(defaults.isPebbleKitSurfaceEnabled(PebbleKitSurface.Classic))
        assertTrue(defaults.isPebbleKitSurfaceEnabled(PebbleKitSurface.PebbleKit2))
    }

    @Test
    fun aClassicSessionNeedsTheClassicToggle() {
        val classicApp = appInfo(companion = null)
        assertFalse(WatchConfig(classicPebbleKitEnabled = false).allowsPlatformCompanionSession(classicApp, pkjsRunning = false))
        // A JS-only watchface with classic off gets no classic session either.
        assertFalse(WatchConfig(classicPebbleKitEnabled = false).allowsPlatformCompanionSession(classicApp, pkjsRunning = true))
        assertTrue(WatchConfig(classicPebbleKitEnabled = true).allowsPlatformCompanionSession(classicApp, pkjsRunning = false))
        // A JS-only watchface gets a classic session under upstream's default too.
        assertTrue(WatchConfig(classicPebbleKitEnabled = true).allowsPlatformCompanionSession(classicApp, pkjsRunning = true))
    }

    @Test
    fun aPebbleKit2AppNeverFallsBackToAClassicSession() {
        val pk2App = appInfo(declaring("com.example.companion"))
        val pk2Off = WatchConfig(classicPebbleKitEnabled = true, pebbleKit2Enabled = false)
        assertFalse(pk2Off.allowsPlatformCompanionSession(pk2App, pkjsRunning = false))
        assertFalse(pk2Off.allowsPlatformCompanionSession(pk2App, pkjsRunning = true))
        val pk2On = WatchConfig(classicPebbleKitEnabled = false, pebbleKit2Enabled = true)
        assertTrue(pk2On.allowsPlatformCompanionSession(pk2App, pkjsRunning = false))
    }

    @Test
    fun upstreamsMultipleCompanionsRuleStillApplies() {
        val config = WatchConfig(
            classicPebbleKitEnabled = true,
            pebbleKit2Enabled = true,
            appMessageToMultipleCompanions = false,
        )
        assertFalse(config.allowsPlatformCompanionSession(appInfo(companion = null), pkjsRunning = true))
        assertTrue(config.allowsPlatformCompanionSession(appInfo(companion = null), pkjsRunning = false))
    }

    private fun declaring(vararg packages: String) = CompanionApp(
        android = AndroidCompanionAppRoot(apps = packages.map { AndroidCompanionAppInstance(pkg = it) }),
    )

    private fun appInfo(companion: CompanionApp?) = PbwAppInfo(
        uuid = "864369ab-1f37-4a2e-9243-dd6b21af9c14",
        shortName = "test",
        versionLabel = "1.0",
        resources = Resources(),
        companionApp = companion,
    )
}
