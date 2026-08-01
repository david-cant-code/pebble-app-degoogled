package coredevices.pebble.health

import com.viktormykhailiv.kmp.health.HealthManager
import com.viktormykhailiv.kmp.health.HealthManagerFactory
import com.viktormykhailiv.kmp.health.HealthManagerFactoryOptions

// iOS has no Google Fit dimension; default options preserve upstream
// behavior (HealthKit). Mechanical actual so the iOS target keeps
// compiling; iOS is unmaintained in this fork.
internal actual fun createPlatformHealthManager(): HealthManager =
    HealthManagerFactory().createManager(HealthManagerFactoryOptions.default())
