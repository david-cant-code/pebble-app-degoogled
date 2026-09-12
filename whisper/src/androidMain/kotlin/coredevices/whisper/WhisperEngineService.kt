package coredevices.whisper

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SharedMemory
import android.util.Log
import coredevices.whisper.WhisperEngineProtocol.DESCRIPTOR
import coredevices.whisper.WhisperEngineProtocol.MAX_MESSAGE_BYTES
import coredevices.whisper.WhisperEngineProtocol.STATUS_BUSY
import coredevices.whisper.WhisperEngineProtocol.STATUS_ENGINE_ERROR
import coredevices.whisper.WhisperEngineProtocol.STATUS_OK
import coredevices.whisper.WhisperEngineProtocol.STATUS_STALE_HANDLE
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_BENCHMARK
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_CANCEL
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_FREE
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_INIT
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_RUNTIME
import coredevices.whisper.WhisperEngineProtocol.TRANSACTION_TRANSCRIBE
import coredevices.whisper.WhisperEngineProtocol.UNKNOWN_INT
import coredevices.whisper.WhisperEngineProtocol.replyCarriesSampleCount
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The engine's process. The app manifest declares this service with
 * `android:isolatedProcess`: a separate zero-permission uid with no path
 * into the app's files, no network and no other permission, so a
 * memory-safety bug in the model parser or the decoder, reached through
 * a model file, is contained to this process. Nothing here opens a path:
 * the model arrives as a file descriptor and the audio as a shared-memory
 * region, both passed by [WhisperEngineClient], and the engine is the
 * only thing that runs here.
 *
 * The platform derives this process's real name from the class name
 * (`<applicationId>:whisper:coredevices.whisper.WhisperEngineService`),
 * so the manifest's `android:process` value is a prefix of it, never
 * equal to it; a per-process manifest attribute keyed to the declared
 * name misses and belongs on the application element instead.
 */
class WhisperEngineService : Service() {
    // Built with the service: onBind is its only entry point and the
    // binder holds no state worth deferring.
    private val binder = EngineBinder(this)

    override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * True inside an isolated process. Every process of the app instantiates
 * the app's Application class, the engine process included, and the app
 * start-up (DI graph, watch connection, background work) must not run
 * there: it has no file, network or permission access, and the engine is
 * all that process is for. From API 28 the platform answers directly;
 * below it the answer is the uid range the platform reserves for
 * isolated processes (AOSP `android.os.Process`, `FIRST_ISOLATED_UID` to
 * `LAST_ISOLATED_UID`, per user), which is what `isIsolated` checks.
 */
fun runningInIsolatedProcess(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Process.isIsolated()
    } else {
        Process.myUid() % 100_000 in 99_000..99_999
    }

/**
 * Serves [WhisperEngineProtocol] over the JNI shim. Each layer holds on
 * its own: only the app's own uid may call (the manifest's exported=false
 * is the layer in front of it); a handle is dereferenced only if this
 * process issued it, so a value from before an engine restart is refused;
 * a handle inside one call refuses a second call and a free, which is
 * what makes a use-after-free impossible if the app process ever fails
 * to serialize its calls; and the CPU feature floor is re-checked here
 * before the engine library is loaded, so a client that skipped its own
 * check gets an error rather than an illegal instruction. A failure in a
 * handler is reported in the reply, never thrown: an exception escaping
 * onTransact ends this process.
 */
private class EngineBinder(private val service: Service) : Binder() {

    private companion object {
        const val TAG = "WhisperEngineService"
    }

    /** Every handle this process has issued and not freed, with its busy flag. */
    private val handles = ConcurrentHashMap<Long, AtomicBoolean>()

    // Cached after the first successful check; the probe library stays
    // loaded either way and the answer cannot change for a process.
    @Volatile
    private var cpuChecked = false

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code < TRANSACTION_INIT || code > TRANSACTION_RUNTIME) {
            return super.onTransact(code, data, reply, flags)
        }
        data.enforceInterface(DESCRIPTOR)
        requireAppCaller()
        // Every transaction is two-way; a one-way delivery has no reply to
        // fill and nothing to do.
        val out = reply ?: return true
        out.writeNoException()
        try {
            when (code) {
                TRANSACTION_INIT -> init(data, out)
                TRANSACTION_TRANSCRIBE -> transcribe(data, out)
                TRANSACTION_CANCEL -> cancel(data, out)
                TRANSACTION_FREE -> free(data, out)
                TRANSACTION_BENCHMARK -> benchmark(data, out)
                TRANSACTION_RUNTIME -> runtime(out)
            }
        } catch (t: Throwable) {
            // The handler may have written part of a reply; start over
            // with the failure, in the layout the client reads for this
            // code, so the app process never reads a torn one.
            Log.e(TAG, "engine transaction $code failed", t)
            out.setDataSize(0)
            out.setDataPosition(0)
            out.writeNoException()
            val message = "${t::class.java.simpleName}: ${t.message}"
            if (replyCarriesSampleCount(code)) {
                writeTranscribeFailure(out, STATUS_ENGINE_ERROR, message)
            } else {
                writeFailure(out, STATUS_ENGINE_ERROR, message)
            }
        }
        return true
    }

    // The isolated uid is this process's own; the app's uid is what the
    // package holds, which is who binds. Anyone else reaching this binder
    // is a platform bug or a repackaged build, and gets nothing.
    private fun requireAppCaller() {
        val caller = getCallingUid()
        val app = service.applicationInfo.uid
        if (caller != app) {
            throw SecurityException("engine call from uid $caller; only the app uid $app may call")
        }
    }

    private fun requireCpuFloor() {
        if (cpuChecked) return
        if (!WhisperCpuJNI.nativeIsWhisperSupported()) {
            throw IllegalStateException("CPU is below the engine's feature floor")
        }
        cpuChecked = true
    }

    /**
     * INIT: in = the model as a ParcelFileDescriptor; out = STATUS_OK and
     * the handle, or a failure status and its message. The descriptor
     * arrives duplicated into this process and is detached to the shim,
     * which owns and closes it from the moment the call is entered.
     */
    private fun init(data: Parcel, reply: Parcel) {
        requireCpuFloor()
        val descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)
        val fd = descriptor.detachFd()
        val handle = try {
            WhisperJNI.nativeInitFd(fd)
        } catch (t: Throwable) {
            // The fd is detached before the shim's class loads its library,
            // so a load that fails throws with the fd still this process's
            // to close; the shim itself never throws once entered.
            ParcelFileDescriptor.adoptFd(fd).close()
            throw t
        }
        if (handle == 0L) {
            writeFailure(reply, STATUS_ENGINE_ERROR, lastError())
            return
        }
        handles[handle] = AtomicBoolean(false)
        reply.writeInt(STATUS_OK)
        reply.writeLong(handle)
    }

    /**
     * TRANSCRIBE: in = handle, the audio as a SharedMemory region of
     * native-order floats, the sample count, threads, language (nullable),
     * call id, cpu mask, nice; out = status, the sample count the engine
     * was given (-1 if it never was), then the text bytes for STATUS_OK or
     * the message bytes for any failure. The region is mapped read-only,
     * copied out and closed before the engine runs.
     */
    private fun transcribe(data: Parcel, reply: Parcel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            writeTranscribeFailure(reply, STATUS_ENGINE_ERROR, "the audio transport needs API 27")
            return
        }
        val handle = data.readLong()
        val region = SharedMemory.CREATOR.createFromParcel(data)
        try {
            val samples = data.readInt()
            val threads = data.readInt()
            val language = data.readString()
            val callId = data.readLong()
            val cpuMask = data.readLong()
            val nice = data.readInt()
            val busy = handles[handle]
            if (busy == null) {
                writeTranscribeFailure(reply, STATUS_STALE_HANDLE, "handle $handle was not issued by this engine process")
                return
            }
            if (!busy.compareAndSet(false, true)) {
                writeTranscribeFailure(reply, STATUS_BUSY, "handle $handle is inside another call")
                return
            }
            try {
                if (samples < 0 || samples.toLong() * Float.SIZE_BYTES > region.size) {
                    writeTranscribeFailure(
                        reply, STATUS_ENGINE_ERROR,
                        "audio of $samples samples does not fit its ${region.size} byte region",
                    )
                    return
                }
                val pcm = FloatArray(samples)
                val mapped = region.mapReadOnly()
                try {
                    mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().get(pcm)
                } finally {
                    SharedMemory.unmap(mapped)
                }
                val stats = intArrayOf(-1)
                val text = WhisperJNI.nativeTranscribe(handle, pcm, threads, language, callId, cpuMask, nice, stats)
                if (text == null) {
                    reply.writeInt(STATUS_ENGINE_ERROR)
                    reply.writeInt(stats[0])
                    reply.writeByteArray(lastError().toByteArray())
                } else {
                    reply.writeInt(STATUS_OK)
                    reply.writeInt(stats[0])
                    reply.writeByteArray(text)
                }
            } finally {
                busy.set(false)
            }
        } finally {
            region.close()
        }
    }

    /** CANCEL: in = call id; out = STATUS_OK. Safe against any id, as in the shim. */
    private fun cancel(data: Parcel, reply: Parcel) {
        WhisperJNI.nativeCancel(data.readLong())
        reply.writeInt(STATUS_OK)
    }

    /**
     * FREE: in = handle; out = STATUS_OK, or STATUS_STALE_HANDLE or
     * STATUS_BUSY with a message. The handle is removed from the live set
     * while marked busy, so no call can take it between the check and
     * the free.
     */
    private fun free(data: Parcel, reply: Parcel) {
        val handle = data.readLong()
        val busy = handles[handle]
        if (busy == null) {
            writeFailure(reply, STATUS_STALE_HANDLE, "handle $handle was not issued by this engine process")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            writeFailure(reply, STATUS_BUSY, "handle $handle is inside another call")
            return
        }
        handles.remove(handle)
        WhisperJNI.nativeFree(handle)
        reply.writeInt(STATUS_OK)
    }

    /** BENCHMARK: in = threads, cpu mask, nice; out = STATUS_OK and the nanoseconds, or a failure and its message. */
    private fun benchmark(data: Parcel, reply: Parcel) {
        requireCpuFloor()
        val threads = data.readInt()
        val cpuMask = data.readLong()
        val nice = data.readInt()
        val ns = WhisperJNI.nativeBenchmark(threads, cpuMask, nice)
        if (ns <= 0L) {
            writeFailure(reply, STATUS_ENGINE_ERROR, lastError())
            return
        }
        reply.writeInt(STATUS_OK)
        reply.writeLong(ns)
    }

    /**
     * RUNTIME: no input; out = STATUS_OK, then the fields of
     * [WhisperEngineRuntime] in declaration order, with null strings as
     * Parcel nulls and unknown integers as [UNKNOWN_INT]. Every read is
     * fenced: a field this process cannot read is unknown, never a guess.
     */
    private fun runtime(reply: Parcel) {
        reply.writeInt(STATUS_OK)
        reply.writeString(statusField("Cpus_allowed_list"))
        reply.writeString(readTrimmed("/proc/self/cpuset"))
        reply.writeInt(importance() ?: UNKNOWN_INT)
        reply.writeInt(readTrimmed("/proc/self/oom_score_adj")?.toIntOrNull() ?: UNKNOWN_INT)
        reply.writeInt(Process.myPid())
        reply.writeInt(Process.myUid())
        reply.writeInt(openFds() ?: UNKNOWN_INT)
    }

    /** This process's open descriptors, the listing's own included, or null if unreadable. */
    private fun openFds(): Int? = runCatching { File("/proc/self/fd").list()?.size }.getOrNull()

    private fun writeFailure(reply: Parcel, status: Int, message: String) {
        reply.writeInt(status)
        reply.writeByteArray(message.toByteArray().let { if (it.size > MAX_MESSAGE_BYTES) it.copyOf(MAX_MESSAGE_BYTES) else it })
    }

    private fun writeTranscribeFailure(reply: Parcel, status: Int, message: String) {
        reply.writeInt(status)
        reply.writeInt(-1)
        reply.writeByteArray(message.toByteArray().let { if (it.size > MAX_MESSAGE_BYTES) it.copyOf(MAX_MESSAGE_BYTES) else it })
    }

    private fun lastError(): String = WhisperJNI.nativeGetLastError().decodeToString()

    private fun statusField(field: String): String? = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$field:") }?.substringAfter(':')?.trim()
        }
    }.getOrNull()

    private fun readTrimmed(path: String): String? = runCatching {
        File(path).readText().trim().ifEmpty { null }
    }.getOrNull()

    private fun importance(): Int? = runCatching {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        info.importance
    }.getOrNull()
}
