package coredevices.whisper

import android.os.IBinder

/**
 * The transaction protocol between [WhisperEngineClient] in the app
 * process and [WhisperEngineService] in the engine process, in one place
 * so both sides read the same codes, layouts and bounds. Every request
 * starts with the interface token; every reply starts with the Binder
 * no-exception marker, then a status code, then the fields that status
 * carries (each handler documents its layout). The bounds apply to
 * everything the engine process sends back: that process parses
 * untrusted model bytes, so what it returns is untrusted too.
 */
internal object WhisperEngineProtocol {
    const val DESCRIPTOR = "coredevices.whisper.IWhisperEngine"

    const val TRANSACTION_INIT = IBinder.FIRST_CALL_TRANSACTION
    const val TRANSACTION_TRANSCRIBE = IBinder.FIRST_CALL_TRANSACTION + 1
    const val TRANSACTION_CANCEL = IBinder.FIRST_CALL_TRANSACTION + 2
    const val TRANSACTION_FREE = IBinder.FIRST_CALL_TRANSACTION + 3
    const val TRANSACTION_BENCHMARK = IBinder.FIRST_CALL_TRANSACTION + 4
    const val TRANSACTION_RUNTIME = IBinder.FIRST_CALL_TRANSACTION + 5

    /** The call ran; its result follows. */
    const val STATUS_OK = 0

    /** The engine reported a failure; its error text follows. */
    const val STATUS_ENGINE_ERROR = 1

    /** The handle was not issued by this engine process; a message follows. */
    const val STATUS_STALE_HANDLE = 2

    /** The handle is inside another call; a message follows. */
    const val STATUS_BUSY = 3

    /**
     * Longest engine output accepted. The watch caps a recording at 15
     * seconds and the shim caps the tokens per segment, so a transcript
     * is hundreds of bytes; this leaves an order of magnitude for a
     * decoder that repeats itself while keeping the repetition collapse
     * that runs over the text, which is quadratic in its word count,
     * bounded by the process on the other side of the boundary.
     */
    const val MAX_TEXT_BYTES = 8 * 1024

    /**
     * Longest error or diagnostic string accepted from the engine
     * process, in bytes for byte arrays and chars for strings. Text under
     * this bound is still untrusted: [sanitizeEngineText] runs over it
     * before it reaches an exception message or a log line.
     */
    const val MAX_MESSAGE_BYTES = 4 * 1024

    /** Sentinel for an integer field the engine process could not read. */
    const val UNKNOWN_INT = Int.MIN_VALUE

    /**
     * Whether the reply to [code] carries the sample count between the
     * status and the payload. Only TRANSCRIBE does, on every status, so
     * a failure written for it outside its handler must carry the slot
     * too; the client reads the layout by code, not by status.
     */
    fun replyCarriesSampleCount(code: Int): Boolean = code == TRANSACTION_TRANSCRIBE
}

/**
 * What the engine process reports about itself, read inside it: the
 * placement facts the app process cannot observe from outside, because
 * `/proc/self` there describes the wrong process.
 *
 * @property cpusAllowedList the engine process's `Cpus_allowed_list`
 *   from `/proc/self/status`, unparsed, null if unreadable there.
 * @property cpuset its cgroup cpuset path, null if unreadable there.
 * @property importance its process importance as the platform reports it
 *   to the process itself, null if that report failed.
 * @property oomScoreAdj its `/proc/self/oom_score_adj`, null if unreadable.
 * @property pid the engine process id.
 * @property uid the engine process uid; an isolated process holds one in
 *   the platform's isolated range, distinct from the app's.
 */
class WhisperEngineRuntime(
    val cpusAllowedList: String?,
    val cpuset: String?,
    val importance: Int?,
    val oomScoreAdj: Int?,
    val pid: Int,
    val uid: Int,
)
