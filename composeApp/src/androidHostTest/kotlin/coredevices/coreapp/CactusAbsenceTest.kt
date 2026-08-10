package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CactusAbsenceTest {
    // The proprietary Cactus engine was replaced by the from-source whisper
    // stack, and its license is why: nothing may quietly reintroduce the
    // bindings. An upstream merge that restores a :cactus dependency would
    // compile cleanly wherever call sites came back with it, so this probe
    // fails the moment the binding classes return to the app's classpath,
    // whatever the source tree looks like.
    @Test
    fun cactusBindingsAreAbsentFromTheClasspath() {
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.cactus.Cactus")
        }
        assertFailsWith<ClassNotFoundException> {
            Class.forName("com.cactus.CactusJNI")
        }
    }
}
