package coredevices.haversine

import kotlin.math.round

/** Subtracts the mean of [samples] in place. Empty input is left alone. */
fun removeDCBias(samples: ShortArray) {
    if (samples.isEmpty()) return
    var sum = 0.0
    for (sample in samples) sum += sample.toInt()
    val mean = round(sum / samples.size).toInt()
    for (i in samples.indices) {
        val shifted = samples[i].toInt() - mean
        samples[i] = shifted.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}
