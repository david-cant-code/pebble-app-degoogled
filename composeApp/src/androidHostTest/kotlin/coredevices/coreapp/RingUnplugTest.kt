package coredevices.coreapp

import coredevices.coreapp.di.NoOpLibIndex
import coredevices.coreapp.di.ringStubsModule
import coredevices.libindex.LibIndex
import coredevices.util.CoreConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.koin.dsl.koinApplication

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

    // NoOpLibIndex is the runtime kill switch for :libindex (which stays on
    // the compile classpath for the watch UI's sake): rings can never
    // appear and scanning can never start, which keeps every ring UI path
    // in WatchesScreen/WatchHomeScreen and the WatchHomeScreen auto-enable
    // of CoreConfig.enableIndex unreachable.
    @Test
    fun noOpLibIndexNeverScansAndNeverReportsRings() {
        val libIndex = NoOpLibIndex()
        assertTrue(libIndex.rings.value.isEmpty())
        libIndex.startScan()
        assertFalse(libIndex.isScanning.value)
        libIndex.stopScan()
        assertTrue(libIndex.rings.value.isEmpty())
    }

    // The behavior test above pins the stub class; this pins the seam. The
    // real RealLibIndex binding survives in the deliberately kept :libindex
    // (libIndexModule), so a one-line rebinding there or in an upstream
    // merge would compile clean and pass every other test while quietly
    // reviving the BLE scanner and Room database. Matches the seam tests
    // the sibling strips got (GmsAuthUnplugTest, AppUpdateUnplugTest).
    @Test
    fun ringSeamResolvesToTheForkNoOpLibIndex() {
        val koin = koinApplication { modules(ringStubsModule) }.koin
        assertIs<NoOpLibIndex>(koin.get<LibIndex>())
    }

    // Kill layer 2 of the ring unplug: enableIndex gates the Index tab and
    // every Index UI surface, its set-points are suppressed in the fork,
    // and nothing pins the upstream-owned default. An upstream merge
    // flipping it to true would merge conflict-free and re-open the gate
    // even for existing installs (CoreConfigHolder omits defaults from
    // stored JSON), so the default itself is the invariant to hold.
    @Test
    fun enableIndexDefaultsToOff() {
        assertFalse(CoreConfig().enableIndex)
    }
}
