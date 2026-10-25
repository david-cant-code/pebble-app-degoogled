package io.rebble.libpebblecommon.datalogging

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.SystemAppIDs.SYSTEM_APP_UUID
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.services.WatchInfo
import kotlin.uuid.Uuid

class Datalogging(
    private val webServices: WebServices,
    private val healthDataProcessor: HealthDataProcessor,
) {
    private val logger = Logger.withTag("Datalogging")

    fun logData(
        sessionId: UByte,
        uuid: Uuid,
        tag: UInt,
        data: ByteArray,
        watchInfo: WatchInfo,
        itemSize: UShort,
        itemsLeft: UInt,
    ) {
        // Handle health tags
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            healthDataProcessor.handleSendDataItems(sessionId, data, itemsLeft)
            return
        }

        // Handle system-app datalogging tags
        if (uuid == SYSTEM_APP_UUID) {
            when (tag) {
                ANALYTICS_HEARTBEAT_TAG -> {
                    // Fixed-size native_heartbeat_record items (no inner length prefix).
                    val size = itemSize.toInt()
                    if (size <= 0) {
                        logger.w { "Analytics heartbeat with itemSize=$size; ignoring" }
                        return
                    }
                    var offset = 0
                    while (offset + size <= data.size) {
                        val itemData = data.copyOfRange(offset, offset + size)
                        webServices.uploadAnalyticsHeartbeat(itemData, watchInfo)
                        offset += size
                    }
                }
            }
        }
    }

    fun openSession(sessionId: UByte, tag: UInt, applicationUuid: Uuid, itemSize: UShort) {
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            healthDataProcessor.handleSessionOpen(sessionId, tag, applicationUuid, itemSize)
        }
    }

    fun closeSession(sessionId: UByte, tag: UInt) {
        if (tag in HealthDataProcessor.HEALTH_TAGS) {
            healthDataProcessor.handleSessionClose(sessionId)
        }
    }

    companion object {
        private val ANALYTICS_HEARTBEAT_TAG: UInt = 87u
    }
}

