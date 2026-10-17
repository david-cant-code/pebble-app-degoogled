package com.anopticlabs.gravel.notebook.storage

import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** A [NotebookCrypto] over an in-memory AES key, for host tests of the codec and the stores. */
internal fun softwareCrypto(
    key: SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey(),
    retry: KeystoreRetry = KeystoreRetry(sleep = {}),
    keyForOpen: () -> SecretKey? = { key },
): NotebookCrypto = NotebookCrypto({ key }, keyForOpen, retry)

internal fun fixedKey(byte: Int): SecretKey = SecretKeySpec(ByteArray(32) { byte.toByte() }, "AES")
