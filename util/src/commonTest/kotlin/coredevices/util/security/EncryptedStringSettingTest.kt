package coredevices.util.security

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val KEY = "account_token_key"

/** Reversible stand-in for the Keystore so the storage contract can be tested off-device. */
private class FakeCipher(
    var failEncrypt: Boolean = false,
    var failDecrypt: Boolean = false,
) : SecretCipher {
    override fun encrypt(plaintext: String): String? =
        if (failEncrypt) null else plaintext.reversed()

    override fun decrypt(stored: String): String? =
        if (failDecrypt) null else stored.reversed()
}

class EncryptedStringSettingTest {

    @Test
    fun `round trips a value`() {
        val settings = MapSettings()
        val setting = EncryptedStringSetting(settings, FakeCipher(), KEY)

        setting.set("secret-token")

        assertEquals("secret-token", setting.get())
    }

    @Test
    fun `does not store the plaintext`() {
        val settings = MapSettings()
        val setting = EncryptedStringSetting(settings, FakeCipher(), KEY)

        setting.set("secret-token")

        val raw = settings.getStringOrNull(KEY)
        assertFalse(raw!!.contains("secret-token"), "raw stored value leaked the plaintext: $raw")
        assertTrue(raw.startsWith("enc1:"))
    }

    @Test
    fun `upgrades a legacy plaintext value in place`() {
        // What an install written by a pre-encryption build looks like on disk.
        val settings = MapSettings().apply { putString(KEY, "legacy-token") }
        val setting = EncryptedStringSetting(settings, FakeCipher(), KEY)

        // The user must stay signed in across the upgrade.
        assertEquals("legacy-token", setting.get())

        val raw = settings.getStringOrNull(KEY)
        assertTrue(raw!!.startsWith("enc1:"), "legacy value was not upgraded: $raw")
        assertFalse(raw.contains("legacy-token"))
        assertEquals("legacy-token", setting.get())
    }

    @Test
    fun `keeps a legacy value when encryption is unavailable`() {
        val settings = MapSettings().apply { putString(KEY, "legacy-token") }
        val setting = EncryptedStringSetting(settings, FakeCipher(failEncrypt = true), KEY)

        // Losing the session would be worse than leaving it exactly as it already was.
        assertEquals("legacy-token", setting.get())
        assertEquals("legacy-token", settings.getStringOrNull(KEY))
    }

    @Test
    fun `refuses to store plaintext when encryption fails`() {
        val settings = MapSettings()
        val setting = EncryptedStringSetting(settings, FakeCipher(failEncrypt = true), KEY)

        setting.set("secret-token")

        assertNull(settings.getStringOrNull(KEY))
    }

    @Test
    fun `discards a value it cannot decrypt`() {
        // Stands in for a ciphertext restored from a backup onto different hardware.
        val settings = MapSettings()
        EncryptedStringSetting(settings, FakeCipher(), KEY).set("secret-token")

        val onNewDevice = EncryptedStringSetting(settings, FakeCipher(failDecrypt = true), KEY)

        assertNull(onNewDevice.get())
        assertNull(settings.getStringOrNull(KEY), "undecryptable value was left at rest")
    }

    @Test
    fun `clears the stored value on null`() {
        val settings = MapSettings()
        val setting = EncryptedStringSetting(settings, FakeCipher(), KEY)
        setting.set("secret-token")

        setting.set(null)

        assertNull(setting.get())
        assertNull(settings.getStringOrNull(KEY))
    }
}
