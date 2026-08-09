package coredevices.coreapp.util

import android.bluetooth.BluetoothDevice
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the passkey redaction in [describePairingExtra]. The pairing debug
 * receiver stringifies every extra of ACTION_PAIRING_REQUEST into logs that
 * the file writer persists and the bug-report flow uploads, so the numeric
 * passkey/PIN in EXTRA_PAIRING_KEY must never survive into that string.
 * Ordinary extras must pass through unchanged, because device metadata is
 * the whole point of the diagnostic; a redaction that swallowed everything
 * would just get reverted the next time someone needed the log.
 */
class BluetoothPairingRedactionTest {

    @Test
    fun pairingKeyExtraIsRedacted() {
        assertEquals(
            "${BluetoothDevice.EXTRA_PAIRING_KEY}=<redacted>",
            describePairingExtra(BluetoothDevice.EXTRA_PAIRING_KEY, 123456),
        )
    }

    @Test
    fun otherExtrasPassThroughUnchanged() {
        assertEquals(
            "${BluetoothDevice.EXTRA_PAIRING_VARIANT}=0",
            describePairingExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, 0),
        )
        assertEquals("android.bluetooth.device.extra.NAME=Core 2 Duo",
            describePairingExtra("android.bluetooth.device.extra.NAME", "Core 2 Duo"))
    }

    @Test
    fun redactionKeysOffTheExactExtraName() {
        // A key merely containing the passkey constant as a substring is not
        // the passkey extra; redacting by substring would hide variant or
        // vendor extras and erode the diagnostic for no privacy gain.
        assertEquals(
            "custom.PAIRING_KEY_ECHO=7",
            describePairingExtra("custom.PAIRING_KEY_ECHO", 7),
        )
    }
}
