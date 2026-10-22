package coredevices.coreapp.di

import co.touchlab.kermit.Logger
import coredevices.coreapp.indexfeed.AcceptResult
import coredevices.coreapp.indexfeed.IncomingRecording
import coredevices.coreapp.indexfeed.IndexRecordingStore
import coredevices.coreapp.indexfeed.IndexTranscriptionPipeline
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.haversine.SatelliteStatus
import coredevices.haversine.TransferStatus
import coredevices.haversine.removeDCBias
import coredevices.libindex.IndexRingClient
import coredevices.libindex.database.BasePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class GravelIndexClient : IndexRingClient {
    private val lines = MutableStateFlow<String?>(null)
    override val status: StateFlow<String?> = lines

    fun post(text: String) {
        lines.value = text
    }
}

/**
 * Starts the satellite scan once a ring is paired and stores each finished
 * recording, then queues a transcription.
 */
class GravelRingSync(
    private val prefs: BasePreferences,
    private val manager: KMPHaversineSatelliteManager,
    private val client: GravelIndexClient,
    private val store: IndexRecordingStore,
    private val pipeline: IndexTranscriptionPipeline,
) {
    private val log = Logger.withTag("GravelRing")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            store.ensureLoaded()
            pipeline.resumePending()
            combine(prefs.ringPaired, manager.lastRing) { paired, _ -> paired }
                .collectLatest { paired ->
                    if (paired == null) return@collectLatest
                    client.post("Looking for the paired ring")
                    manager.awaitBluetoothReady()
                    manager.startScanning().collect { status ->
                        onStatus(status)
                    }
                }
        }
    }

    private suspend fun onStatus(status: SatelliteStatus) {
        when (status) {
            is SatelliteStatus.BluetoothFailure ->
                client.post("Bluetooth: ${status.reason}")
            is SatelliteStatus.Transferring -> when (val transfer = status.transferStatus) {
                is TransferStatus.TransferStarted ->
                    client.post("Syncing collections ${transfer.willTransferRange}")
                is TransferStatus.TransferTypeDetermined ->
                    transfer.buttonSequence?.let { client.post("Button: $it") }
                is TransferStatus.TransferComplete -> save(transfer)
                is TransferStatus.TransferFailed ->
                    client.post("Sync failed at ${transfer.collectionIndex}")
                is TransferStatus.IrrecoverableDataDetected ->
                    client.post("Skipped a damaged collection")
                else -> Unit
            }
            else -> Unit
        }
    }

    private suspend fun save(transfer: TransferStatus.TransferComplete) {
        val samples = transfer.samples.copyOf()
        removeDCBias(samples)
        val rate = transfer.sampleRate.toInt().coerceAtLeast(1)
        val result = store.accept(
            IncomingRecording(
                ringId = transfer.satellite.id,
                collectionIndex = transfer.collectionIndex,
                recordedAtEpochSeconds = transfer.transferCompleteTimestamp.epochSeconds,
                durationMillis = samples.size * 1000L / rate,
                buttonSequence = transfer.buttonSequence,
                sampleRate = rate,
                wav = pcm16LeWav(samples, rate),
            ),
        )
        when (result) {
            is AcceptResult.Inserted -> {
                log.i { "saved ${result.recording.audioFileName} ${samples.size} samples at $rate Hz" }
                client.post("Saved recording ${result.recording.collectionIndex}")
                pipeline.enqueue(result.recording.id)
            }
            is AcceptResult.Duplicate ->
                client.post("Already saved recording ${result.recording.collectionIndex}")
        }
    }
}
