package coredevices.pebble.ui

import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.PermissionSetting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Pins the network gate on the developer config page. The page URL is built by the
 * app's own PKJS and loaded in a WebView, so opening it is an app-controlled network
 * request to a developer-chosen server; a network-denied app must be refused (with the
 * pointer snackbar) and a granted app must get past the gate. showSettings lives in
 * upstream-touched code, so an upstream merge dropping the fork-added guard is the
 * regression this exists to catch.
 */
class WatchappConfigPageGateTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")

    private fun installedApp() = CommonApp(
        title = "Test App",
        developerName = "Test Developer",
        uuid = uuid,
        androidCompanion = null,
        commonAppType = CommonAppType.Locker(
            sideloaded = false,
            configurable = true,
            sync = true,
            order = 0,
        ),
        type = AppType.Watchapp,
        category = null,
        version = null,
        listImageUrl = null,
        screenshotImageUrl = null,
        isCompatible = true,
        isNativelyCompatible = true,
        hearts = null,
        description = null,
        developerId = null,
        categorySlug = null,
        storeId = null,
        sourceLink = null,
        appstoreSource = null,
        capabilities = emptyList(),
    )

    private fun topBarParams(snackbars: MutableList<String>) = TopBarParams(
        searchAvailable = {},
        actions = {},
        title = {},
        overrideGoBack = MutableStateFlow(Unit),
        showSnackbar = { snackbars += it },
        scrollToTop = MutableStateFlow(Unit),
    )

    @Test
    fun configPageRefusedWhenNetworkDenied() = runTest {
        val libPebble = FakeLibPebble()
        // No explicit grant: resolves through the deny-by-default baseline.
        libPebble.watches.value = emptyList()
        val snackbars = mutableListOf<String>()

        installedApp().showSettings(NoOpNavBarNav, libPebble, topBarParams(snackbars))

        assertEquals(1, snackbars.size, "a denied app must be refused with an explanation")
        assertContains(snackbars.single(), "Watch App Permissions")
    }

    @Test
    fun configPageProceedsPastGateWhenNetworkAllowed() = runTest {
        val libPebble = FakeLibPebble()
        // Empty watch list makes the flow stop right after the gate (no connected
        // watch), which keeps the test on the gate itself: passing it silently is
        // exactly the granted behaviour, refusing it would surface the snackbar.
        libPebble.watches.value = emptyList()
        libPebble.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        val snackbars = mutableListOf<String>()

        installedApp().showSettings(NoOpNavBarNav, libPebble, topBarParams(snackbars))

        assertTrue(snackbars.isEmpty(), "a granted app must get past the permission gate")
    }
}
