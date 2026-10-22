package com.anopticlabs.gravel.llm

import android.os.IBinder

/**
 * The transaction protocol between [LlmEngineClient] and
 * [LlmEngineService]: every request starts with the interface token,
 * every reply with the no-exception marker, a status ([LlmEngineStatus])
 * and the fields that status carries (each handler states its layout).
 */
internal object LlmEngineProtocol {
    const val DESCRIPTOR = "com.anopticlabs.gravel.llm.ILlmEngine"

    private const val FIRST = IBinder.FIRST_CALL_TRANSACTION

    fun code(transaction: LlmTransaction): Int = FIRST + transaction.ordinal

    fun transaction(code: Int): LlmTransaction? = LlmTransaction.entries.getOrNull(code - FIRST)
}
