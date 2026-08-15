package coredevices.haversine

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Pins the shape the stub's behavioral contract rests on (see the KDoc on
 * HaversineStubs.kt): the three satellite classes can only ever be
 * constructed privately and nothing hands one out, so no ring object can
 * exist in the app. HaversineStubTest covers what the two static entry
 * points answer; this test covers the invariant a later edit is most
 * likely to loosen (a constructor made public or a factory added to reach
 * an instance from a test), which no other test in the tree observes.
 * JVM reflection, hence the android host source set rather than commonTest.
 */
class HaversineStubShapeTest {

    private val satelliteClasses = listOf(
        KMPHaversineSatellite::class.java,
        KMPHaversineSatelliteState::class.java,
        KMPHaversineSatelliteManager::class.java,
    )

    @Test
    fun satelliteClassesHaveOnlyPrivateConstructors() {
        satelliteClasses.forEach { type ->
            val constructors = type.declaredConstructors
            assertTrue(constructors.isNotEmpty(), "${type.simpleName} declares no constructor")
            constructors.forEach { constructor ->
                assertTrue(Modifier.isPrivate(constructor.modifiers), "${type.simpleName} has a non-private constructor: $constructor")
            }
        }
    }

    // A companion factory compiles to a nested class plus static accessors;
    // a top-level factory lands in the file facade class.
    @Test
    fun nothingHandsOutASatelliteInstance() {
        satelliteClasses.forEach { type ->
            assertTrue(type.declaredClasses.isEmpty(), "${type.simpleName} gained a nested class: ${type.declaredClasses.toList()}")
            val staticFactories = type.declaredMethods.filter { Modifier.isStatic(it.modifiers) && it.returnType in satelliteClasses }
            assertTrue(staticFactories.isEmpty(), "${type.simpleName} gained a static factory: $staticFactories")
        }
        val facade = Class.forName("coredevices.haversine.HaversineStubsKt")
        val topLevelFactories = facade.declaredMethods.filter { it.returnType in satelliteClasses }
        assertTrue(topLevelFactories.isEmpty(), "A top-level function returns a satellite type: $topLevelFactories")
    }

    // The KDoc's second line of defense: should an instance ever be reached
    // anyway, its members answer the empty shape rather than throwing.
    @Test
    fun anInstanceReachedByReflectionIsInert() {
        val manager = construct(KMPHaversineSatelliteManager::class.java)
        assertNull(manager.lastRing.value)
        assertNull(runBlocking { manager.getSatelliteById("any") })

        val satellite = construct(KMPHaversineSatellite::class.java)
        assertEquals("", satellite.id)
        assertNull(satellite.name)
        assertNull(satellite.state.value)
        runBlocking { satellite.eraseCollections() }

        val state = construct(KMPHaversineSatelliteState::class.java)
        assertEquals("", state.firmwareVersion)
        assertEquals("", state.serialNumber)
        assertNull(state.programmedSerialNumber)
    }

    private fun <T> construct(type: Class<T>): T =
        type.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
}
