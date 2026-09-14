package coredevices.coreapp.util

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import java.util.concurrent.LinkedBlockingQueue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Fake watch for the companion session tests: records the messages and the ACK/NACK results a
 * session relays to it, and ACKs every message.
 */
internal class RecordingWatch : ConnectedPebble.AppMessages {
    val sent = LinkedBlockingQueue<AppMessageData>()
    val results = LinkedBlockingQueue<AppMessageResult>()

    override val transactionSequence: Iterator<UByte> =
        generateSequence(0) { it + 1 }.map { it.toUByte() }.iterator()

    override suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult {
        sent.put(appMessageData)
        return AppMessageResult.ACK(appMessageData.transactionId)
    }

    override suspend fun sendAppMessageResult(appMessageResult: AppMessageResult) {
        results.put(appMessageResult)
    }

    override fun inboundAppMessages(appUuid: Uuid): Flow<AppMessageData> = emptyFlow()
}

internal fun testWatchInfo() = WatchInfo(
    runningFwVersion = testFirmwareVersion(),
    recoveryFwVersion = null,
    platform = WatchHardwarePlatform.UNKNOWN,
    bootloaderTimestamp = Instant.DISTANT_PAST,
    board = "test",
    serial = "TESTSERIAL",
    btAddress = "00:00:00:00:00:00",
    resourceCrc = 0L,
    resourceTimestamp = Instant.DISTANT_PAST,
    language = "en_US",
    languageVersion = 1,
    capabilities = emptySet(),
    isUnfaithful = false,
    healthInsightsVersion = null,
    javascriptVersion = null,
    color = WatchColor.entries.first(),
)

private fun testFirmwareVersion() = FirmwareVersion(
    stringVersion = "1.0.0",
    timestamp = Instant.DISTANT_PAST,
    major = 1,
    minor = 0,
    patch = 0,
    suffix = null,
    gitHash = "",
    isRecovery = false,
    isDualSlot = false,
    isSlot0 = false,
)
