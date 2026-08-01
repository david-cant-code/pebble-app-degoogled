package coredevices.pebble.health

import com.viktormykhailiv.kmp.health.HealthManager

/**
 * Fork seam. Upstream creates the health-kmp manager with default factory
 * options, whose Android default enables the Google Fit fallback
 * (useGoogleFit = true) when Health Connect is unavailable. This fork
 * excludes the GMS artifacts that fallback needs, so the Android actual
 * must pin useGoogleFit = false: every device then deterministically gets
 * either the Health Connect manager or the library's no-op manager, and the
 * Google Fit class path is unreachable even if GMS classes were somehow
 * present. The options constructor is Android-actual-only, hence this
 * expect/actual instead of a common call site.
 */
internal expect fun createPlatformHealthManager(): HealthManager
