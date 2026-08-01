package coredevices.coreapp

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import coredevices.util.transcription.InferenceBoost

/**
 * Android implementation of the InferenceBoost seam. utilModule resolves
 * the seam with getOrNull and falls back to a no-op, so binding this in
 * the DI graph is all it takes to give local transcription its foreground
 * service back; iOS stays on the no-op. minSdk is 26, so
 * startForegroundService is unconditionally the right entry point.
 */
class AndroidInferenceBoost(private val context: Context) : InferenceBoost {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val refCounter = BoostRefCounter(
        start = { context.startForegroundService(Intent(context, InferenceBoostService::class.java)) },
        stop = { context.stopService(Intent(context, InferenceBoostService::class.java)) },
        postToMain = { action -> mainHandler.post(action) },
    )

    override fun acquire() = refCounter.acquire()

    override fun release() = refCounter.release()
}
