package coredevices.util.transcription

import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins that a detector transfer in flight never holds up a dictation:
 * the provider's resolve blocks on the detector's mutex for the whole
 * download, so the service must not enter it before the file exists.
 */
class DetectorInstallLockTest {

    /** A provider whose detector resolve blocks like a download holding the detector's mutex. */
    private class InstallingProvider : CactusModelPathProvider by FakeModelProvider() {
        @Volatile var installed = false
        val resolveCalls = AtomicInteger()
        val transfer = CompletableDeferred<Unit>()

        override fun isVadModelInstalled(): Boolean = installed

        override suspend fun getVadModelPath(): String? {
            resolveCalls.incrementAndGet()
            transfer.await()
            return "/fake/vad-silero/ggml-silero.bin"
        }
    }

    @Test
    fun aDetectorTransferInFlightNeverHoldsUpADictation() = runBlocking(Dispatchers.Default) {
        val provider = InstallingProvider()
        val service = WhisperTranscriptionService(
            coreConfigFlow = CoreConfigFlow(
                MutableStateFlow(CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = "model-a"))),
            ),
            modelProvider = provider,
            analytics = NoopAnalytics,
            inferenceBoost = NoOpInferenceBoost(),
            engine = FakeWhisperEngine().engine,
            debugBuild = { false },
            clearCaptures = {},
        )
        withTimeout(30.seconds) { while (!service.isModelReady) delay(10) }

        // A short init timeout turns any wait on the transfer into a failure.
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000, initTimeout = 3.seconds))
        assertEquals(0, provider.resolveCalls.get(), "nothing resolves the detector before its file exists")
        assertFalse(service.isVadReady)

        // The transfer finishes: the next dictation loads the detector.
        provider.installed = true
        provider.transfer.complete(Unit)
        assertEquals("hello world", service.transcribeLocal(realPcmBytes(), sampleRate = 16_000, initTimeout = 3.seconds))
        assertTrue(service.isVadReady)
        assertEquals(1, provider.resolveCalls.get())
    }
}
