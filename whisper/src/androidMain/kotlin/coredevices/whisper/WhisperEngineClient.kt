package coredevices.whisper

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SharedMemory
import android.os.SystemClock
import android.system.OsConstants
import android.util.Log
import coredevices.whisper.WhisperEngineProtocol.DESCRIPTOR
import coredevices.whisper.WhisperEngineProtocol.MAX_MESSAGE_BYTES
import coredevices.whisper.WhisperEngineProtocol.MAX_TEXT_BYTES
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
import java.io.File
import java.io.IOException
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * The app-process side of the engine boundary: binds
 * [WhisperEngineService], holds the one binding for the life of this
 * process, and turns the path-based common surface into transactions.
 * The binding is never released while the process lives: the engine
 * keeps the loaded model resident exactly as an in-process engine would,
 * and a released binding would end the engine process and turn the next
 * dictation into a cold load with nothing in front of it.
 *
 * Failure classes, kept apart because callers act on them differently:
 * a transport failure (the process would not start or connect, died
 * under a call, or answered a stale handle) is a
 * [WhisperEngineUnavailableException], after which every handle is gone;
 * an engine failure with the process alive is a RuntimeException carrying
 * the engine's error text, as the in-process binding threw. Everything
 * read back from the engine process is bounded before use.
 *
 * Must be attached to the application context before any engine call;
 * unattached, the engine reads as unsupported. Binding blocks the calling
 * thread until the process is up, so no engine call may run on the main
 * thread.
 */
object WhisperEngineClient {
    private const val TAG = "WhisperEngineClient"

    /**
     * How long a bind may take before the engine is reported
     * unavailable. Process spawn measures around a second on the slowest
     * phone tried; this leaves room for a loaded system and stays inside
     * the transcription service's own init ceiling, so a wait that hits
     * it is attributed here and not to the model load.
     */
    private const val BIND_TIMEOUT_MILLIS = 5_000L

    private class Connection(val serviceConnection: ServiceConnection, val binder: IBinder)

    @Volatile
    private var appContext: Context? = null

    // bindLock is held across a whole bind, including the wait for the
    // process; stateLock only around reads and writes of the connection,
    // so a death delivered on a binder thread never waits on a bind.
    private val bindLock = Any()
    private val stateLock = Any()
    private var connection: Connection? = null

    @Volatile
    private var lastBindMillis: Long? = null

    @Volatile
    private var lastEngineError: String = ""

    private val deathListeners = CopyOnWriteArrayList<() -> Unit>()

    /** Records the application context; the first line of the app's onCreate. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    val isAttached: Boolean get() = appContext != null

    /** True while a live binding to the engine process is held. */
    val isConnected: Boolean get() = synchronized(stateLock) { connection != null }

    /**
     * Milliseconds the most recent bind took, process spawn included;
     * null before any bind. The transcription service reads it to split
     * its cold-path line.
     */
    fun lastBindMillis(): Long? = lastBindMillis

    /** The error text of the last engine failure this process received. */
    fun lastEngineError(): String = lastEngineError

    /**
     * Registers [listener] to run, on a binder thread, once per engine
     * process death. By the time it runs the binding is released and
     * every handle is gone; the next engine call binds a fresh process.
     */
    fun addDeathListener(listener: () -> Unit) {
        deathListeners += listener
    }

    /**
     * What the engine process reports about itself, or null when no
     * engine is bound and [bindIfNeeded] is false. With it true the call
     * binds, which spawns the engine process if it is not running.
     */
    fun runtime(bindIfNeeded: Boolean): WhisperEngineRuntime? {
        if (!bindIfNeeded && !isConnected) return null
        return transact(TRANSACTION_RUNTIME, "runtime", bindIfNeeded, write = {}) { reply ->
            expectOk(reply, "runtime")
            WhisperEngineRuntime(
                cpusAllowedList = reply.readBoundedString(),
                cpuset = reply.readBoundedString(),
                importance = reply.readInt().takeIf { it != UNKNOWN_INT },
                oomScoreAdj = reply.readInt().takeIf { it != UNKNOWN_INT },
                pid = reply.readInt(),
                uid = reply.readInt(),
            )
        }
    }

    /**
     * Opens [modelFile] read-only here, where the path resolves, and
     * hands the descriptor to the engine process; this process's copy is
     * closed once the call returns. Returns the engine's handle.
     */
    internal fun init(modelFile: File): Long {
        val descriptor = try {
            ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw RuntimeException("whisper init failed: cannot open the model file: ${e.message}", e)
        }
        return descriptor.use {
            transact(TRANSACTION_INIT, "init", write = { data -> descriptor.writeToParcel(data, 0) }) { reply ->
                when (val status = reply.readInt()) {
                    STATUS_OK -> reply.readLong()
                    else -> throw engineFailure("init", status, reply.readBoundedBytes(MAX_MESSAGE_BYTES).decodeToString())
                }
            }
        }
    }

    /**
     * Sends the audio as a shared-memory region of native-order floats,
     * made read-only before it crosses, and returns the engine's UTF-8
     * text. [stats], when given, receives the sample count the engine
     * reported in its first slot on every exit, as the shim fills it.
     */
    internal fun transcribe(
        handle: Long,
        pcm: FloatArray,
        threads: Int,
        language: String?,
        callId: Long,
        cpuMask: Long,
        nice: Int,
        stats: IntArray?,
    ): ByteArray {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) { "the audio transport needs API 27" }
        val byteCount = pcm.size.toLong() * Float.SIZE_BYTES
        require(byteCount <= Int.MAX_VALUE) { "audio of ${pcm.size} samples exceeds the region limit" }
        // A region must have a size; an empty clip still crosses (the
        // engine reports it as it would in process) through the
        // smallest one.
        val region = SharedMemory.create("whisper-pcm", byteCount.toInt().coerceAtLeast(Float.SIZE_BYTES))
        try {
            val mapped = region.mapReadWrite()
            try {
                mapped.order(ByteOrder.nativeOrder()).asFloatBuffer().put(pcm)
            } finally {
                SharedMemory.unmap(mapped)
            }
            // This process never reads the region again, so it can be
            // sealed read-only for the engine process; a refused seal only
            // loses that hardening and is logged.
            if (!region.setProtect(OsConstants.PROT_READ)) {
                Log.w(TAG, "could not make the audio region read-only before sending it")
            }
            return transact(
                TRANSACTION_TRANSCRIBE, "transcribe",
                write = { data ->
                    data.writeLong(handle)
                    region.writeToParcel(data, 0)
                    data.writeInt(pcm.size)
                    data.writeInt(threads)
                    data.writeString(language)
                    data.writeLong(callId)
                    data.writeLong(cpuMask)
                    data.writeInt(nice)
                },
            ) { reply ->
                val status = reply.readInt()
                val samples = reply.readInt()
                stats?.let { if (it.isNotEmpty()) it[0] = samples }
                when (status) {
                    STATUS_OK -> reply.readBoundedBytes(MAX_TEXT_BYTES)
                    else -> throw engineFailure("transcription", status, reply.readBoundedBytes(MAX_MESSAGE_BYTES).decodeToString())
                }
            }
        } finally {
            region.close()
        }
    }

    /**
     * Requests the abort of one call. Never binds and never throws: an
     * engine that is gone has nothing left to cancel, and this runs from
     * cancellation paths that must stay safe.
     */
    internal fun cancel(callId: Long) {
        try {
            transact(TRANSACTION_CANCEL, "cancel", bindIfNeeded = false, write = { data -> data.writeLong(callId) }) { reply ->
                expectOk(reply, "cancel")
            }
        } catch (e: WhisperEngineUnavailableException) {
            Log.w(TAG, "cancel of call $callId reached no engine: ${e.message}")
        }
    }

    /**
     * Releases a handle. A handle the engine process does not know, or
     * no engine process at all, means the context is already gone, so
     * neither is an error; a handle inside another call is a
     * serialization failure in this process and is thrown.
     */
    internal fun free(handle: Long) {
        try {
            transact(TRANSACTION_FREE, "free", bindIfNeeded = false, write = { data -> data.writeLong(handle) }) { reply ->
                when (val status = reply.readInt()) {
                    STATUS_OK -> Unit
                    STATUS_STALE_HANDLE -> Log.w(TAG, "free of handle $handle: ${reply.readBoundedBytes(MAX_MESSAGE_BYTES).decodeToString()}")
                    else -> throw engineFailure("free", status, reply.readBoundedBytes(MAX_MESSAGE_BYTES).decodeToString())
                }
            }
        } catch (e: WhisperEngineUnavailableException) {
            Log.w(TAG, "free of handle $handle reached no engine: ${e.message}")
        }
    }

    /** Runs the model-free speed probe in the engine process and returns its nanoseconds per block. */
    internal fun benchmark(threads: Int, cpuMask: Long, nice: Int): Long =
        transact(
            TRANSACTION_BENCHMARK, "benchmark",
            write = { data ->
                data.writeInt(threads)
                data.writeLong(cpuMask)
                data.writeInt(nice)
            },
        ) { reply ->
            when (val status = reply.readInt()) {
                STATUS_OK -> reply.readLong()
                else -> throw engineFailure("benchmark", status, reply.readBoundedBytes(MAX_MESSAGE_BYTES).decodeToString())
            }
        }

    /**
     * Maps a failure status to the exception its callers act on: a stale
     * handle means the engine process the handle came from is gone; a
     * busy handle is a serialization failure in this process; an engine
     * error keeps the engine's text and is remembered for
     * [lastEngineError].
     */
    private fun engineFailure(operation: String, status: Int, message: String): RuntimeException = when (status) {
        STATUS_STALE_HANDLE -> WhisperEngineUnavailableException("whisper $operation failed: $message")
        STATUS_BUSY -> IllegalStateException("whisper $operation failed: $message")
        STATUS_ENGINE_ERROR -> {
            lastEngineError = message
            RuntimeException("whisper $operation failed: $message")
        }
        else -> WhisperEngineUnavailableException("whisper $operation failed: unknown status $status")
    }

    private fun expectOk(reply: Parcel, operation: String) {
        val status = reply.readInt()
        if (status != STATUS_OK) {
            throw engineFailure(operation, status, reply.readBoundedBytes(MAX_MESSAGE_BYTES).decodeToString())
        }
    }

    private fun Parcel.readBoundedBytes(max: Int): ByteArray {
        val bytes = createByteArray() ?: ByteArray(0)
        if (bytes.size > max) {
            throw WhisperEngineUnavailableException("engine reply of ${bytes.size} bytes exceeds the $max byte bound")
        }
        return bytes
    }

    private fun Parcel.readBoundedString(): String? {
        val value = readString() ?: return null
        if (value.length > MAX_MESSAGE_BYTES) {
            throw WhisperEngineUnavailableException("engine reply string of ${value.length} chars exceeds the bound")
        }
        return value
    }

    /**
     * One two-way transaction against the bound engine, binding first
     * when [bindIfNeeded] is set and otherwise failing without one, so a
     * call that only makes sense against a live engine never spawns a
     * process. [write] fills the request after the interface token;
     * [read] consumes the reply after its exception marker. A dead or
     * unreachable process surfaces as [WhisperEngineUnavailableException]
     * and drops the binding, so the next binding call gets a fresh process.
     */
    private inline fun <T> transact(
        code: Int,
        operation: String,
        bindIfNeeded: Boolean = true,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        val live = connected(bindIfNeeded)
            ?: throw WhisperEngineUnavailableException("no engine process is bound for $operation")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            val handled = try {
                live.binder.transact(code, data, reply, 0)
            } catch (e: DeadObjectException) {
                onDied(live.serviceConnection)
                throw WhisperEngineUnavailableException("engine process died during $operation", e)
            } catch (e: RemoteException) {
                throw WhisperEngineUnavailableException("engine transaction for $operation failed: ${e.message}", e)
            }
            check(handled) { "engine did not recognize the $operation transaction" }
            reply.readException()
            return read(reply)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * The live connection; with [bindIfNeeded] it binds (and so spawns
     * the engine process) when there is none, otherwise it answers null.
     */
    private fun connected(bindIfNeeded: Boolean): Connection? {
        synchronized(stateLock) { connection?.let { return it } }
        if (!bindIfNeeded) return null
        synchronized(bindLock) {
            synchronized(stateLock) { connection?.let { return it } }
            val fresh = bind()
            synchronized(stateLock) { connection = fresh }
            return fresh
        }
    }

    private fun bind(): Connection {
        val context = appContext
            ?: throw WhisperEngineUnavailableException("engine client not attached to the application")
        check(Looper.myLooper() != Looper.getMainLooper()) { "engine calls must not run on the main thread" }
        val latch = CountDownLatch(1)
        var bound: IBinder? = null
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder?) {
                bound = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = onDied(this)
            override fun onBindingDied(name: ComponentName) = onDied(this)
            override fun onNullBinding(name: ComponentName) = latch.countDown()
        }
        val intent = Intent(context, WhisperEngineService::class.java)
        // BIND_IMPORTANT carries this process's foreground level to the
        // engine process, so a decode boosted here is not demoted there.
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        val started = SystemClock.elapsedRealtime()
        // From API 29 the connection callbacks run on the delivering
        // binder thread, so a busy main thread cannot delay the bind;
        // below it they run on the main looper.
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.bindService(intent, flags, Executor { it.run() }, serviceConnection)
        } else {
            context.bindService(intent, serviceConnection, flags)
        }
        if (!accepted) {
            unbindQuietly(context, serviceConnection)
            throw WhisperEngineUnavailableException("the engine service could not be bound (is it declared in the manifest?)")
        }
        if (!latch.await(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            unbindQuietly(context, serviceConnection)
            throw WhisperEngineUnavailableException("the engine process did not connect within $BIND_TIMEOUT_MILLIS ms")
        }
        val binder = bound ?: run {
            unbindQuietly(context, serviceConnection)
            throw WhisperEngineUnavailableException("the engine service returned no binder")
        }
        try {
            binder.linkToDeath({ onDied(serviceConnection) }, 0)
        } catch (e: RemoteException) {
            unbindQuietly(context, serviceConnection)
            throw WhisperEngineUnavailableException("the engine process died as it connected", e)
        }
        lastBindMillis = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "engine process bound in $lastBindMillis ms")
        return Connection(serviceConnection, binder)
    }

    /**
     * Drops the binding of a dead engine process and tells the listeners,
     * once: the death recipient, the disconnect callback and a failed
     * transaction can all report the same death, and only the first one
     * whose connection is still current acts.
     */
    private fun onDied(serviceConnection: ServiceConnection) {
        val dropped = synchronized(stateLock) {
            val current = connection
            if (current?.serviceConnection !== serviceConnection) return
            connection = null
            current
        }
        Log.w(TAG, "engine process died; binding released")
        appContext?.let { unbindQuietly(it, dropped.serviceConnection) }
        for (listener in deathListeners) {
            try {
                listener()
            } catch (t: Throwable) {
                Log.e(TAG, "engine death listener failed", t)
            }
        }
    }

    // unbindService throws when the connection was never registered or
    // is already gone; either way the outcome wanted is "not bound".
    private fun unbindQuietly(context: Context, serviceConnection: ServiceConnection) {
        try {
            context.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
        }
    }
}
