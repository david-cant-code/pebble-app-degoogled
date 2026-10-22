package com.anopticlabs.gravel.llm

/**
 * The bounds of the engine boundary, applied on both sides of it: what
 * the engine process returns is untrusted, and what the app sends it is
 * refused past a bound rather than handed to the engine.
 */
object LlmEngineLimits {
    /** Rendered verbatim by the chat template, so anything else is refused. */
    val ROLES = setOf("system", "user", "assistant")

    const val MAX_MESSAGES = 64

    /** Longest prompt sent, and longest rendered prompt accepted back. */
    const val MAX_PROMPT_BYTES = 256 * 1024

    /** Sum of the contents handed to the template, leaving the render room for its control tokens. */
    const val MAX_CONTENT_BYTES = 224 * 1024

    /** Longest generation accepted; the shim stops at the same value. */
    const val MAX_TEXT_BYTES = 256 * 1024

    /** Longest error or diagnostic text accepted, in bytes or chars. */
    const val MAX_MESSAGE_BYTES = 4 * 1024

    const val MAX_TOKENS = 8192

    val CONTEXT_TOKENS = 512..32_768

    val THREADS = 1..16

    /** Sentinel for an integer field the engine process could not read. */
    const val UNKNOWN_INT = Int.MIN_VALUE
}

/** The status at the head of every reply; a failure status is followed by its message. */
object LlmEngineStatus {
    const val OK = 0
    const val ENGINE_ERROR = 1

    /** The handle was not issued by this engine process. */
    const val STALE_HANDLE = 2

    /** The handle is inside another call. */
    const val BUSY = 3

    /** The request violated a bound of [LlmEngineLimits]. */
    const val BAD_REQUEST = 4
}

/** The protocol's transactions, in code order; the Binder code is derived from the ordinal. */
enum class LlmTransaction { INIT, FORMAT_CHAT, GENERATE, CANCEL, FREE, RUNTIME }
