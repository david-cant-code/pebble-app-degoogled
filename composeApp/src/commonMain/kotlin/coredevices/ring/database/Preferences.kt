package coredevices.ring.database

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Fork-owned stand-in for the unplugged :experimental module's ring
 * preferences. Same fully-qualified name as upstream so
 * PebbleBackgroundManager stays byte-identical; a re-plugged :experimental
 * collides at compile time, which is the intended tripwire.
 *
 * PebbleBackgroundManager null-checks `ringPaired` to decide whether the
 * foreground service must stay alive for a ring; with no ring support it is
 * permanently null, so the service lifecycle is driven by watches alone.
 */
class Preferences {
    val ringPaired: StateFlow<String?> = MutableStateFlow(null)
}
