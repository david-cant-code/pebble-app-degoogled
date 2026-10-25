package com.anopticlabs.gravel.notification

import android.media.ExifInterface
import kotlin.test.Test
import kotlin.test.assertEquals

class ExifOrientationTest {
    @Test
    fun quarterTurnsSwapTheSidesAndTheRestDoNot() {
        val swaps = mapOf(
            ExifInterface.ORIENTATION_UNDEFINED to false,
            ExifInterface.ORIENTATION_NORMAL to false,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to false,
            ExifInterface.ORIENTATION_ROTATE_180 to false,
            ExifInterface.ORIENTATION_FLIP_VERTICAL to false,
            ExifInterface.ORIENTATION_TRANSPOSE to true,
            ExifInterface.ORIENTATION_ROTATE_90 to true,
            ExifInterface.ORIENTATION_TRANSVERSE to true,
            ExifInterface.ORIENTATION_ROTATE_270 to true,
            42 to false,
        )
        swaps.forEach { (orientation, swapped) ->
            assertEquals(if (swapped) 3024 to 4032 else 4032 to 3024, displayedSize(4032, 3024, orientation), "orientation $orientation")
        }
    }
}
