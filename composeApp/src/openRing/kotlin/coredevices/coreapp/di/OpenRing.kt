package coredevices.coreapp.di

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.util.CoreConfigHolder
import coredevices.util.afterRingPaired
import coredevices.util.onboardingDeviceChoice
import coredevices.util.setOnboardingDeviceChoice
import coredevices.haversine.KMPHaversineDebugDelegate
import coredevices.haversine.KMPHaversineDebugInfo
import coredevices.haversine.KMPHaversineHacksDelegate
import coredevices.haversine.KMPHaversineSatellite
import coredevices.libindex.database.BasePreferences
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.Module

internal const val RING_HW_MAJOR = 11
internal const val RING_HW_MINOR = 0

expect fun gravelRingModule(): Module

expect fun startGravelRing()

fun openRingModules(): List<Module> = listOf(gravelRingModule())

/** The open ring build can scan and pair, so onboarding offers Index 01. */
fun indexOnboardingAvailable(): Boolean = true

/**
 * Same keys the upstream ring preferences use, so pairing and the
 * satellite manager read one paired id.
 */
class RingPreferences(
    private val settings: Settings,
    private val coreConfig: CoreConfigHolder,
) : BasePreferences {
    private val paired = MutableStateFlow(settings.getStringOrNull(PAIRED))
    override val ringPaired: StateFlow<String?> = paired
    private val pairedName = MutableStateFlow(settings.getStringOrNull(PAIRED_NAME))
    override val ringPairedName: StateFlow<String?> = pairedName
    private val syncIndex = MutableStateFlow(settings.getIntOrNull(SYNC_INDEX))
    override val lastSyncIndex: StateFlow<Int?> = syncIndex
    private val wiped = MutableStateFlow(settings.getStringOrNull(WIPED))
    val lastWipedId: StateFlow<String?> = wiped

    override fun setRingPaired(id: String?) {
        if (id == null) settings.remove(PAIRED) else settings.putString(PAIRED, id)
        paired.value = id
        if (id != null) {
            val current = settings.onboardingDeviceChoice()
            val next = current.afterRingPaired()
            if (current != next) settings.setOnboardingDeviceChoice(next)
            // Upstream turns the feed on after a signed-in account already has
            // recordings. Pairing is the stand-in for that sign-in.
            coreConfig.enableIndexFeed()
        }
    }

    override fun setRingPairedName(name: String?) {
        if (name == null) settings.remove(PAIRED_NAME) else settings.putString(PAIRED_NAME, name)
        pairedName.value = name
    }

    override suspend fun setLastSyncIndex(index: Int?) {
        if (index == null) settings.remove(SYNC_INDEX) else settings.putInt(SYNC_INDEX, index)
        syncIndex.value = index
    }

    fun setLastWipedId(id: String) {
        settings.putString(WIPED, id)
        wiped.value = id
    }

    private companion object {
        const val PAIRED = "ring_paired"
        const val PAIRED_NAME = "ring_paired_name"
        const val SYNC_INDEX = "last_sync_index"
        const val WIPED = "gravel.ring.lastWipedId"
    }
}

class GravelHacksDelegate(private val prefs: RingPreferences) : KMPHaversineHacksDelegate {
    override fun shouldWipeCollectionsBeforeTransfer(satellite: KMPHaversineSatellite): Boolean {
        val paired = prefs.ringPaired.value ?: return false
        if (!sameRing(satellite.id, paired)) return false
        val wiped = prefs.lastWipedId.value ?: return true
        return !sameRing(satellite.id, wiped)
    }

    override fun wipedCollectionsBeforeTransfer(satellite: KMPHaversineSatellite) {
        prefs.setLastWipedId(satellite.id)
    }
}

class LocalRingDebugDelegate : KMPHaversineDebugDelegate {
    private val log = Logger.withTag("GravelRing")
    private var lastReadMillis = 0L

    override fun handleHaversineDebugInfo(info: KMPHaversineDebugInfo) {
        log.i { "debug info for ${info.satelliteId} left unread in v1" }
    }

    override fun shouldReadRxRSSI(satellite: KMPHaversineSatellite): Boolean {
        val now = Clock.System.now().toEpochMilliseconds()
        if (now - lastReadMillis < 60.seconds.inWholeMilliseconds) return false
        lastReadMillis = now
        return true
    }

    override fun handleRxRSSI(rssi: Float, satellite: KMPHaversineSatellite) {
        log.i { "rx rssi $rssi for ${satellite.id}" }
    }
}

fun pcm16LeWav(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataBytes = samples.size * 2
    val out = ByteArray(44 + dataBytes)
    out[0] = 'R'.code.toByte()
    out[1] = 'I'.code.toByte()
    out[2] = 'F'.code.toByte()
    out[3] = 'F'.code.toByte()
    writeLe32(out, 4, 36 + dataBytes)
    out[8] = 'W'.code.toByte()
    out[9] = 'A'.code.toByte()
    out[10] = 'V'.code.toByte()
    out[11] = 'E'.code.toByte()
    out[12] = 'f'.code.toByte()
    out[13] = 'm'.code.toByte()
    out[14] = 't'.code.toByte()
    out[15] = ' '.code.toByte()
    writeLe32(out, 16, 16)
    writeLe16(out, 20, 1)
    writeLe16(out, 22, 1)
    writeLe32(out, 24, sampleRate)
    writeLe32(out, 28, sampleRate * 2)
    writeLe16(out, 32, 2)
    writeLe16(out, 34, 16)
    out[36] = 'd'.code.toByte()
    out[37] = 'a'.code.toByte()
    out[38] = 't'.code.toByte()
    out[39] = 'a'.code.toByte()
    writeLe32(out, 40, dataBytes)
    for (i in samples.indices) {
        writeLe16(out, 44 + i * 2, samples[i].toInt())
    }
    return out
}

private fun writeLe16(out: ByteArray, at: Int, value: Int) {
    out[at] = (value and 0xFF).toByte()
    out[at + 1] = ((value shr 8) and 0xFF).toByte()
}

private fun writeLe32(out: ByteArray, at: Int, value: Int) {
    writeLe16(out, at, value)
    writeLe16(out, at + 2, value shr 16)
}

private fun sameRing(a: String, b: String): Boolean =
    a.replace(":", "").equals(b.replace(":", ""), ignoreCase = true)
