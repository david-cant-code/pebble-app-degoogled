package coredevices.ring.ui.navigation

/**
 * Fork-owned stand-in for the unplugged :experimental module's ring route
 * marker. Nothing implements it, so AppNavHost's `it is RingRoute` scoping
 * check compiles unchanged and is always false.
 */
sealed interface RingRoute
