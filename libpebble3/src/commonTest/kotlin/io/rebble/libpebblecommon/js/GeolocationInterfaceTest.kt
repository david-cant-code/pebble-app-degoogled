package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.database.dao.FakeLockerAppPermissionDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.locker.PermissionSetting
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import io.rebble.libpebblecommon.util.GeolocationPositionResult
import io.rebble.libpebblecommon.util.SystemGeolocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Proves the location enforcement site actually consults the permission resolver and,
 * for continuous watches, keeps enforcing it: a revocation mid-watch must cancel the
 * running GPS stream (a watchface can hold one for days), a re-grant must restart it,
 * and denial must surface to the app's JS callback as a geolocation error.
 *
 * GeolocationInterface takes its dependencies from the library's isolated Koin
 * context, so each test loads a module with the fakes into that context and unloads
 * it afterwards; no full DI graph is booted.
 */
class GeolocationInterfaceTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")
    private val koin = (object : LibPebbleKoinComponent {}).getKoin()
    private var loadedModule: Module? = null

    private class FakeSystemGeolocation : SystemGeolocation {
        val positions = MutableSharedFlow<GeolocationPositionResult>(extraBufferCapacity = 16)
        var activeWatchers = 0
            private set
        var currentPositionResult: GeolocationPositionResult =
            GeolocationPositionResult.Error("not configured")

        override suspend fun getCurrentPosition(
            maximumAge: Duration?,
            timeout: Duration?,
            highAccuracy: Boolean,
        ): GeolocationPositionResult = currentPositionResult

        override suspend fun watchPosition(
            interval: Duration,
            highAccuracy: Boolean,
        ): Flow<GeolocationPositionResult> = positions
            .onStart { activeWatchers++ }
            .onCompletion { activeWatchers-- }
    }

    private class Fixture(
        val geo: GeolocationInterface,
        val runner: FakeJsRunner,
        val systemGeolocation: FakeSystemGeolocation,
        val resolver: WatchappPermissionResolver,
    )

    private fun fixture(scope: CoroutineScope, locationDefault: Boolean = false): Fixture {
        val resolver = WatchappPermissionResolver(
            FakeLockerAppPermissionDao(),
            LibPebbleConfigFlow(
                MutableStateFlow(
                    LibPebbleConfig(
                        watchConfig = WatchConfig(watchappDefaultLocationAllowed = locationDefault),
                    ),
                ),
            ),
        )
        val systemGeolocation = FakeSystemGeolocation()
        val module = module {
            single { resolver }
            single<SystemGeolocation> { systemGeolocation }
        }
        koin.loadModules(listOf(module), allowOverride = true)
        loadedModule = module
        val runner = fakeJsRunner(uuid)
        // The abstract class carries the whole gate; the platform subclasses only add
        // bridge annotations, so an anonymous subclass exercises the production logic.
        val geo = object : GeolocationInterface(scope, runner) {}
        return Fixture(geo, runner, systemGeolocation, resolver)
    }

    @AfterTest
    fun tearDown() {
        loadedModule?.let { koin.unloadModules(listOf(it)) }
        loadedModule = null
    }

    private fun position(lat: Double) = GeolocationPositionResult.Success(
        timestamp = Instant.fromEpochMilliseconds(0),
        latitude = lat,
        longitude = 0.0,
        accuracy = 1.0,
        altitude = null,
        heading = null,
        speed = null,
    )

    private fun Fixture.watchSuccessCount() =
        runner.evals.count { it.startsWith("_PebbleGeoCB._resultWatchSuccess(7,") }

    private fun Fixture.watchErrorCount() =
        runner.evals.count { it.startsWith("_PebbleGeoCB._resultWatchError(7,") }

    @Test
    fun watchDeniedAtStartReportsErrorAndNeverStartsStream() = runTest {
        val f = fixture(this, locationDefault = false)
        f.geo.watchPosition(id = 7.0, interval = 1000.0, highAccuracy = 0.0)
        runCurrent()
        assertEquals(0, f.systemGeolocation.activeWatchers, "denied watch must not open a GPS stream")
        assertEquals(1, f.watchErrorCount())
        f.geo.clearWatch(7)
    }

    @Test
    fun revokingLocationCancelsRunningStream() = runTest {
        val f = fixture(this, locationDefault = true)
        f.geo.watchPosition(id = 7.0, interval = 1000.0, highAccuracy = 0.0)
        runCurrent()
        assertEquals(1, f.systemGeolocation.activeWatchers)

        f.systemGeolocation.positions.emit(position(1.0))
        runCurrent()
        assertEquals(1, f.watchSuccessCount())

        // The user revokes while the watch is live: the stream must die immediately
        // and the app must be told, not keep receiving GPS until the session ends.
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Location, PermissionSetting.Deny)
        runCurrent()
        assertEquals(0, f.systemGeolocation.activeWatchers, "revocation must cancel the GPS stream")
        assertEquals(1, f.watchErrorCount())

        // Positions produced after revocation must never reach the app.
        f.systemGeolocation.positions.emit(position(2.0))
        runCurrent()
        assertEquals(1, f.watchSuccessCount(), "no positions may be delivered after revocation")
        f.geo.clearWatch(7)
    }

    @Test
    fun regrantRestartsRevokedStream() = runTest {
        val f = fixture(this, locationDefault = true)
        f.geo.watchPosition(id = 7.0, interval = 1000.0, highAccuracy = 0.0)
        runCurrent()
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Location, PermissionSetting.Deny)
        runCurrent()
        assertEquals(0, f.systemGeolocation.activeWatchers)

        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Location, PermissionSetting.Allow)
        runCurrent()
        assertEquals(1, f.systemGeolocation.activeWatchers, "re-grant must restart the stream")
        f.systemGeolocation.positions.emit(position(3.0))
        runCurrent()
        assertEquals(1, f.watchSuccessCount())
        f.geo.clearWatch(7)
    }

    @Test
    fun clearWatchStopsStreamAndGrantTracking() = runTest {
        val f = fixture(this, locationDefault = true)
        f.geo.watchPosition(id = 7.0, interval = 1000.0, highAccuracy = 0.0)
        runCurrent()
        assertEquals(1, f.systemGeolocation.activeWatchers)

        f.geo.clearWatch(7)
        runCurrent()
        assertEquals(0, f.systemGeolocation.activeWatchers)
    }

    @Test
    fun reusingAWatchIdCancelsThePreviousRegistration() = runTest {
        val f = fixture(this, locationDefault = true)
        f.geo.watchPosition(id = 7.0, interval = 1000.0, highAccuracy = 0.0)
        runCurrent()
        assertEquals(1, f.systemGeolocation.activeWatchers)

        // Same id again: the first registration must die with the overwrite, or a
        // page re-registering its watch would leak one collector per call.
        f.geo.watchPosition(id = 7.0, interval = 1000.0, highAccuracy = 0.0)
        runCurrent()
        assertEquals(1, f.systemGeolocation.activeWatchers, "id reuse must not stack streams")

        f.geo.clearWatch(7)
        runCurrent()
        assertEquals(0, f.systemGeolocation.activeWatchers, "exactly one job may back a reused id")
    }

    @Test
    fun deniedWatchRegistrationsAreBoundedByEviction() = runTest {
        // A denied watch stays registered so a re-grant can resume it, which means
        // an app retrying watchPosition with fresh ids after each denial (never
        // calling clearWatch) would otherwise pin one grant collector per retry
        // for a session that can last days.
        val f = fixture(this, locationDefault = false)
        val extra = 3
        repeat(GeolocationInterface.MAX_ACTIVE_WATCHES + extra) { i ->
            f.geo.watchPosition(id = (100 + i).toDouble(), interval = 1000.0, highAccuracy = 0.0)
        }
        runCurrent()
        assertEquals(0, f.systemGeolocation.activeWatchers, "denied watches must not open GPS streams")

        // A grant resumes every registration still tracked, so the stream count
        // proves eviction bounded the denied backlog at the cap.
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Location, PermissionSetting.Allow)
        runCurrent()
        assertEquals(
            GeolocationInterface.MAX_ACTIVE_WATCHES,
            f.systemGeolocation.activeWatchers,
            "a retry loop must not accumulate unbounded watch registrations",
        )

        repeat(GeolocationInterface.MAX_ACTIVE_WATCHES + extra) { i -> f.geo.clearWatch(100 + i) }
        runCurrent()
        assertEquals(0, f.systemGeolocation.activeWatchers)
    }

    @Test
    fun getCurrentPositionIsGatedPerCall() = runTest {
        val f = fixture(this, locationDefault = false)
        f.systemGeolocation.currentPositionResult = position(4.0)

        f.geo.getCurrentPosition(id = 3.0, maximumAgeMs = -1.0, timeoutMs = -1.0, highAccuracy = 0.0)
        runCurrent()
        assertTrue(
            f.runner.evals.any { it.startsWith("_PebbleGeoCB._resultGetError(3,") },
            "denied getCurrentPosition must report an error to JS",
        )

        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Location, PermissionSetting.Allow)
        f.geo.getCurrentPosition(id = 4.0, maximumAgeMs = -1.0, timeoutMs = -1.0, highAccuracy = 0.0)
        runCurrent()
        assertTrue(
            f.runner.evals.any { it.startsWith("_PebbleGeoCB._resultGetSuccess(4,") },
            "granted getCurrentPosition must deliver the position",
        )
    }
}
