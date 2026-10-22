package coredevices.haversine

import kotlin.time.Instant

sealed interface SatelliteStatus {
    val satellite: KMPHaversineSatellite

    class Transferring(
        override val satellite: KMPHaversineSatellite,
        val transferStatus: TransferStatus,
    ) : SatelliteStatus

    /** Declared so `RingSync`'s `when` compiles. v1 never emits these. */
    sealed interface FirmwareUpdating : SatelliteStatus {
        val newVersion: String

        class Started(
            override val satellite: KMPHaversineSatellite,
            override val newVersion: String,
        ) : FirmwareUpdating

        class Success(
            override val satellite: KMPHaversineSatellite,
            override val newVersion: String,
        ) : FirmwareUpdating

        class Failed(
            override val satellite: KMPHaversineSatellite,
            override val newVersion: String,
        ) : FirmwareUpdating

        class NotStarted(
            override val satellite: KMPHaversineSatellite,
            override val newVersion: String,
        ) : FirmwareUpdating
    }

    class ProgrammingUserId(
        override val satellite: KMPHaversineSatellite,
    ) : SatelliteStatus

    class BluetoothFailure(
        override val satellite: KMPHaversineSatellite,
        val reason: BluetoothFailureReason,
    ) : SatelliteStatus
}

enum class BluetoothFailureReason {
    PeerRemovedPairingInformation,
    AuthenticationFailed,
    ConnectFailed,
}

sealed interface TransferStatus {
    val satellite: KMPHaversineSatellite

    class TransferStarted(
        override val satellite: KMPHaversineSatellite,
        val rollover: Boolean,
        val willTransferRange: IntRange,
    ) : TransferStatus

    class TransferInProgress(
        override val satellite: KMPHaversineSatellite,
        val currentCollectionIndex: Int,
        val collectionStartIndex: Int?,
    ) : TransferStatus

    class TransferTypeDetermined(
        override val satellite: KMPHaversineSatellite,
        val isAudio: Boolean,
        val buttonSequence: String?,
        val collectionStartIndex: Int?,
        val collectionIndex: Int,
        val final: Boolean,
        val advertisementReceivedTimestamp: Instant,
        val lifetimeCollectionCount: UInt?,
    ) : TransferStatus

    class TransferComplete(
        override val satellite: KMPHaversineSatellite,
        val samples: ShortArray,
        val sampleRate: UInt,
        val collectionStartCount: Long,
        val collectionIndex: Int,
        val buttonSequence: String?,
        val buttonReleaseTimestamp: Instant?,
        val transferCompleteTimestamp: Instant,
    ) : TransferStatus

    class TransferFailed(
        override val satellite: KMPHaversineSatellite,
        val collectionIndex: Int,
        val exception: Throwable?,
    ) : TransferStatus

    class IrrecoverableDataDetected(
        override val satellite: KMPHaversineSatellite,
        val exception: Throwable?,
        val collection: CollectionGroup?,
    ) : TransferStatus
}

class CollectionGroup(
    val startIndex: Int?,
    val indices: IntRange,
)

class DataDecodeException(
    message: String,
    val data: ByteArray?,
) : Exception(message)

class HaversineFeatureUnavailable(feature: String) : UnsupportedOperationException(feature)
