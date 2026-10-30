package com.anopticlabs.gravel.llm

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
import android.os.StrictMode
import android.os.SystemClock
import android.util.Log
import com.anopticlabs.gravel.llm.LlmEngineLimits.MAX_MESSAGE_BYTES
import com.anopticlabs.gravel.llm.LlmEngineLimits.MAX_PROMPT_BYTES
import com.anopticlabs.gravel.llm.LlmEngineLimits.MAX_TEXT_BYTES
import com.anopticlabs.gravel.llm.LlmEngineProtocol.DESCRIPTOR
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * The app-process side of the engine boundary, in the shape of
 * `coredevices.whisper.WhisperEngineClient`: binds [LlmEngineService],
 * holds the one binding for the life of this process (a loaded model
 * stays resident until the caller frees it) and turns each call into a
 * transaction. Failure classes: a transport failure (the process would
 * not start, died under a call, or answered a stale handle) is an
 * [LlmEngineUnavailableException], after which every handle is gone (its
 * [LlmEngineTimeoutException] when the call's own deadline ended the process); a
 * request refused by a bound an IllegalArgumentException; a handle
 * inside another call an IllegalStateException; an engine failure with
 * the process alive a RuntimeException with the engine's text.
 *
 * Must be attached before any engine call. A transaction blocks the
 * calling thread, so none may run on the main thread, and carries a
 * deadline whose expiry drops the binding, which ends the isolated
 * process (the whisper client states the source) and fails the call.
 */
object LlmEngineClient {
    private const val TAG = "LlmEngineClient"

    /** How long a bind may take before the engine is reported unavailable. */
    private const val BIND_TIMEOUT_MILLIS = 5_000L

    // One connection is one engine process; an expired deadline leaves
    // the message the blocked call fails with.
    private class Connection(val serviceConnection: ServiceConnection, val binder: IBinder, val generation: Long) {
        @Volatile
        var expired: String? = null
    }

    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "llm-engine-watchdog").apply { isDaemon = true }
    }

    /** Test seam: caps every transaction deadline at this value; it can only shorten one. */
    @Volatile
    var transactionDeadlineCapForTests: Duration? = null

    @Volatile
    private var appContext: Context? = null

    // bindLock is held across a whole bind; stateLock only around reads and
    // writes of the connection, so a death report never waits on a bind.
    private val bindLock = Any()
    private val stateLock = Any()
    private var connection: Connection? = null

    @Volatile
    private var bindCount: Long = 0L

    @Volatile
    private var lastBindMillis: Long? = null

    private val deathListeners = CopyOnWriteArrayList<(Long) -> Unit>()

    /** Records the application context; called from the app's onCreate before any engine call. */
    fun attach(context: Context) {
        appContext = context.applicationContext
    }

    val isAttached: Boolean get() = appContext != null

    /** True while a live binding to the engine process is held. */
    val isConnected: Boolean get() = synchronized(stateLock) { connection != null }

    /** Milliseconds the most recent bind took, process spawn included; null before any bind. */
    fun lastBindMillis(): Long? = lastBindMillis

    /** The generation of the most recently bound engine process: one higher per bind, 0 before the first. */
    fun processGeneration(): Long = bindCount

    /** Registers [listener] to run once per engine process death, with the dead process's generation. */
    fun addDeathListener(listener: (generation: Long) -> Unit) {
        deathListeners += listener
    }

    /** The engine process's report about itself, or null when none is bound and [bindIfNeeded] is false. */
    fun runtime(bindIfNeeded: Boolean): LlmEngineRuntime? {
        if (!bindIfNeeded && !isConnected) return null
        return transact(LlmTransaction.RUNTIME, "runtime", bindIfNeeded, write = {}) { reply ->
            expectOk(reply, "runtime")
            boundedEngineRuntime(
                cpusAllowedList = reply.readBoundedString(),
                cpuset = reply.readBoundedString(),
                oomScoreAdj = reply.readInt(),
                pid = reply.readInt(),
                uid = reply.readInt(),
                openFds = reply.readInt(),
            )
        }
    }

    /** Loads [modelFile] through a descriptor with a context of [contextTokens]; the caller releases the handle with [free]. */
    fun init(modelFile: File, contextTokens: Int): Long {
        contextRequestError(contextTokens)?.let { throw IllegalArgumentException("llm init refused: $it") }
        val descriptor = try {
            ParcelFileDescriptor.open(modelFile, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: IOException) {
            throw RuntimeException("llm init failed: cannot open the model file: ${e.message}", e)
        }
        return descriptor.use {
            transact(
                LlmTransaction.INIT, "init",
                write = { data ->
                    descriptor.writeToParcel(data, 0)
                    data.writeInt(contextTokens)
                },
            ) { reply ->
                when (val status = reply.readInt()) {
                    LlmEngineStatus.OK -> reply.readLong()
                    else -> throw exceptionForStatus("init", status, reply.readBoundedMessage())
                }
            }
        }
    }

    /**
     * Renders [messages] with the engine's built-in formatter for the
     * template family it detects on the model (llm_jni.cpp states the
     * source), the assistant turn opened, for [generate].
     */
    fun formatChat(handle: Long, messages: List<ChatMessage>): String {
        val roles = messages.map { it.role }
        chatMessagesError(roles, messages.map { it.content })?.let { throw IllegalArgumentException("llm formatChat refused: $it") }
        val contents = messages.map { it.content.encodeToByteArray() }
        return transact(
            LlmTransaction.FORMAT_CHAT, "formatChat",
            write = { data ->
                data.writeLong(handle)
                data.writeInt(messages.size)
                for (i in messages.indices) {
                    data.writeByteArray(roles[i].encodeToByteArray())
                    data.writeByteArray(contents[i])
                }
            },
        ) { reply ->
            when (val status = reply.readInt()) {
                LlmEngineStatus.OK -> reply.readBoundedBytes(MAX_PROMPT_BYTES).decodeToString()
                else -> throw exceptionForStatus("formatChat", status, reply.readBoundedMessage())
            }
        }
    }

    /**
     * One greedy generation of at most [maxTokens] tokens from [prompt] on
     * [threads] engine threads. [callId] names the call for [cancel] and
     * must be unique among the calls in flight; [cpuMask] (bit i = CPU i)
     * and [nice] are the scheduling the engine threads of this call run
     * under when the kernel accepts them (the engine logs a refused value
     * and ignores it), 0 leaving each alone, and each distinct combination
     * with [threads] keeps its worker threads until [free]; [stats] is
     * filled on every exit. A prompt the context cannot hold, and a
     * cancellation, are engine failures.
     */
    fun generate(
        handle: Long,
        prompt: String,
        maxTokens: Int,
        threads: Int,
        callId: Long,
        cpuMask: Long = 0L,
        nice: Int = 0,
        stats: LlmGenerateStats? = null,
    ): String {
        val promptBytes = prompt.encodeToByteArray()
        generateRequestError(promptBytes.size, maxTokens, threads)?.let { throw IllegalArgumentException("llm generate refused: $it") }
        return transact(
            LlmTransaction.GENERATE, "generate", maxTokens = maxTokens,
            write = { data ->
                data.writeLong(handle)
                data.writeByteArray(promptBytes)
                data.writeInt(maxTokens)
                data.writeInt(threads)
                data.writeLong(callId)
                data.writeLong(cpuMask)
                data.writeInt(nice)
            },
        ) { reply ->
            val status = reply.readInt()
            val slots = reply.createLongArray() ?: LongArray(0)
            checkReplyBound("stats", slots.size, GENERATE_STAT_SLOTS)
            val padded = slots.copyOf(GENERATE_STAT_SLOTS).also { for (i in slots.size until GENERATE_STAT_SLOTS) it[i] = -1L }
            stats?.let { into ->
                val bounded = boundedGenerateStats(padded[0], padded[1], padded[2], padded[3], padded[4], maxTokens, promptBytes.size)
                into.promptTokens = bounded.promptTokens
                into.generatedTokens = bounded.generatedTokens
                into.prefillMillis = bounded.prefillMillis
                into.decodeMillis = bounded.decodeMillis
                into.stopReason = bounded.stopReason
            }
            when (status) {
                LlmEngineStatus.OK -> reply.readBoundedBytes(MAX_TEXT_BYTES).decodeToString()
                else -> throw exceptionForStatus("generate", status, reply.readBoundedMessage())
            }
        }
    }

    /** Requests the abort of one call; never binds and never throws. */
    fun cancel(callId: Long) {
        try {
            transact(LlmTransaction.CANCEL, "cancel", bindIfNeeded = false, write = { data -> data.writeLong(callId) }) { reply ->
                expectOk(reply, "cancel")
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "cancel of call $callId did not reach the engine: ${e.message}")
        }
    }

    /** Releases a handle; an unknown handle or no engine process means the model is already gone. Safe with 0. */
    fun free(handle: Long) {
        if (handle == 0L) return
        try {
            transact(LlmTransaction.FREE, "free", bindIfNeeded = false, write = { data -> data.writeLong(handle) }) { reply ->
                when (val status = reply.readInt()) {
                    LlmEngineStatus.OK -> Unit
                    LlmEngineStatus.STALE_HANDLE -> Log.w(TAG, "free of handle $handle: ${reply.readBoundedMessage()}")
                    else -> throw exceptionForStatus("free", status, reply.readBoundedMessage())
                }
            }
        } catch (e: LlmEngineUnavailableException) {
            Log.w(TAG, "free of handle $handle reached no engine: ${e.message}")
        }
    }

    /** Ends the bound engine process now, as a missed deadline does; every handle from it is gone. */
    fun endProcess(reason: String) {
        val live = synchronized(stateLock) { connection } ?: return
        expire(live, "engine process ended: $reason")
    }

    private fun expectOk(reply: Parcel, operation: String) {
        val status = reply.readInt()
        if (status != LlmEngineStatus.OK) {
            throw exceptionForStatus(operation, status, reply.readBoundedMessage())
        }
    }

    /** An engine message: bounded, decoded, and stripped of control characters before it can reach a log line. */
    private fun Parcel.readBoundedMessage(): String =
        sanitizeEngineText(readBoundedBytes(MAX_MESSAGE_BYTES).decodeToString())

    private fun Parcel.readBoundedBytes(max: Int): ByteArray {
        val bytes = createByteArray() ?: ByteArray(0)
        checkReplyBound("bytes", bytes.size, max)
        return bytes
    }

    private fun Parcel.readBoundedString(): String? {
        val value = readString() ?: return null
        checkReplyBound("chars", value.length, MAX_MESSAGE_BYTES)
        return sanitizeEngineText(value)
    }

    /**
     * One two-way transaction, binding first when [bindIfNeeded] is set;
     * [write] fills the request after the interface token, [read] consumes
     * the reply after its header.
     */
    private inline fun <T> transact(
        transaction: LlmTransaction,
        operation: String,
        bindIfNeeded: Boolean = true,
        maxTokens: Int = 0,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T {
        check(Looper.myLooper() != Looper.getMainLooper()) { "engine calls must not run on the main thread" }
        val live = connected(bindIfNeeded)
            ?: throw LlmEngineUnavailableException("no engine process is bound for $operation")
        val deadline = transactionDeadline(transaction, maxTokens).let { bound ->
            transactionDeadlineCapForTests?.let { cap -> minOf(bound, cap) } ?: bound
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        // The reply header is read as one int, never through the
        // framework's reader; the call runs under a lax StrictMode policy
        // and the engine notes no app op before its header
        // (`coredevices.whisper.WhisperEngineClient.transact` states why
        // and cites the platform sources).
        val policy = StrictMode.getThreadPolicy()
        // This transaction's own: a deadline another call on the same process missed is not this one's.
        val timedOut = AtomicBoolean(false)
        val expiry = watchdog.schedule(
            {
                timedOut.set(true)
                expire(live, "engine did not answer $operation within $deadline; its process is ended")
            },
            deadline.inWholeMilliseconds, TimeUnit.MILLISECONDS,
        )
        try {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX)
            data.writeInterfaceToken(DESCRIPTOR)
            write(data)
            val handled = try {
                live.binder.transact(LlmEngineProtocol.code(transaction), data, reply, 0)
            } catch (e: DeadObjectException) {
                onDied(live.serviceConnection)
                throw engineLostException(live.expired ?: "engine process died during $operation", timedOut.get(), e)
            } catch (e: RemoteException) {
                throw engineLostException("engine transaction for $operation failed: ${e.message}", timedOut.get(), e)
            }
            check(handled) { "engine did not recognize the $operation transaction" }
            return readPastReplyHeader(operation, reply.readInt(), expire = { message -> expire(live, message) }) {
                read(reply)
            }
        } finally {
            expiry.cancel(false)
            StrictMode.setThreadPolicy(policy)
            data.recycle()
            reply.recycle()
        }
    }

    /** Treats the engine process behind [live] as dead, for [message]; dropping the binding ends it. */
    private fun expire(live: Connection, message: String) {
        live.expired = message
        Log.w(TAG, message)
        onDied(live.serviceConnection)
    }

    /** The live connection; with [bindIfNeeded] it binds when there is none, otherwise it answers null. */
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
            ?: throw LlmEngineUnavailableException("engine client not attached to the application")
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
        val intent = Intent(context, LlmEngineService::class.java)
        // BIND_IMPORTANT carries this process's foreground level to the engine process.
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
        val started = SystemClock.elapsedRealtime()
        // From API 29 the callbacks run on the delivering binder thread, off the main thread.
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.bindService(intent, flags, Executor { it.run() }, serviceConnection)
        } else {
            context.bindService(intent, serviceConnection, flags)
        }
        if (!accepted) {
            unbindQuietly(context, serviceConnection)
            throw LlmEngineUnavailableException("the engine service could not be bound (is it declared in the manifest?)")
        }
        if (!latch.await(BIND_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            unbindQuietly(context, serviceConnection)
            throw LlmEngineUnavailableException("the engine process did not connect within $BIND_TIMEOUT_MILLIS ms")
        }
        val binder = bound ?: run {
            unbindQuietly(context, serviceConnection)
            throw LlmEngineUnavailableException("the engine service returned no binder")
        }
        try {
            binder.linkToDeath({ onDied(serviceConnection) }, 0)
        } catch (e: RemoteException) {
            unbindQuietly(context, serviceConnection)
            throw LlmEngineUnavailableException("the engine process died as it connected", e)
        }
        lastBindMillis = SystemClock.elapsedRealtime() - started
        val generation = ++bindCount
        Log.i(TAG, "engine process bound in $lastBindMillis ms (generation $generation)")
        return Connection(serviceConnection, binder, generation)
    }

    /** Drops the binding of a dead engine process and tells the listeners, once per process. */
    private fun onDied(serviceConnection: ServiceConnection) {
        val dropped = synchronized(stateLock) {
            val current = connection
            if (current?.serviceConnection !== serviceConnection) return
            connection = null
            current
        }
        Log.w(TAG, "engine process died (generation ${dropped.generation}); binding released")
        appContext?.let { unbindQuietly(it, dropped.serviceConnection) }
        for (listener in deathListeners) {
            try {
                listener(dropped.generation)
            } catch (t: Throwable) {
                Log.e(TAG, "engine death listener failed", t)
            }
        }
    }

    // unbindService throws for a connection already gone; the outcome wanted is "not bound".
    private fun unbindQuietly(context: Context, serviceConnection: ServiceConnection) {
        try {
            context.unbindService(serviceConnection)
        } catch (_: IllegalArgumentException) {
        }
    }
}
