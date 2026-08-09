package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.database.dao.FakeLockerAppPermissionDao
import io.rebble.libpebblecommon.database.dao.FakeLockerEntryDao
import io.rebble.libpebblecommon.database.dao.FakeTimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.FakeTimelineReminderRealDao
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.locker.PermissionSetting
import io.rebble.libpebblecommon.locker.WatchappPermissionResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * Proves the phone-side interceptor gate in [PrivatePKJSInterface.onIntercepted]
 * consults the permission resolver. onIntercepted is exposed directly on the
 * `_Pebble` bridge, so a hostile bundle can call it without going through the XHR
 * shim; the Kotlin-side check is what actually closes the egress-on-behalf vector,
 * which makes "denied never reaches the interceptor" the load-bearing assertion.
 */
class PrivatePKJSInterfaceGateTest {
    private val uuid = Uuid.parse("00000000-0000-0000-0000-0000000000aa")

    private class RecordingInterceptor : HttpInterceptor {
        val requests = mutableListOf<String>()
        override fun shouldIntercept(url: String): Boolean = true
        override suspend fun onIntercepted(
            url: String,
            method: String,
            body: String?,
            appUuid: Uuid,
        ): InterceptResponse {
            requests += url
            return InterceptResponse(result = "intercepted", status = 200)
        }
    }

    private class Fixture(
        val bridge: PrivatePKJSInterface,
        val runner: FakeJsRunner,
        val interceptor: RecordingInterceptor,
        val resolver: WatchappPermissionResolver,
    )

    private fun fixture(scope: CoroutineScope, networkDefault: Boolean = false): Fixture {
        val configFlow = MutableStateFlow(
            LibPebbleConfig(
                watchConfig = WatchConfig(watchappDefaultNetworkAllowed = networkDefault),
            ),
        )
        val resolver = WatchappPermissionResolver(
            FakeLockerAppPermissionDao(),
            LibPebbleConfigFlow(configFlow),
        )
        val watchConfigFlow = WatchConfigFlow(configFlow)
        val runner = fakeJsRunner(uuid)
        val interceptor = RecordingInterceptor()
        // The emulator's DAOs are never touched here (nothing routes to the timeline
        // emulator in these tests); it exists only because HttpInterceptorManager
        // requires the concrete type.
        val emulator = RemoteTimelineEmulator(
            watchConfigFlow,
            Json,
            FakeTimelinePinRealDao(),
            FakeTimelineReminderRealDao(),
        )
        val bridge = object : PrivatePKJSInterface(
            runner,
            runner.device,
            scope,
            MutableSharedFlow(extraBufferCapacity = 8),
            Channel(Channel.UNLIMITED),
            JsTokenUtil(
                object : TokenProvider {
                    override suspend fun getDevToken(): String? = null
                },
                FakeLockerEntryDao(),
                watchConfigFlow,
            ),
            emulator,
            HttpInterceptorManager(emulator, InjectedPKJSHttpInterceptors(listOf(interceptor))),
            NotificationConfigFlow(configFlow),
            resolver,
        ) {}
        return Fixture(bridge, runner, interceptor, resolver)
    }

    @Test
    fun deniedAppNeverReachesInterceptorAndGetsError() = runTest {
        val f = fixture(this, networkDefault = false)
        f.bridge.onIntercepted("cb1", "https://api.example.com/weather", "GET", null)
        advanceUntilIdle()
        assertTrue(
            f.interceptor.requests.isEmpty(),
            "a network-denied app must not reach the phone-side interceptors",
        )
        assertEquals(listOf("cb1" to InterceptResponse.ERROR), f.runner.interceptResponses)
    }

    @Test
    fun grantedAppReachesInterceptorAndGetsItsResponse() = runTest {
        val f = fixture(this, networkDefault = false)
        f.resolver.setWatchappPermission(uuid, LockerAppPermissionType.Network, PermissionSetting.Allow)
        f.bridge.onIntercepted("cb2", "https://api.example.com/weather", "GET", null)
        advanceUntilIdle()
        assertEquals(listOf("https://api.example.com/weather"), f.interceptor.requests)
        val (callbackId, response) = f.runner.interceptResponses.single()
        assertEquals("cb2", callbackId)
        assertEquals(200, response.status)
    }
}
