package com.anopticlabs.gravel.transcription

import co.touchlab.kermit.Logger
import coredevices.util.transcription.WhisperTranscriptionService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlin.time.Duration

/**
 * Gravel: watch dictation and Notebook recordings on the user's transcription server. A dictation that needs the server
 * to itself holds it ([raise], [supersede], [release]); each hold ends when released or [bound] after it was raised.
 * While any hold is up, [sendRecording] keeps recordings off the server and stops a long one's request under way,
 * sending it again from the start once the holds have ended.
 */
class ServerDictationHold(
    bound: Duration = WhisperTranscriptionService.DICTATION_HOLD,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    class Hold internal constructor(internal val held: DictationHold.Hold)

    private val holds = DictationHold(CoroutineScope(dispatcher), bound)
    private val sending = MutableStateFlow(0)

    /** Whether a long recording's request is under way. */
    val longRecordingOnServer: Boolean get() = sending.value > 0

    internal val isHeld: Boolean get() = holds.isHeld

    fun raise(): Hold = Hold(holds.raise())

    /** A hold that ends every hold raised before it. */
    fun supersede(): Hold = Hold(holds.supersede())

    fun release(hold: Hold) = holds.release(hold.held)

    /** Ends every hold. */
    internal fun clear() = holds.clear()

    /**
     * [request]'s transcript, or its failure. A short recording ([long] false) is never stopped once sent. Null when the
     * recording waited for a hold and [stillWanted] then says it no longer goes to this server.
     */
    suspend fun sendRecording(long: Boolean, stillWanted: () -> Boolean, request: suspend () -> String): String? {
        var waited = false
        while (true) {
            if (holds.isHeld) {
                waited = true
                holds.awaitReleased()
            }
            if (waited && !stillWanted()) return null
            if (!long) return request()
            sendUnlessHeld(request)?.let { return it }
            waited = true
            logger.i { "a dictation stopped a long recording's request on the server" }
        }
    }

    // Null when a hold stopped the request.
    private suspend fun sendUnlessHeld(request: suspend () -> String): String? = supervisorScope {
        sending.update { it + 1 }
        val stopped = CompletableDeferred<Unit>()
        val call = async { request() }
        val watch = launch {
            holds.awaitRaised()
            if (call.isActive) {
                stopped.complete(Unit)
                call.cancel(CancellationException("a dictation needs the server"))
            }
        }
        try {
            call.await()
        } catch (e: Exception) {
            if (!stopped.isCompleted) throw e
            null
        } finally {
            watch.cancel()
            sending.update { it - 1 }
        }
    }

    private companion object {
        val logger = Logger.withTag("ServerDictationHold")
    }
}
