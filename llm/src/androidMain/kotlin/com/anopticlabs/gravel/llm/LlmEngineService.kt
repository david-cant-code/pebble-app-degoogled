package com.anopticlabs.gravel.llm

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.anopticlabs.gravel.llm.LlmEngineLimits.MAX_PROMPT_BYTES
import com.anopticlabs.gravel.llm.LlmEngineLimits.MAX_TEXT_BYTES
import com.anopticlabs.gravel.llm.LlmEngineLimits.UNKNOWN_INT
import com.anopticlabs.gravel.llm.LlmEngineProtocol.DESCRIPTOR
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The text engine's process, declared in this module's manifest with
 * `android:isolatedProcess` like the speech engine's
 * (`coredevices.whisper.WhisperEngineService` states what the isolation
 * buys and how the platform names the process; here it shows as
 * `<applicationId>:llm:com.anopticlabs.gravel.llm.LlmEngineService`). A
 * memory-safety bug reached through a model file or a prompt is contained
 * to it; nothing here opens a path. A process of its own so a generation
 * that runs for minutes never sits in front of a watch dictation.
 */
class LlmEngineService : Service() {
    private val binder = EngineBinder(this)

    override fun onBind(intent: Intent?): IBinder = binder
}

/**
 * Serves [LlmEngineProtocol] over the JNI shim. Each layer holds on its
 * own: only the app's uid may transact (the manifest's exported=false is
 * the layer in front); a handle is dereferenced only if this process
 * issued it; a handle inside one call refuses a second call and a free;
 * every request is checked against [LlmEngineLimits] before the shim sees
 * it; and the CPU floor is re-checked before the engine library loads. A
 * failure in a handler is reported in the reply, never thrown (an
 * exception out of onTransact reaches the client as a transport failure).
 */
private class EngineBinder(private val service: Service) : Binder() {

    private companion object {
        const val TAG = "LlmEngineService"
    }

    /** Every handle this process has issued and not freed, with its busy flag. */
    private val handles = ConcurrentHashMap<Long, AtomicBoolean>()

    @Volatile
    private var cpuChecked = false

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val transaction = LlmEngineProtocol.transaction(code) ?: return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        requireAppCaller()
        // Every transaction is two-way; a one-way delivery does nothing.
        // The header goes first, before any handler work, and the catch
        // below writes it again after; no handler may note an app op
        // before it (LlmEngineClient.transact states why).
        val out = reply ?: return true
        out.writeNoException()
        try {
            if (requiresCpuFloor(transaction)) requireCpuFloor()
            when (transaction) {
                LlmTransaction.INIT -> init(data, out)
                LlmTransaction.FORMAT_CHAT -> formatChat(data, out)
                LlmTransaction.GENERATE -> generate(data, out)
                LlmTransaction.CANCEL -> cancel(data, out)
                LlmTransaction.FREE -> free(data, out)
                LlmTransaction.RUNTIME -> runtime(out)
            }
        } catch (t: Throwable) {
            // Start the reply over with the failure, in the layout the
            // client reads for this transaction.
            Log.e(TAG, "engine transaction $transaction failed", t)
            out.setDataSize(0)
            out.setDataPosition(0)
            out.writeNoException()
            val message = "${t::class.java.simpleName}: ${t.message}"
            if (replyCarriesStats(transaction)) {
                writeGenerateFailure(out, LlmEngineStatus.ENGINE_ERROR, LongArray(GENERATE_STAT_SLOTS) { -1L }, message)
            } else {
                writeFailure(out, LlmEngineStatus.ENGINE_ERROR, message)
            }
        }
        return true
    }

    private fun requireAppCaller() {
        val caller = getCallingUid()
        val app = service.applicationInfo.uid
        if (caller != app) {
            throw SecurityException("engine call from uid $caller; only the app uid $app may call")
        }
    }

    // Before any transaction that touches the engine library (requiresCpuFloor).
    private fun requireCpuFloor() {
        if (cpuChecked) return
        if (!LlmCpuJNI.nativeIsLlmSupported()) {
            throw IllegalStateException("CPU is below the engine's feature floor")
        }
        cpuChecked = true
    }

    /**
     * INIT: in = the model as a ParcelFileDescriptor, the context size;
     * out = OK and the handle. The descriptor is detached to the shim,
     * which owns it from the moment the call is entered.
     */
    private fun init(data: Parcel, reply: Parcel) {
        val descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(data)
        val contextTokens = data.readInt()
        contextRequestError(contextTokens)?.let { message ->
            descriptor.close()
            writeFailure(reply, LlmEngineStatus.BAD_REQUEST, message)
            return
        }
        val fd = descriptor.detachFd()
        val handle = try {
            LlmJNI.nativeInitFd(fd, contextTokens)
        } catch (t: Throwable) {
            // A library load that throws leaves the fd this process's to
            // close; the shim never throws once entered.
            ParcelFileDescriptor.adoptFd(fd).close()
            throw t
        }
        if (handle == 0L) {
            writeFailure(reply, LlmEngineStatus.ENGINE_ERROR, lastError())
            return
        }
        handles[handle] = AtomicBoolean(false)
        reply.writeInt(LlmEngineStatus.OK)
        reply.writeLong(handle)
    }

    /** FORMAT_CHAT: in = handle, message count, then per message the role bytes and the content bytes; out = OK and the prompt bytes. */
    private fun formatChat(data: Parcel, reply: Parcel) {
        val handle = data.readLong()
        val count = data.readInt()
        if (count !in 1..LlmEngineLimits.MAX_MESSAGES) {
            writeFailure(reply, LlmEngineStatus.BAD_REQUEST, "$count messages is outside 1..${LlmEngineLimits.MAX_MESSAGES}")
            return
        }
        val roleBytes = ArrayList<ByteArray>(count)
        val contentBytes = ArrayList<ByteArray>(count)
        repeat(count) {
            roleBytes += data.createByteArray() ?: ByteArray(0)
            contentBytes += data.createByteArray() ?: ByteArray(0)
        }
        chatMessagesError(roleBytes.map { it.decodeToString() }, contentBytes.map { it.decodeToString() })?.let { message ->
            writeFailure(reply, LlmEngineStatus.BAD_REQUEST, message)
            return
        }
        withHandle(handle, reply) {
            val prompt = LlmJNI.nativeFormatChat(handle, roleBytes.toTypedArray(), contentBytes.toTypedArray())
            when {
                prompt == null -> writeFailure(reply, LlmEngineStatus.ENGINE_ERROR, lastError())
                prompt.size > MAX_PROMPT_BYTES ->
                    writeFailure(reply, LlmEngineStatus.ENGINE_ERROR, "rendered prompt of ${prompt.size} bytes exceeds the $MAX_PROMPT_BYTES bound")
                else -> {
                    reply.writeInt(LlmEngineStatus.OK)
                    reply.writeByteArray(prompt)
                }
            }
        }
    }

    /**
     * GENERATE: in = handle, the prompt bytes, max tokens, threads, call
     * id, cpu mask, nice; out = status, the five stat slots (-1 where the
     * shim never got that far), then the text or the message bytes.
     */
    private fun generate(data: Parcel, reply: Parcel) {
        val handle = data.readLong()
        val prompt = data.createByteArray() ?: ByteArray(0)
        val maxTokens = data.readInt()
        val threads = data.readInt()
        val callId = data.readLong()
        val cpuMask = data.readLong()
        val nice = data.readInt()
        val stats = LongArray(GENERATE_STAT_SLOTS) { -1L }
        generateRequestError(prompt.size, maxTokens, threads)?.let { message ->
            writeGenerateFailure(reply, LlmEngineStatus.BAD_REQUEST, stats, message)
            return
        }
        withHandle(handle, reply, failure = { status, message -> writeGenerateFailure(reply, status, stats, message) }) {
            val text = LlmJNI.nativeGenerate(handle, prompt, maxTokens, threads, callId, cpuMask, nice, stats)
            when {
                text == null -> writeGenerateFailure(reply, LlmEngineStatus.ENGINE_ERROR, stats, lastError())
                text.size > MAX_TEXT_BYTES ->
                    writeGenerateFailure(reply, LlmEngineStatus.ENGINE_ERROR, stats, "generation of ${text.size} bytes exceeds the $MAX_TEXT_BYTES bound")
                else -> {
                    reply.writeInt(LlmEngineStatus.OK)
                    reply.writeLongArray(stats)
                    reply.writeByteArray(text)
                }
            }
        }
    }

    /** CANCEL: in = call id; out = OK. Safe against any id, as in the shim. */
    private fun cancel(data: Parcel, reply: Parcel) {
        LlmJNI.nativeCancel(data.readLong())
        reply.writeInt(LlmEngineStatus.OK)
    }

    /** FREE: in = handle; out = OK, or STALE_HANDLE or BUSY with a message. Removed from the live set while busy. */
    private fun free(data: Parcel, reply: Parcel) {
        val handle = data.readLong()
        withHandle(handle, reply) {
            handles.remove(handle)
            LlmJNI.nativeFree(handle)
            reply.writeInt(LlmEngineStatus.OK)
        }
    }

    /** RUNTIME: no input; out = OK, then the fields of [LlmEngineRuntime] in declaration order, unknown integers as [UNKNOWN_INT]. */
    private fun runtime(reply: Parcel) {
        reply.writeInt(LlmEngineStatus.OK)
        reply.writeString(readCpusAllowedList())
        reply.writeString(readCpuset())
        reply.writeInt(readOomScoreAdj() ?: UNKNOWN_INT)
        reply.writeInt(Process.myPid())
        reply.writeInt(Process.myUid())
        reply.writeInt(openFds() ?: UNKNOWN_INT)
    }

    /** Runs [block] with [handle] marked busy, or writes the refusal (not issued here, or inside another call). */
    private inline fun withHandle(
        handle: Long,
        reply: Parcel,
        failure: (status: Int, message: String) -> Unit = { status, message -> writeFailure(reply, status, message) },
        block: () -> Unit,
    ) {
        val busy = handles[handle]
        if (busy == null) {
            failure(LlmEngineStatus.STALE_HANDLE, "handle $handle was not issued by this engine process")
            return
        }
        if (!busy.compareAndSet(false, true)) {
            failure(LlmEngineStatus.BUSY, "handle $handle is inside another call")
            return
        }
        try {
            block()
        } finally {
            busy.set(false)
        }
    }

    /** This process's open descriptors, the listing's own included, or null if unreadable. */
    private fun openFds(): Int? = runCatching { File("/proc/self/fd").list()?.size }.getOrNull()

    private fun writeFailure(reply: Parcel, status: Int, message: String) {
        reply.writeInt(status)
        reply.writeByteArray(boundedMessageBytes(message))
    }

    private fun writeGenerateFailure(reply: Parcel, status: Int, stats: LongArray, message: String) {
        reply.writeInt(status)
        reply.writeLongArray(stats)
        reply.writeByteArray(boundedMessageBytes(message))
    }

    private fun lastError(): String = LlmJNI.nativeGetLastError().decodeToString()
}
