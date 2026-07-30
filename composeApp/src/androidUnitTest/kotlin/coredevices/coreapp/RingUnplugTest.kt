package coredevices.coreapp

import coredevices.coreapp.di.NoOpLibIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RingUnplugTest {
    // :experimental is unplugged from the build, but its call sites survive
    // against fork-owned stubs with the same fully-qualified names. An
    // upstream merge that re-adds the module to settings.gradle.kts and the
    // app's dependencies would compile the ring feature back in silently if
    // not for the duplicate-class tripwire, and this probe fails the moment
    // any real :experimental class returns to the app classpath, whatever
    // the source tree looks like.
    @Test
    fun experimentalModuleIsAbsentFromTheClasspath() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("coredevices.ring.service.RingSync")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("coredevices.ring.RingDelegate")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("coredevices.experimentalModuleKt")
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
}
