package coredevices.util.transcription

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import coredevices.util.models.WhisperTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the speed estimator's caching contract and the calibration math
 * the model picker builds its estimates and its step-down on.
 */
class DeviceSpeedTest {

    private fun estimator(
        settings: Settings = MapSettings(),
        probe: (Int) -> Long,
        supported: Boolean = true,
        threads: Int = 2,
        now: () -> Long = { 1_000L },
    ) = DeviceSpeedEstimator(
        settings = settings,
        threadCount = { threads },
        probe = probe,
        supported = { supported },
        now = now,
    )

    @Test
    fun measureCachesTheScoreForLaterInstances() = runBlocking {
        val settings = MapSettings()
        val first = estimator(settings, probe = { 250_000_000L })
        assertNull(first.cached())
        val measured = first.measure()
        assertEquals(SpeedScore(nsPerBlock = 250_000_000L, threads = 2, measuredAtEpochMs = 1_000L), measured)
        assertEquals(measured, estimator(settings, probe = { error("must not run") }).cached())
    }

    @Test
    fun cachedOrMeasureRunsTheProbeOnce() = runBlocking {
        var runs = 0
        val estimator = estimator(probe = { runs++; 10L })
        estimator.cachedOrMeasure()
        estimator.cachedOrMeasure()
        assertEquals(1, runs)
    }

    @Test
    fun callersArrivingDuringAProbeShareItsScore() = runBlocking(Dispatchers.Default) {
        val gate = CountDownLatch(1)
        val runs = AtomicInteger()
        val estimator = estimator(probe = { runs.incrementAndGet(); gate.await(10, TimeUnit.SECONDS); 10L })
        val first = async { estimator.cachedOrMeasure() }
        val second = async { estimator.cachedOrMeasure() }
        delay(200)
        gate.countDown()
        assertEquals(first.await(), second.await())
        assertEquals(1, runs.get(), "the second caller must wait for the running probe, not start its own")
    }

    @Test
    fun aFailedProbeKeepsThePreviousScore() = runBlocking {
        val settings = MapSettings()
        val good = estimator(settings, probe = { 100L }).measure()
        val failing = estimator(settings, probe = { error("engine unavailable") })
        assertEquals(good, failing.measure())
        assertEquals(good, failing.cached())
    }

    @Test
    fun anUnsupportedEngineNeverProbes() = runBlocking {
        var runs = 0
        val estimator = estimator(probe = { runs++; 10L }, supported = false)
        assertNull(estimator.measure())
        assertEquals(0, runs)
    }

    @Test
    fun aScoreFromAnOlderProbeIsDiscarded() = runBlocking {
        val settings = MapSettings()
        estimator(settings, probe = { 100L }).measure()
        settings.putInt("stt_speed_probe_version", WhisperSpeedCalibration.PROBE_VERSION - 1)
        assertNull(estimator(settings, probe = { error("must not run") }).cached())
    }

    @Test
    fun classificationFollowsTheWatchBudget() {
        assertEquals(WindowFit.Fits, WhisperSpeedCalibration.classify(10.0))
        assertEquals(WindowFit.Marginal, WhisperSpeedCalibration.classify(10.01))
        // The band ends where the phone reports the loss, one margin under the watch's 15 s.
        assertEquals(WindowFit.Marginal, WhisperSpeedCalibration.classify(14.0))
        assertEquals(WindowFit.Exceeds, WhisperSpeedCalibration.classify(14.01))
        assertEquals(WindowFit.Exceeds, WhisperSpeedCalibration.classify(15.0))
    }

    @Test
    fun estimateScalesWithTheScoreAndCarriesTheBackgroundMargin() {
        val reference = SpeedScore(WhisperSpeedCalibration.REFERENCE_SCORE_NS, threads = 2, measuredAtEpochMs = 0L)
        val twiceAsSlow = reference.copy(nsPerBlock = reference.nsPerBlock * 2)
        for (tier in WhisperTier.entries) {
            val id = "whisper-${tier.name.lowercase()}-en"
            val atReference = assertNotNull(WhisperSpeedCalibration.estimateWindowSeconds(id, reference))
            val expected = assertNotNull(WhisperSpeedCalibration.referenceWindowSeconds(tier)) *
                WhisperSpeedCalibration.BACKGROUND_MARGIN
            assertEquals(expected, atReference, 1e-9)
            assertEquals(atReference * 2, assertNotNull(WhisperSpeedCalibration.estimateWindowSeconds(id, twiceAsSlow)), 1e-9)
        }
        assertNull(WhisperSpeedCalibration.estimateWindowSeconds("whisper-base-en", null))
        assertNull(WhisperSpeedCalibration.estimateWindowSeconds("not-a-model", reference))
    }

    @Test
    fun rowTextNamesTheFitInPlainWords() {
        assertEquals("147 MB", modelRowText(147, null))
        assertEquals("74 MB, under 1 s for a 15 s recording", modelRowText(74, 0.4))
        assertEquals("147 MB, about 4 s for a 15 s recording", modelRowText(147, 3.6))
        assertEquals(
            "487 MB, about 12 s for a 15 s recording, close to the watch's limit",
            modelRowText(487, 12.2),
        )
        assertEquals(
            "487 MB, too slow for the watch on this phone (about 22 s for a 15 s recording)",
            modelRowText(487, 22.4),
        )
    }
}
