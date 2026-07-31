package coredevices.pebble.health

import com.viktormykhailiv.kmp.health.HealthManager
import com.viktormykhailiv.kmp.health.HealthManagerFactory
import com.viktormykhailiv.kmp.health.HealthManagerFactoryOptions

// useGoogleFit = false is load-bearing: the Google Fit backend's GMS
// dependencies are excluded from the build (see the health-kmp dependency
// declarations), so the factory must never route there. Health Connect
// available -> HealthConnectManager, else the library's NoHealthManager.
internal actual fun createPlatformHealthManager(): HealthManager =
    HealthManagerFactory().createManager(HealthManagerFactoryOptions(useGoogleFit = false))
