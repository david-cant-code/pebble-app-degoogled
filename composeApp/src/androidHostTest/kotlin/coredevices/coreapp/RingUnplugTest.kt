package coredevices.coreapp

import com.anopticlabs.gravel.app.RingClientLibIndex
import com.anopticlabs.gravel.app.ringClientModule
import coredevices.coreapp.di.ringStubsModule
import coredevices.libindex.LibIndex
import coredevices.libindex.device.DiscoveredIndexDevice
import coredevices.libindex.device.IndexIdentifier
import coredevices.libindex.device.IndexImage
import coredevices.libindex.device.IndexPairingState
import coredevices.util.CoreConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.module.flatten

class RingUnplugTest {
    // :experimental is unplugged from the build, but its call sites survive
    // against fork-owned stubs with the same fully-qualified names. An
    // upstream merge that re-adds the module to settings.gradle.kts and the
    // app's dependencies would compile the ring feature back in silently if
    // not for the duplicate-class tripwire, and this probe fails the moment
    // any real :experimental class returns to the app classpath, whatever
    // the source tree looks like. The positive probe on the fork's own
    // ExperimentalDevices stub proves the probe strings resolve by exact
    // binary name on this classpath, so a rename cannot quietly turn the
    // absence assertions into tautologies (the original third probe was
    // exactly that: "coredevices.experimentalModuleKt" can never exist,
    // Kotlin capitalizes file facades).
    @Test
    fun experimentalModuleIsAbsentFromTheClasspath() {
        Class.forName("coredevices.ExperimentalDevices")
        assertFailsWith<ClassNotFoundException> {
            Class.forName("coredevices.ring.service.RingSync")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("coredevices.ring.RingDelegate")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("coredevices.ExperimentalModuleKt")
        }
    }

    // Haversine (the ring's satellite BLE library) is still on the classpath
    // as a transitive of the deliberately kept :libindex; the app itself no
    // longer references it (PebbleService held the only call site), and like
    // the rest of the inert ring libraries it is never constructed.

    // The stub facade must be the fork's no-arg one, not upstream's
    // service-wired class (whose constructor takes eleven ring services).
    @Test
    fun experimentalDevicesFacadeIsTheForkStub() {
        val ctor = Class.forName("coredevices.ExperimentalDevices").constructors.single()
        assertEquals(0, ctor.parameterCount)
    }

    // The ring seam: Gravel's two ring modules bind LibIndex once, to its
    // ring client, never to upstream's RealLibIndex (libindex's own scanner
    // and Room database). Upstream's libIndexModule added to the app's
    // module list would not be caught here. The modules are read, not
    // started, so nothing in them is constructed.
    @OptIn(KoinInternalApi::class)
    @Test
    fun gravelsRingModulesBindItsRingClient() {
        val bindings = flatten(listOf(ringStubsModule, ringClientModule)).flatMap { it.mappings.entries }
            .filter { (key, _) -> key.startsWith("${LibIndex::class.qualifiedName}:") }
        assertEquals(listOf(RingClientLibIndex::class), bindings.map { it.value.beanDefinition.primaryType })
    }

    // Upstream keeps its service up for a failsafe ring a search heard, for a
    // firmware recovery Gravel does not have.
    @Test
    fun aFailsafeRingHeardInASearchHoldsNothingUp() {
        val failsafe = object : DiscoveredIndexDevice {
            override val identifier = IndexIdentifier("A1B2C3D4E5F6")
            override val name = "Pebble Index"
            override val rssi = -50
            override val pairingState = IndexPairingState.NotPaired
            override val currentImage = IndexImage.Failsafe
            override suspend fun pair() = error("not paired")
        }
        assertFalse(PebbleBackgroundManager.holdsForRingRecovery(listOf(failsafe)))
    }

    // Kill layer 2 of the ring unplug: enableIndex gates the Index tab and
    // every Index UI surface, its in-app set-points are suppressed in the
    // fork (debug builds' adb settings receiver can still write it), and
    // nothing pins the upstream-owned default. An upstream merge
    // flipping it to true would merge conflict-free and re-open the gate
    // even for existing installs (CoreConfigHolder omits defaults from
    // stored JSON), so the default itself is the invariant to hold.
    @Test
    fun enableIndexDefaultsToOff() {
        assertFalse(CoreConfig().enableIndex)
    }
}
