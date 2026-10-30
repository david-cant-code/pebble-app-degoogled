package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EndlessResponseCopiesTest {
    @Test
    fun everyCopyIsIdenticalAndInPlace() {
        val copies = TrackedTree.files.filter { it.substringAfterLast('/') == NAME }
        assertTrue("util/$COPY" in copies, "util's copy is not tracked at util/$COPY: $copies")
        val own = TrackedTree.file("util/$COPY").readText()
        for (copy in copies) {
            assertTrue(Regex("[^/]+/" + Regex.escape(COPY)).matches(copy), "$copy is not at <module>/$COPY")
            assertEquals(own, TrackedTree.file(copy).readText(), "$copy differs from util's copy")
        }
    }

    private companion object {
        const val NAME = "EndlessResponse.kt"
        const val COPY = "src/androidHostTest/kotlin/com/anopticlabs/gravel/network/$NAME"
    }
}
