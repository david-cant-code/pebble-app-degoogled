package com.anopticlabs.gravel.pkjs

import kotlin.test.Test
import kotlin.test.assertEquals

class WebViewVersionTest {
    @Test
    fun theMajorVersionComesFromTheVersionName() {
        val expected = listOf(
            "153.0.8010.36" to 153, "140.0.7339.207.0" to 140, "152" to 152,
            "" to null, null to null, "abc" to null, ".153" to null,
        )
        for ((versionName, major) in expected) assertEquals(major, webViewMajor(versionName), "$versionName")
    }
}
