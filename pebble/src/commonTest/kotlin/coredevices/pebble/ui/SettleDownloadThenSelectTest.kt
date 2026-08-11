package coredevices.pebble.ui

import coredevices.util.models.ModelDownloadStatus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the post-download settle/select decision behind the model
 * management screen's download button. The guarded regressions: a
 * cancelled download must not select its (absent) model, a failed
 * download's waiter must terminate instead of surviving to select its
 * model on the next unrelated download's Idle, and a successful download
 * must still be selected.
 *
 * The status flow is a StateFlow in production, so each transition is
 * pumped with runCurrent(): StateFlow conflates rapid writes and
 * deduplicates equal values, and the waiter must observe the Downloading
 * step for the scenario to mean what it says.
 */
class SettleDownloadThenSelectTest {

    private val status = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)
    private var installed = false
    private var refreshes = 0
    private var selections = 0
    private var waiterDone = false

    private fun TestScope.startWaiter() {
        // UNDISPATCHED so the waiter is already collecting (and has taken
        // its replayed current value) before the test mutates the flow.
        launch(start = CoroutineStart.UNDISPATCHED) {
            settleDownloadThenSelect(
                status = status,
                isInstalled = { installed },
                refresh = { refreshes++ },
                select = { selections++ },
            )
            waiterDone = true
        }
    }

    private fun TestScope.emit(value: ModelDownloadStatus) {
        status.value = value
        runCurrent()
    }

    @Test
    fun successfulDownloadIsSelected() = runTest {
        startWaiter()
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        installed = true
        emit(ModelDownloadStatus.Idle)
        assertTrue(waiterDone)
        assertEquals(1, selections)
        assertEquals(1, refreshes)
    }

    @Test
    fun cancelledDownloadIsNotSelected() = runTest {
        startWaiter()
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        // Android cancel settles as Idle with nothing installed.
        emit(ModelDownloadStatus.Idle)
        assertTrue(waiterDone)
        assertEquals(0, selections)
        assertEquals(1, refreshes, "the list still refreshes so a torn state never lingers")
    }

    @Test
    fun iosCancelStatusAlsoSettles() = runTest {
        startWaiter()
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        emit(ModelDownloadStatus.Cancelled)
        assertTrue(waiterDone)
        assertEquals(0, selections)
    }

    @Test
    fun failedDownloadTerminatesTheWaiterWithoutSelecting() = runTest {
        startWaiter()
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        emit(ModelDownloadStatus.Failed("whisper-base-en", "network gone"))
        assertTrue(waiterDone, "a Failed status must settle the waiter, not leave it alive")
        assertEquals(0, selections)

        // The regression this guards: a later unrelated download reaching
        // Idle must not trigger the dead waiter's selection.
        installed = true
        emit(ModelDownloadStatus.Downloading("whisper-small-en"))
        emit(ModelDownloadStatus.Idle)
        assertEquals(0, selections)
    }

    @Test
    fun replayedCurrentStatusDoesNotSettleTheWait() = runTest {
        // The status flow's current value predates this download (the
        // download was only just scheduled); replaying it must not count
        // as settling.
        startWaiter()
        runCurrent()
        assertFalse(waiterDone)
        assertEquals(0, refreshes)

        // Drive the scenario to its end so the waiter completes.
        emit(ModelDownloadStatus.Downloading("whisper-base-en"))
        emit(ModelDownloadStatus.Idle)
        assertTrue(waiterDone)
    }
}
