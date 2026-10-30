package com.anopticlabs.gravel.notebook.backup

import kotlin.test.Test
import kotlin.test.assertEquals

/** RFC 5869 appendix A, test cases 1 to 3 (HKDF with SHA-256). */
class HkdfTest {

    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    private fun range(from: Int, to: Int) = ByteArray(to - from + 1) { (from + it).toByte() }

    @Test
    fun basicCase() {
        val okm = Hkdf.sha256(ByteArray(22) { 0x0b }, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42)
        assertEquals("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865", okm.hex())
    }

    @Test
    fun longerInputsAndOutputs() {
        val okm = Hkdf.sha256(range(0x00, 0x4f), range(0x60, 0xaf), range(0xb0, 0xff), 82)
        assertEquals(
            "b11e398dc80327a1c8e7f78c596a49344f012eda2d4efad8a050cc4c19afa97c59045a99cac7827271cb41c65e590e09" +
                "da3275600c2f09b8367793a9aca3db71cc30c58179ec3e87c14c01d5c1f3434f1d87",
            okm.hex(),
        )
    }

    @Test
    fun zeroLengthSaltAndInfo() {
        val okm = Hkdf.sha256(ByteArray(22) { 0x0b }, ByteArray(0), ByteArray(0), 42)
        assertEquals("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8", okm.hex())
    }
}
