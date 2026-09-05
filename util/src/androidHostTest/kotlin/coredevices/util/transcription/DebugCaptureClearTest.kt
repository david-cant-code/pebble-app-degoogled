package coredevices.util.transcription

import coredevices.util.CoreConfig
import coredevices.util.CoreConfigFlow
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Pins when the transcription service empties the debug capture directory:
 * once at start in a build that cannot honour the hook, and each time the
 * hook goes off in one that can, never while it is on.
 */
class DebugCaptureClearTest {

    private class Harness(captureDump: Boolean, debugBuild: Boolean) {
        val config = MutableStateFlow(
            CoreConfig(sttConfig = STTConfig(mode = CactusSTTMode.LocalOnly, modelName = "model-a", debugCaptureDump = captureDump)),
        )
        @Volatile var clears = 0
        val service = WhisperTranscriptionService(
            coreConfigFlow = CoreConfigFlow(config),
            modelProvider = FakeModelProvider(),
            analytics = NoopAnalytics,
            inferenceBoost = NoOpInferenceBoost(),
            engine = FakeWhisperEngine().engine,
            debugBuild = { debugBuild },
            clearCaptures = { clears++ },
        )

        fun setCaptureDump(on: Boolean) {
            config.value = config.value.copy(sttConfig = config.value.sttConfig.copy(debugCaptureDump = on))
        }

        suspend fun awaitClears(expected: Int) {
            try {
                withTimeout(10.seconds) { while (clears < expected) delay(10) }
            } catch (e: Exception) {
                throw AssertionError("Timed out waiting for $expected clears, saw $clears", e)
            }
            assertEquals(expected, clears)
        }
    }

    @Test
    fun aBuildThatCannotHonourTheHookClearsOnceAtStart() = runBlocking(Dispatchers.Default) {
        val h = Harness(captureDump = true, debugBuild = false)
        h.awaitClears(1)
        h.setCaptureDump(false)
        h.setCaptureDump(true)
        delay(300)
        assertEquals(1, h.clears, "the stored flag is inert here, so nothing new to clear")
    }

    @Test
    fun aDebugBuildClearsEachTimeTheHookGoesOff() = runBlocking(Dispatchers.Default) {
        val h = Harness(captureDump = true, debugBuild = true)
        delay(300)
        assertEquals(0, h.clears, "captures are kept while the hook is on")
        h.setCaptureDump(false)
        h.awaitClears(1)
        h.setCaptureDump(true)
        delay(300)
        assertEquals(1, h.clears)
        h.setCaptureDump(false)
        h.awaitClears(2)
    }
}
