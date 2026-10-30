package com.anopticlabs.gravel.llm

/** One turn of a conversation; [role] is one of [LlmEngineLimits.ROLES]. */
data class ChatMessage(val role: String, val content: String)

/** Why a generation stopped, as the engine reports it. */
enum class StopReason { END_OF_GENERATION, TOKEN_CAP, CONTEXT_FULL, BYTE_CAP }

/**
 * What one generation reports about itself. Each field keeps -1 (null
 * for the reason) until the engine reached the point that sets it, so a
 * failed call still reports what it got to.
 */
class LlmGenerateStats {
    var promptTokens: Int = -1
    var generatedTokens: Int = -1
    var prefillMillis: Long = -1
    var decodeMillis: Long = -1
    var stopReason: StopReason? = null
}

/** The engine process's placement facts; a field is null when unreadable there or of a shape it cannot have. */
class LlmEngineRuntime(
    val cpusAllowedList: String?,
    val cpuset: String?,
    val oomScoreAdj: Int?,
    val pid: Int,
    val uid: Int,
    val openFds: Int?,
)

/**
 * The engine's process could not be reached, died under a call, or
 * answered for a handle it did not issue; every handle from it is gone.
 * An engine failure with the process alive is a plain RuntimeException.
 */
open class LlmEngineUnavailableException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** The call's deadline passed, so the client ended the engine process under it. */
class LlmEngineTimeoutException(message: String, cause: Throwable? = null) :
    LlmEngineUnavailableException(message, cause)
