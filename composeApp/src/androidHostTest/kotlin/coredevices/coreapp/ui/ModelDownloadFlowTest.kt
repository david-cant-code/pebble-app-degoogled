package coredevices.coreapp.ui

import coredevices.util.models.ModelDownloadStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the download-then-act machine both model prompts run on: an
 * installed model is handed over without a download, a refused schedule
 * and a download that settles without installing both read as failed and
 * end the switch, a cancel ends it without ever selecting, a settled
 * install selects exactly once, a second tap while the first is still
 * checking the install is ignored, and a settle cancelled with its scope
 * still ends the switch. The status flow is a StateFlow in production, so
 * each transition is pumped with runCurrent().
 */
class ModelDownloadFlowTest {

    private val status = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)
    private var installed = false
    /** When set, the install check suspends on it, as the IO round trip does in production. */
    private var installedGate: CompletableDeferred<Boolean>? = null
    private var scheduleAccepted = true
    private val scheduled = mutableListOf<String>()
    private var cancels = 0
    private var started = 0
    private val installs = mutableListOf<String>()
    private var ended = 0

    private fun TestScope.flow(scope: CoroutineScope = backgroundScope) = ModelDownloadFlow(
        scope = scope,
        status = status,
        isInstalled = { installedGate?.await() ?: installed },
        schedule = { slug -> scheduled += slug; scheduleAccepted },
        cancelDownload = { cancels++ },
        onStarted = { started++ },
        onInstalled = { installs += it },
        onEnded = { ended++ },
    )

    private fun TestScope.emit(value: ModelDownloadStatus) {
        status.value = value
        runCurrent()
    }

    @Test
    fun installedModelIsHandedOverWithoutADownload() = runTest {
        installed = true
        val flow = flow()
        flow.download("whisper-base-en")
        runCurrent()
        assertEquals(listOf("whisper-base-en"), installs)
        assertEquals(emptyList(), scheduled)
        assertFalse(flow.downloading)
        assertEquals(0, started)
    }

    @Test
    fun refusedScheduleFailsWithoutStarting() = runTest {
        scheduleAccepted = false
        val flow = flow()
        flow.download("whisper-base-en")
        runCurrent()
        assertTrue(flow.failed)
        assertFalse(flow.downloading)
        assertEquals(0, started)
        assertEquals(0, ended, "nothing started, so nothing ends")
    }

    @Test
    fun settledInstallSelectsOnce() = runTest {
        val flow = flow()
        flow.download("whisper-base-en")
        runCurrent()
        assertTrue(flow.downloading)
        assertEquals(1, started)
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        assertTrue(flow.downloading, "a Downloading status is not settled")
        installed = true
        emit(ModelDownloadStatus.Idle)
        assertFalse(flow.downloading)
        assertFalse(flow.failed)
        assertEquals(listOf("whisper-base-en"), installs)
        assertEquals(0, ended)
    }

    @Test
    fun settledWithoutInstallFailsAndEnds() = runTest {
        val flow = flow()
        flow.download("whisper-base-en")
        runCurrent()
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        // Android cancel and a torn download both settle as Idle with nothing installed.
        emit(ModelDownloadStatus.Idle)
        assertTrue(flow.failed)
        assertFalse(flow.downloading)
        assertEquals(emptyList(), installs)
        assertEquals(1, ended)
    }

    @Test
    fun failedStatusFailsAndEnds() = runTest {
        val flow = flow()
        flow.download("whisper-base-en")
        runCurrent()
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        emit(ModelDownloadStatus.Failed("whisper-base-en", "network gone"))
        assertTrue(flow.failed)
        assertEquals(1, ended)
        // A retry clears the failure and schedules again.
        flow.download("whisper-base-en")
        runCurrent()
        assertFalse(flow.failed)
        assertEquals(listOf("whisper-base-en", "whisper-base-en"), scheduled)
    }

    @Test
    fun cancelEndsTheDownloadAndNeverSelects() = runTest {
        val flow = flow()
        flow.download("whisper-base-en")
        runCurrent()
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        flow.cancel()
        assertEquals(1, cancels)
        assertFalse(flow.downloading)
        assertEquals(1, ended)
        // The download manager's own settle after the cancel must not select.
        installed = true
        emit(ModelDownloadStatus.Idle)
        assertEquals(emptyList(), installs)
        assertEquals(1, ended)
    }

    @Test
    fun cancelWithoutADownloadDoesNothing() = runTest {
        val flow = flow()
        flow.cancel()
        assertEquals(0, cancels)
        assertEquals(0, ended)
    }

    @Test
    fun aSecondTapWhileTheInstallCheckIsPendingIsIgnored() = runTest {
        val gate = CompletableDeferred<Boolean>()
        installedGate = gate
        val flow = flow()
        flow.download("whisper-base-en")
        runCurrent()
        assertFalse(flow.downloading, "the schedule has not run yet")
        flow.download("whisper-base-en")
        runCurrent()
        gate.complete(false)
        installedGate = null
        runCurrent()
        assertEquals(listOf("whisper-base-en"), scheduled, "one schedule for two taps")
        assertEquals(1, started)
        installed = true
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        emit(ModelDownloadStatus.Idle)
        assertEquals(listOf("whisper-base-en"), installs, "one selection for two taps")
    }

    @Test
    fun aSettleCancelledWithItsScopeEndsTheSwitch() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
        val flow = flow(scope)
        flow.download("whisper-base-en")
        runCurrent()
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        assertTrue(flow.downloading)
        scope.cancel()
        runCurrent()
        assertFalse(flow.downloading)
        assertEquals(1, ended, "the switch ends with the scope")
        assertEquals(0, cancels, "the download itself is left to the download manager")
        // Nothing waits any more, so a later settle selects nothing.
        installed = true
        emit(ModelDownloadStatus.Idle)
        assertEquals(emptyList(), installs)
    }
}
