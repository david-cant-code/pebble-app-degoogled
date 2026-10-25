package com.anopticlabs.gravel.notification

import android.media.ExifInterface

/** A [width]x[height] image's size as displayed under an EXIF [orientation]: quarter turns swap the sides. */
internal fun displayedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> = when (orientation) {
    ExifInterface.ORIENTATION_TRANSPOSE,
    ExifInterface.ORIENTATION_ROTATE_90,
    ExifInterface.ORIENTATION_TRANSVERSE,
    ExifInterface.ORIENTATION_ROTATE_270 -> height to width
    else -> width to height
}
