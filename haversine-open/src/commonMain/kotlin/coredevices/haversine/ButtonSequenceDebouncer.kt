package coredevices.haversine

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

class ButtonGesture(val sequence: String)

/**
 * ASSUMPTION: gesture items that arrive within 1.5 s are one sequence, and
 * the last one wins. Audio transfers are ignored. Hardware has not confirmed
 * the window.
 */
class ButtonSequenceDebouncer(private val scope: CoroutineScope) {
    private val gestures = MutableSharedFlow<ButtonGesture>(extraBufferCapacity = 8)
    val buttonGestures: Flow<ButtonGesture> = gestures
    private var quiet: Job? = null
    private var pending: String? = null

    fun onTransferStatus(status: TransferStatus) {
        if (status !is TransferStatus.TransferTypeDetermined || status.isAudio) return
        val sequence = status.buttonSequence?.takeIf { it.isNotBlank() } ?: return
        pending = sequence
        quiet?.cancel()
        quiet = scope.launch {
            delay(1500.milliseconds)
            pending?.let { gestures.emit(ButtonGesture(it)) }
        }
    }
}
