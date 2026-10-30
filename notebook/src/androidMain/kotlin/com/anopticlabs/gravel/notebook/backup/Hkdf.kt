package com.anopticlabs.gravel.notebook.backup

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** HKDF with HMAC-SHA256, as RFC 5869 defines it. */
internal object Hkdf {
    private const val ALGORITHM = "HmacSHA256"
    private const val HASH_LEN = 32

    /** [length] bytes of output keying material; the caller wipes it and [ikm]. */
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..255 * HASH_LEN) { "HKDF output length out of range" }
        // An absent salt is HashLen zeros (RFC 5869 section 2.2); SecretKeySpec refuses an empty key.
        val prk = Mac.getInstance(ALGORITHM).run {
            init(SecretKeySpec(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ALGORITHM))
            doFinal(ikm)
        }
        try {
            val mac = Mac.getInstance(ALGORITHM).apply { init(SecretKeySpec(prk, ALGORITHM)) }
            val okm = ByteArray(length)
            var block = ByteArray(0)
            var filled = 0
            var counter = 1
            while (filled < length) {
                mac.update(block)
                mac.update(info)
                mac.update(counter.toByte())
                block.fill(0)
                block = mac.doFinal()
                val n = minOf(block.size, length - filled)
                block.copyInto(okm, filled, 0, n)
                filled += n
                counter++
            }
            block.fill(0)
            return okm
        } finally {
            prk.fill(0)
        }
    }
}
