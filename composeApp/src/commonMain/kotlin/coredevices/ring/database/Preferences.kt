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
 * foreground service must stay alive for a ring; Gravel passes the address
 * of the ring paired with its ring client.
 */
class Preferences(val ringPaired: StateFlow<String?> = MutableStateFlow(null))
