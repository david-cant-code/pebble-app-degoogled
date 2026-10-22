package coredevices.haversine

import gravel.ring.parseHex
import gravel.ring.records.parseCollection
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking

class HaversineOpenHostTest {
    @Test
    fun inertManagerDoesNotScanOrConnect() = runBlocking {
        val manager = KMPHaversineSatelliteManager()
        assertNull(manager.lastRing.value)
        assertNull(manager.getSatelliteById("001122334455"))
        assertTrue(manager.programSatelliteWithUserID("001122334455", "user").isFailure)
        manager.awaitBluetoothReady()
        manager.restartPreemptiveTransfer()
        manager.startScanning().collect { error("inert scan emitted $it") }
    }

    @Test
    fun publicApiIsReachableFromTheAndroidConstructor() {
        val type = KMPHaversineSatelliteManager::class.java
        assertTrue(type.constructors.any { it.parameterCount == 0 })
        assertTrue(type.constructors.any { ctor ->
            ctor.parameterTypes.any { it.name == "android.content.Context" }
        })
        val names = type.methods.map { it.name }.toSet()
        listOf(
            "getSatelliteById",
            "startScanning",
            "awaitBluetoothReady",
            "restartPreemptiveTransfer",
            "programSatelliteWithUserID",
            "getLastRing",
        ).forEach { name ->
            // Kotlin mangles a Result return into a suffixed JVM name.
            assertTrue(names.any { it == name || it.startsWith("$name-") }, name)
        }
        val state = KMPHaversineSatelliteState::class.java
        listOf(
            "getFirmwareVersion",
            "getSerialNumber",
            "getProgrammedSerialNumber",
            "getRxRSSI",
            "isInFailsafeMode",
            "getBatteryMillivolts",
            "isInCollectionState",
            "isNearby",
            "getTruncatedCollectionCount",
        ).forEach { name ->
            assertTrue(state.methods.any { it.name == name }, name)
        }
    }

    @Test
    fun satelliteForceFailsafeThrows() {
        runBlocking {
            val satellite = KMPHaversineSatellite(
                "x",
                null,
                object : SatelliteOps {
                    override suspend fun eraseCollections(satellite: KMPHaversineSatellite) = Unit
                },
            )
            assertFailsWith<HaversineFeatureUnavailable> { satellite.forceFailsafe() }
        }
    }

    @Test
    fun goldenHexRecordingsMatchTheirPcm() {
        val index = resource("golden/index.txt")
            .lineSequence()
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        assertTrue(index.isNotEmpty())
        for (name in index) {
            val collection = parseHex(resource("golden/$name.collection.hex"))
            val pcm = parseHex(resource("golden/$name.pcm.hex"))
            val samples = parseCollection(collection).audio?.samples
            assertTrue(samples != null, name)
            assertContentEquals(pcm, samples.toS16le(), name)
        }
    }

    private fun resource(path: String): String =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream(path)) { path }
            .bufferedReader()
            .readText()
}

private fun ShortArray.toS16le(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt()
        out[i * 2] = value.toByte()
        out[i * 2 + 1] = (value shr 8).toByte()
    }
    return out
}
