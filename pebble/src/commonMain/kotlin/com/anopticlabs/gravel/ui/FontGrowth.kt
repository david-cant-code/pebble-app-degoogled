package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp

/**
 * How much wider [text] is drawn in [style] at the phone's font size than at font size 1.0, and never less than zero:
 * what a width set for font 1.0 has to gain for the text to keep fitting.
 */
@Composable
fun fontGrowth(text: String, style: TextStyle): Dp {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density, text, style) {
        fun widthAt(scale: Density) = measurer.measure(text, style, density = scale).size.width
        with(density) { (widthAt(density) - widthAt(Density(density.density))).coerceAtLeast(0).toDp() }
    }
}
