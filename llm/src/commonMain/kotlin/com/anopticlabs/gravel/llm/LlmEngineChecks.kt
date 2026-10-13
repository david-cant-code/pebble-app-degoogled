package com.anopticlabs.gravel.llm

import com.anopticlabs.gravel.llm.LlmEngineLimits.MAX_MESSAGE_BYTES
import com.anopticlabs.gravel.llm.LlmEngineLimits.UNKNOWN_INT
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/*
 * The pure decisions of both sides of the engine boundary, apart from the
 * Parcel code so the host suite can feed them hostile values.
 */

/** Why a context size is refused, or null. */
fun contextRequestError(contextTokens: Int): String? =
    if (contextTokens in LlmEngineLimits.CONTEXT_TOKENS) null
    else "context of $contextTokens tokens is outside ${LlmEngineLimits.CONTEXT_TOKENS}"

/**
 * Why a conversation is refused, or null. A content carrying the marker
 * syntax of the chat templates in use would render as a turn of its own;
 * the engine refuses the exact special-token texts of the loaded model
 * behind this check.
 */
fun chatMessagesError(roles: List<String>, contents: List<String>): String? {
    if (roles.isEmpty()) return "a conversation needs at least one message"
    if (roles.size != contents.size) return "${roles.size} roles for ${contents.size} contents"
    if (roles.size > LlmEngineLimits.MAX_MESSAGES) return "${roles.size} messages exceed the ${LlmEngineLimits.MAX_MESSAGES} bound"
    roles.forEachIndexed { i, role -> if (role !in LlmEngineLimits.ROLES) return "message $i has an unknown role" }
    var total = 0L
    contents.forEachIndexed { i, content ->
        if (content.contains("<|") || content.contains("|>")) return "message $i carries a template marker"
        total += content.encodeToByteArray().size
    }
    if (total > LlmEngineLimits.MAX_CONTENT_BYTES) return "message contents of $total bytes exceed the ${LlmEngineLimits.MAX_CONTENT_BYTES} bound"
    return null
}

/** Why a generation request is refused, or null. */
fun generateRequestError(promptBytes: Int, maxTokens: Int, threads: Int): String? = when {
    promptBytes <= 0 -> "the prompt is empty"
    promptBytes > LlmEngineLimits.MAX_PROMPT_BYTES -> "prompt of $promptBytes bytes exceeds the ${LlmEngineLimits.MAX_PROMPT_BYTES} bound"
    maxTokens !in 1..LlmEngineLimits.MAX_TOKENS -> "$maxTokens tokens is outside 1..${LlmEngineLimits.MAX_TOKENS}"
    threads !in LlmEngineLimits.THREADS -> "$threads threads is outside ${LlmEngineLimits.THREADS}"
    else -> null
}

internal fun boundedMessageBytes(message: String): ByteArray =
    message.encodeToByteArray().let { if (it.size > MAX_MESSAGE_BYTES) it.copyOf(MAX_MESSAGE_BYTES) else it }

/**
 * The platform's isolated uid range (AOSP `android16-release`,
 * `core/java/android/os/Process.java`, `FIRST_ISOLATED_UID` to
 * `LAST_ISOLATED_UID`, within each user's block of 100000).
 */
fun isIsolatedUid(uid: Int): Boolean = uid % 100_000 in 99_000..99_999

/** The exception a failure status maps to; the client's KDoc lists the classes. */
internal fun exceptionForStatus(operation: String, status: Int, message: String): RuntimeException = when (status) {
    LlmEngineStatus.STALE_HANDLE -> LlmEngineUnavailableException("llm $operation failed: $message")
    LlmEngineStatus.BUSY -> IllegalStateException("llm $operation failed: $message")
    LlmEngineStatus.BAD_REQUEST -> IllegalArgumentException("llm $operation refused: $message")
    LlmEngineStatus.ENGINE_ERROR -> RuntimeException("llm $operation failed: $message")
    else -> ReplyRefusedException("llm $operation failed: unknown status $status")
}

/** A reply outside the protocol; [readPastReplyHeader] ends the engine process for it. */
internal class ReplyRefusedException(message: String) : LlmEngineUnavailableException(message)

internal fun checkReplyBound(kind: String, size: Int, max: Int) {
    if (size > max) {
        throw ReplyRefusedException("engine reply $kind of $size exceeds the $max bound")
    }
}

/** The failure for a call whose engine process went away: a timeout when the call's deadline ended it. */
internal fun engineLostException(message: String, timedOut: Boolean, cause: Throwable?): LlmEngineUnavailableException =
    if (timedOut) LlmEngineTimeoutException(message, cause) else LlmEngineUnavailableException(message, cause)

/** Time budgeted per requested token of a generation, at the slowest placement the engine is run on. */
internal val GENERATE_PER_TOKEN_BUDGET = 250.milliseconds

/**
 * How long the client waits for an answer before it ends the engine
 * process; above the slowest legitimate call of each kind, a generation's
 * growing with the tokens it asked for.
 */
internal fun transactionDeadline(transaction: LlmTransaction, maxTokens: Int = 0): Duration = when (transaction) {
    LlmTransaction.INIT -> 3.minutes
    LlmTransaction.GENERATE -> 5.minutes + GENERATE_PER_TOKEN_BUDGET * maxTokens.coerceIn(0, LlmEngineLimits.MAX_TOKENS)
    else -> 15.seconds
}

/** Whether [transaction] reaches the engine library, so the CPU floor is checked first. */
fun requiresCpuFloor(transaction: LlmTransaction): Boolean = transaction != LlmTransaction.RUNTIME

/** Whether the reply carries the generation stats between the status and the payload. */
internal fun replyCarriesStats(transaction: LlmTransaction): Boolean = transaction == LlmTransaction.GENERATE

internal const val GENERATE_STAT_SLOTS = 5

/** The platform's no-exception reply marker (LlmEngineClient.transact states the source). */
internal const val REPLY_HEADER_NO_EXCEPTION = 0

/** Why a reply header is refused, or null; the message names only the value, never the reply's text. */
internal fun replyHeaderViolation(operation: String, header: Int): String? =
    if (header == REPLY_HEADER_NO_EXCEPTION) {
        null
    } else {
        "engine reply to $operation carried header $header instead of the no-exception marker; its process is ended"
    }

/** Reads the payload past a good header; a bad header, or a [ReplyRefusedException] from [read], runs [expire] and fails the call. */
internal inline fun <T> readPastReplyHeader(operation: String, header: Int, expire: (String) -> Unit, read: () -> T): T {
    replyHeaderViolation(operation, header)?.let { message ->
        expire(message)
        throw LlmEngineUnavailableException(message)
    }
    return try {
        read()
    } catch (e: ReplyRefusedException) {
        val message = "${e.message}; its process is ended"
        expire(message)
        throw LlmEngineUnavailableException(message, e)
    }
}

/**
 * The values `oom_score_adj` can hold (AOSP bionic `android16-release`,
 * `libc/kernel/uapi/linux/oom.h`, `OOM_SCORE_ADJ_MIN` and
 * `OOM_SCORE_ADJ_MAX`); [UNKNOWN_INT] lies outside it.
 */
internal val OOM_SCORE_ADJ_RANGE = -1000..1000

internal const val MAX_CPUSET_PATH_CHARS = 64

/** The runtime report with each field kept only in the shape it can have, so it can forge nothing in the log line it lands in. */
internal fun boundedEngineRuntime(
    cpusAllowedList: String?,
    cpuset: String?,
    oomScoreAdj: Int,
    pid: Int,
    uid: Int,
    openFds: Int,
): LlmEngineRuntime = LlmEngineRuntime(
    cpusAllowedList = cpusAllowedList?.takeIf(::isCpuList),
    cpuset = cpuset?.takeIf(::isCpusetPath),
    oomScoreAdj = oomScoreAdj.takeIf { it in OOM_SCORE_ADJ_RANGE },
    pid = pid,
    uid = uid,
    openFds = openFds.takeIf { it >= 0 },
)

private fun isCpuList(text: String): Boolean =
    text.isNotEmpty() && text.all { it in '0'..'9' || it == ',' || it == '-' }

// The names the platform's task profiles join under the cpuset controller
// are letters, digits, `_` and `-`, nested with `/` (AOSP `android16-release`,
// `system/core/libprocessgroup/profiles/task_profiles.json`, the `JoinCgroup`
// actions on the `cpuset` controller); a dot is allowed for a vendor's name.
private fun isCpusetPath(text: String): Boolean =
    text.length in 1..MAX_CPUSET_PATH_CHARS && text[0] == '/' &&
        text.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '/' || it == '_' || it == '-' || it == '.' }

/**
 * The generation stats with each value kept only in the shape it can
 * have; the counts feed the speed record. A tokenizer emits at most one
 * token per prompt byte plus the vocabulary's own leading token, and a
 * refused prompt reports a count above the context, so the prompt bound
 * is its byte size.
 */
internal fun boundedGenerateStats(
    promptTokens: Long,
    generatedTokens: Long,
    prefillMicros: Long,
    decodeMicros: Long,
    stopReason: Long,
    maxTokens: Int,
    promptBytes: Int,
): LlmGenerateStats = LlmGenerateStats().apply {
    this.promptTokens = promptTokens.takeIf { it in 0..promptBytes.toLong() + 1 }?.toInt() ?: -1
    this.generatedTokens = generatedTokens.takeIf { it in 0..maxTokens.toLong() }?.toInt() ?: -1
    this.prefillMillis = prefillMicros.takeIf { it >= 0 }?.let { it / 1000 } ?: -1
    this.decodeMillis = decodeMicros.takeIf { it >= 0 }?.let { it / 1000 } ?: -1
    this.stopReason = StopReason.entries.getOrNull(stopReason.toInt().takeIf { stopReason in 0..3 } ?: -1)
}

/** Engine text with every ISO control character replaced, so it cannot split a log line. */
internal fun sanitizeEngineText(text: String): String {
    if (text.none { it.isISOControl() }) return text
    return buildString(text.length) {
        for (ch in text) append(if (ch.isISOControl()) '?' else ch)
    }
}
