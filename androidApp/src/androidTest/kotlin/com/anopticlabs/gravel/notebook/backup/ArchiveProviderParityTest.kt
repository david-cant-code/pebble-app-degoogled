package com.anopticlabs.gravel.notebook.backup

import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The phone's crypto providers must derive the key and seal the segments exactly as the host
 * JVM does, for a non-ASCII password: the same inputs give the archive whose hash `ArchiveVector`
 * in the host suite pins, and it opens with the password in either Unicode form.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   com.anopticlabs.gravel.notebook.backup.ArchiveProviderParityTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class ArchiveProviderParityTest {

    private val password = "pässwörd ñandú ünïcode"
    private val body = "Grüße, café"
    private val hostSha256 = "d1fb63dc1b64d5db07a833a786c8310f284254e92b7aea77be80418ad30bfc38"

    private class FixedRandom : SecureRandom() {
        private var n = 0

        override fun nextBytes(bytes: ByteArray) {
            for (i in bytes.indices) bytes[i] = (n++ * 7 + 3).toByte()
        }
    }

    private fun write(): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ArchiveWriter.open(out, Normalizer.normalize(password, Normalizer.Form.NFC).toCharArray(), FixedRandom())
        writer.write(RecordType.NOTE, body.encodeToByteArray())
        writer.finish()
        return out.toByteArray()
    }

    @Test
    fun theArchiveMatchesTheHostVectorAndOpensWithTheDecomposedPassword() {
        val data = write()
        assertEquals(hostSha256, MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) })

        val reader = ArchiveReader.open(ByteArrayInputStream(data), Normalizer.normalize(password, Normalizer.Form.NFD).toCharArray())
        assertEquals(body, reader.next()!!.body.decodeToString())
        assertEquals(null, reader.next())
    }

    @Test
    fun aPasswordThatIsNotValidTextIsRefusedBeforeTheProviderSeesIt() {
        assertFailsWith<IllegalArgumentException> { ArchiveWriter.open(ByteArrayOutputStream(), "abcdefghijklmnop\uD800".toCharArray()) }
        val problem = assertFailsWith<InvalidBackupException> { ArchiveReader.open(ByteArrayInputStream(write()), "abcdefghijklmnop\uD800".toCharArray()) }.problem
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, problem)
        val empty = assertFailsWith<InvalidBackupException> { ArchiveReader.open(ByteArrayInputStream(write()), CharArray(0)) }.problem
        assertEquals(BackupProblem.WRONG_PASSWORD_OR_DAMAGED, empty)
    }
}
