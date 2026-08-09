package coredevices.coreapp

import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.viktormykhailiv.kmp.health.ApplicationContextHolder
import com.viktormykhailiv.kmp.health.HealthManager
import com.viktormykhailiv.kmp.health.legacy.GoogleFitManager
import coredevices.pebble.watchModule
import org.junit.After
import org.junit.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertEquals
import kotlin.test.assertIsNot

/**
 * Pins the health seam's two load-bearing facts through the production Koin
 * module: watchModule creates the manager via the fork's
 * createPlatformHealthManager() (not upstream's bare factory call), and the
 * Android actual pins useGoogleFit=false. Both matter because the Google Fit
 * backend's GMS dependencies are excluded from the build; if either fact
 * regresses (the realistic vector is an upstream merge restoring
 * `single { HealthManagerFactory().createManager() }`), devices without
 * Health Connect get a GoogleFitManager whose first GMS-touching call throws
 * an uncatchable NoClassDefFoundError inside the startup auto-sync.
 *
 * The distinguishing environment is "Health Connect unavailable": with HC
 * available the factory returns HealthConnectManager before consulting
 * useGoogleFit, so the test must force unavailability. It cannot run on the
 * JVM at all (health-kmp ships Java 21 bytecode; the JDK 17 unit-test JVM
 * refuses to load it), so it runs instrumented and forces the condition
 * through the library's settable context holder: on SDK 34+ the Health
 * Connect client probes availability via getSystemService("healthconnect"),
 * which the wrapper answers with null.
 */
class HealthSeamTest {

    private val appContext = InstrumentationRegistry.getInstrumentation()
        .targetContext.applicationContext

    // Both mutations below touch process-global state shared with the live
    // app: the context holder, and watchModule's single{} factories, which
    // cache their instance inside the module object itself, across every
    // Koin container that loads the module. Restore the context and drop the
    // test-era instance so the app lazily rebuilds against the real context.
    @After
    fun restoreProcessState() {
        ApplicationContextHolder.applicationContext = appContext
        koinApplication { modules(watchModule) }.koin.unloadModules(listOf(watchModule))
    }

    @Test
    fun healthSeamNeverRoutesToGoogleFit() {
        ApplicationContextHolder.applicationContext = object : ContextWrapper(appContext) {
            override fun getSystemService(name: String): Any? =
                if (name == "healthconnect") null else super.getSystemService(name)
        }

        // The app process resolved the HealthManager single at boot, and that
        // instance lives in the shared module object, so a plain get() would
        // hand it back without ever running the seam. Drop it, re-register,
        // and resolve fresh so the production definition executes under the
        // forced-unavailable context.
        val koin = koinApplication { modules(watchModule) }.koin
        koin.unloadModules(listOf(watchModule))
        koin.loadModules(listOf(watchModule))
        val manager = koin.get<HealthManager>()

        assertIsNot<GoogleFitManager>(
            manager,
            "health seam routed to the GMS-backed Google Fit manager",
        )
        // NoHealthManager is internal to the library, so pin it by name.
        assertEquals(
            "NoHealthManager",
            manager::class.simpleName,
            "expected the library's no-op manager while Health Connect is unavailable",
        )
    }
}
