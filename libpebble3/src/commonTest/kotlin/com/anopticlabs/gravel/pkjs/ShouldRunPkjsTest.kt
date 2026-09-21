package com.anopticlabs.gravel.pkjs

import kotlin.test.Test
import kotlin.test.assertEquals

class ShouldRunPkjsTest {
    @Test
    fun theFullTruthTable() {
        val expected = mapOf(
            Triple(false, false, false) to false,
            Triple(false, false, true) to false,
            Triple(false, true, false) to false,
            Triple(false, true, true) to false,
            Triple(true, false, false) to false,
            Triple(true, false, true) to true,
            Triple(true, true, false) to true,
            Triple(true, true, true) to true,
        )
        for ((input, runs) in expected) {
            val (hasPkjs, networkGranted, primaryLayerActive) = input
            assertEquals(
                runs,
                shouldRunPkjs(hasPkjs, networkGranted, primaryLayerActive),
                "hasPkjs=$hasPkjs networkGranted=$networkGranted primaryLayerActive=$primaryLayerActive",
            )
        }
    }
}
