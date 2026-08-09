package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.FakeAppMessages
import io.rebble.libpebblecommon.connection.fakeWatch
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import kotlinx.coroutines.channels.Channel
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

/**
 * Recording JsRunner for unit tests of the bridge interfaces (geolocation gate,
 * private PKJS interface). Captures everything the code under test signals back
 * towards the app's JS so assertions can check what the app would have observed,
 * without any real JS engine behind it.
 */
class FakeJsRunner(
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    device: CompanionAppDevice,
) : JsRunner(appInfo, lockerEntry, Path("unused.js"), device, Channel(Channel.UNLIMITED)) {
    val evals = mutableListOf<String>()
    val interceptResponses = mutableListOf<Pair<String, InterceptResponse>>()

    override suspend fun start() {}
    override suspend fun stop() {}
    override suspend fun loadAppJs(jsUrl: String) {}
    override suspend fun signalInterceptResponse(callbackId: String, result: InterceptResponse) {
        interceptResponses += callbackId to result
    }
    override suspend fun signalNewAppMessageData(data: String?): Boolean = true
    override suspend fun signalTimelineToken(callId: String, token: String) {}
    override suspend fun signalTimelineTokenFail(callId: String) {}
    override suspend fun signalReady() {}
    override suspend fun signalShowConfiguration() {}
    override suspend fun signalWebviewClosed(data: String?) {}
    override suspend fun eval(js: String) {
        evals += js
    }
    override suspend fun evalWithResult(js: String): Any? {
        evals += js
        return null
    }
    override fun debugForceGC() {}
}

/** A runner for [uuid] wired to a fake connected watch; enough for bridge tests. */
fun fakeJsRunner(uuid: Uuid): FakeJsRunner {
    val watch = fakeWatch(connected = true) as ConnectedPebbleDevice
    return FakeJsRunner(
        appInfo = PbwAppInfo(
            uuid = uuid.toString(),
            shortName = "Test App",
            versionLabel = "1.0",
            resources = Resources(emptyList()),
        ),
        lockerEntry = LockerEntry(
            id = uuid,
            version = "1.0",
            title = "Test App",
            type = "watchapp",
            developerName = "Test Developer",
            configurable = false,
            pbwVersionCode = "1",
            platforms = emptyList(),
        ),
        device = CompanionAppDevice(watch.identifier, watch.watchInfo, FakeAppMessages()),
    )
}
