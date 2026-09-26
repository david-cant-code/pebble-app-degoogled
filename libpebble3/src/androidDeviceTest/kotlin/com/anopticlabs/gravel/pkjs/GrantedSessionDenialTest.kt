package com.anopticlabs.gravel.pkjs

import androidx.test.filters.MediumTest
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.database.dao.FakeLockerAppPermissionDao
import io.rebble.libpebblecommon.database.dao.LockerAppPermissionDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermission
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.js.PKJSRunnerTests
import io.rebble.libpebblecommon.js.WebViewJsRunner
import io.rebble.libpebblecommon.js.createJsRunner
import io.rebble.libpebblecommon.js.stopWithinTimeout
import io.rebble.libpebblecommon.locker.PermissionSetting
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

/** A session built with the Network grant allowed, whose grant is then denied. */
@MediumTest
class GrantedSessionDenialTest : PKJSRunnerTests(::createJsRunner) {

    private fun resolver(dao: LockerAppPermissionDao = FakeLockerAppPermissionDao()) =
        WatchappPermissionResolver(dao, LibPebbleConfigFlow(MutableStateFlow(LibPebbleConfig())))

    // A ready granted session whose localStorage restore has completed, holding one value set by
    // property assignment, which bypasses the setItem write-through, so only a save keeps it.
    private suspend fun grantedSessionWithUnsavedValue(uuid: Uuid, permissions: WatchappPermissionResolver): WebViewJsRunner {
        permissions.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        val runner = makeRunner("", uuid, watchappPermissions = permissions) as WebViewJsRunner
        runner.start()
        withTimeout(5.seconds) { runner.readyState.first { it } }
        withTimeout(5.seconds) {
            while (runner.evalWithResult("window.__localStorageShimmed === true;") != "true") delay(20)
        }
        runner.eval("localStorage.propKey = 'propValue';")
        assertEquals("\"propValue\"", runner.evalWithResult("localStorage.propKey;"))
        return runner
    }

    // Blocks the page's main thread, so a script evaluated after it, such as the save, waits.
    private suspend fun WebViewJsRunner.keepPageBusy(duration: Duration) =
        eval("(function () { var end = Date.now() + ${duration.inWholeMilliseconds}; while (Date.now() < end) {} })();")

    private suspend fun WebViewJsRunner.awaitEnded(within: Duration = 10.seconds) {
        withTimeout(within) { while (webViewSettingsForTest() != null) delay(20) }
        assertFalse(readyState.value)
    }

    private suspend fun assertNextSessionReads(uuid: Uuid, permissions: WatchappPermissionResolver, expected: String) {
        val next = makeRunner("", uuid, watchappPermissions = permissions) as WebViewJsRunner
        try {
            next.start()
            withTimeout(10.seconds) { next.readyState.first { it } }
            assertEquals(expected, next.evalWithResult("localStorage.getItem('propKey');"))
        } finally {
            next.stopWithinTimeout()
        }
    }

    @Test
    fun denyingTheGrantEndsTheSessionAndKeepsItsLocalStorage() = runBlocking {
        val uuid = Uuid.random()
        val permissions = resolver()
        val runner = grantedSessionWithUnsavedValue(uuid, permissions)
        try {
            permissions.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
            runner.awaitEnded()
        } finally {
            runner.stopWithinTimeout()
        }
        assertNextSessionReads(uuid, permissions, "\"propValue\"")
    }

    // The grant flipped back to denied after start() read it as allowed.
    @Test
    fun aSessionBuiltAsGrantedWhoseGrantIsAlreadyDeniedEnds() = runBlocking {
        val uuid = Uuid.random()
        val stored = FakeLockerAppPermissionDao()
        stored.insertOrReplace(LockerAppPermission(uuid, LockerAppPermissionType.Network, granted = false))
        val grantedAtStart = object : LockerAppPermissionDao by stored {
            override suspend fun getByAppUuidAndPermission(appUuid: Uuid, permission: LockerAppPermissionType) =
                LockerAppPermission(appUuid, permission, granted = true)
        }
        val runner = makeRunner("", uuid, watchappPermissions = resolver(grantedAtStart)) as WebViewJsRunner
        try {
            runner.start()
            assertFalse(runner.denyConstruction)
            runner.awaitEnded()
        } finally {
            runner.stopWithinTimeout()
        }
    }

    // A busy page holds the end inside its save while stop() arrives, as the lifecycle's restart
    // does. A second save would leave the next session nothing to read.
    @Test
    fun aStopDuringTheEndsSaveWaitsForItAndSavesOnce() = runBlocking {
        val uuid = Uuid.random()
        val permissions = resolver()
        val runner = grantedSessionWithUnsavedValue(uuid, permissions)
        try {
            runner.keepPageBusy(2.seconds)
            permissions.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
            withTimeout(5.seconds) { runner.readyState.first { !it } }
            assertNotNull(runner.webViewSettingsForTest(), "the session ended before the busy page let it save")
            runner.signalReady()
            assertFalse(runner.readyState.value, "marked ready while ending")
            assertNotNull(runner.webViewSettingsForTest(), "the session ended before stop() arrived")
        } finally {
            runner.stopWithinTimeout()
        }
        assertNextSessionReads(uuid, permissions, "\"propValue\"")
    }

    @Test
    fun aBusyPageDoesNotHoldOffTheEnd() = runBlocking {
        val uuid = Uuid.random()
        val permissions = resolver()
        val runner = grantedSessionWithUnsavedValue(uuid, permissions)
        val busy = 8.seconds
        val pageFree = TimeSource.Monotonic.markNow() + busy
        try {
            runner.keepPageBusy(busy)
            permissions.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Deny)
            runner.awaitEnded(within = 6.seconds)
        } finally {
            runner.stopWithinTimeout()
            // Waits out the busy script before the next test starts a page.
            delay(-pageFree.elapsedNow() + 1.seconds)
        }
    }
}
