package com.anopticlabs.gravel.notebook.storage

/**
 * The at-rest envelope: magic `GNF1`, a version byte, the key-wrap nonce, the file's data key
 * sealed by the Keystore key (with its tag), the data nonce, then the ciphertext ending in its tag.
 * Persisted format: a new layout gets a new version byte.
 */
object NotebookEnvelope {
    const val IV_LEN = 12
    const val TAG_LEN = 16
    const val DATA_KEY_LEN = 32
    const val WRAPPED_KEY_LEN = DATA_KEY_LEN + TAG_LEN
    const val WRAP_IV_OFFSET = 5
    const val WRAPPED_KEY_OFFSET = WRAP_IV_OFFSET + IV_LEN
    const val DATA_IV_OFFSET = WRAPPED_KEY_OFFSET + WRAPPED_KEY_LEN
    const val HEADER_LEN = DATA_IV_OFFSET + IV_LEN
    const val MIN_LEN = HEADER_LEN + TAG_LEN

    private val MAGIC = byteArrayOf('G'.code.toByte(), 'N'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())
    private const val VERSION: Byte = 0x01
    private val KEY_AAD_PREFIX = "gn1:key:".encodeToByteArray()
    private val DATA_AAD_PREFIX = "gn1:data:".encodeToByteArray()

    fun isOurFormat(bytes: ByteArray): Boolean =
        bytes.size >= MIN_LEN && hasMagic(bytes) && bytes[4] == VERSION

    fun header(wrapIv: ByteArray, wrappedKey: ByteArray, dataIv: ByteArray): ByteArray {
        require(wrapIv.size == IV_LEN) { "key-wrap nonce is ${wrapIv.size} bytes, expected $IV_LEN" }
        require(wrappedKey.size == WRAPPED_KEY_LEN) { "wrapped key is ${wrappedKey.size} bytes, expected $WRAPPED_KEY_LEN" }
        require(dataIv.size == IV_LEN) { "data nonce is ${dataIv.size} bytes, expected $IV_LEN" }
        val out = ByteArray(HEADER_LEN)
        MAGIC.copyInto(out, 0)
        out[4] = VERSION
        wrapIv.copyInto(out, WRAP_IV_OFFSET)
        wrappedKey.copyInto(out, WRAPPED_KEY_OFFSET)
        dataIv.copyInto(out, DATA_IV_OFFSET)
        return out
    }

    /** Checks the length, magic and version of [envelope] without any key. */
    fun checkHeader(envelope: ByteArray) {
        if (envelope.size < MIN_LEN) {
            throw InvalidFormatException("envelope too short: ${envelope.size} < $MIN_LEN")
        }
        if (!hasMagic(envelope)) throw InvalidFormatException("not our format (bad magic)")
        if (envelope[4] != VERSION) {
            throw InvalidFormatException("unknown version: 0x${(envelope[4].toInt() and 0xff).toString(16)}")
        }
    }

    /** The AAD of the data key's seal: binds it to the file's [location]. */
    fun keyAad(location: ByteArray): ByteArray = KEY_AAD_PREFIX + location

    /** The AAD of the data's seal: binds it to the [envelope]'s header and the file's [location]. */
    fun dataAad(envelope: ByteArray, location: ByteArray): ByteArray =
        envelope.copyOfRange(0, HEADER_LEN) + DATA_AAD_PREFIX + location

    private fun hasMagic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && (0 until 4).all { bytes[it] == MAGIC[it] }
}
