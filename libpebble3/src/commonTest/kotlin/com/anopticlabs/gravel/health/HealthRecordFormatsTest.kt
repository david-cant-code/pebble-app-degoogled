package com.anopticlabs.gravel.health

import io.rebble.libpebblecommon.database.entity.HealthDataEntity
import io.rebble.libpebblecommon.database.entity.OverlayDataEntity
import io.rebble.libpebblecommon.health.OverlayType
import io.rebble.libpebblecommon.health.parsers.parseOverlayData
import io.rebble.libpebblecommon.health.parsers.parseStepsData
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlin.test.Test
import kotlin.test.assertEquals

private const val HEADER_BYTES = 9
private const val SAMPLES_PER_RECORD = 15
private const val SESSION_BYTES = 26
private const val START = 1_760_000_000u

/**
 * Feeds the health parsers records laid out as the watch firmware writes them (PebbleOS v4.40.0,
 * include/pbl/services/activity: activity_algorithm.h AlgMinuteRecordHdr and AlgMinuteDLSSample with
 * the version history at ALG_DLS_MINUTES_RECORD_VERSION, activity_private.h
 * ActivitySessionDataLoggingRecord, activity.h ActivitySessionDataStepping).
 */
class HealthRecordFormatsTest {
    // Bytes of one minute sample at each record version in that history from 5 on.
    private val sampleBytesByVersion = mapOf(5 to 6, 6 to 12, 7 to 13, 12 to 15, 13 to 16, 14 to 18)

    /** A minute record with [written] samples; a sample carries the fields its [version] has, padded to [sampleSize]. */
    private fun minuteRecord(
        version: Int,
        sampleSize: Int,
        start: UInt = START,
        written: Int = SAMPLES_PER_RECORD,
        claimed: Int = written,
        itemSize: Int = HEADER_BYTES + SAMPLES_PER_RECORD * sampleSize,
    ): ByteArray {
        val buffer = DataBuffer(itemSize)
        buffer.setEndian(Endian.Little)
        buffer.putUShort(version.toUShort())
        buffer.putUInt(start)
        buffer.putByte(0) // local time offset
        buffer.putUByte(sampleSize.toUByte())
        buffer.putUByte(claimed.toUByte())
        for (s in 0 until written) {
            var wrote = 6
            buffer.putUByte((10 + s).toUByte()) // steps
            buffer.putUByte(0x21u) // orientation
            buffer.putUShort((300 + s).toUShort()) // vmc
            buffer.putUByte(3u) // light
            buffer.putUByte((if (s % 2 == 0) 2 else 0).toUByte()) // flags, bit 1 marks an active minute
            if (version >= 6) {
                buffer.putUShort((20 + s).toUShort()) // resting calories
                buffer.putUShort((40 + s).toUShort()) // active calories
                buffer.putUShort((700 + s).toUShort()) // distance
                wrote += 6
            }
            if (version >= 7) {
                buffer.putUByte((60 + s).toUByte()) // heart rate
                wrote += 1
            }
            if (version >= 12) {
                buffer.putUShort((100 + s).toUShort()) // heart rate weight
                wrote += 2
            }
            if (version >= 13) {
                buffer.putUByte((1 + s % 3).toUByte()) // heart rate zone
                wrote += 1
            }
            repeat(sampleSize - wrote) { buffer.putUByte(0xAAu) }
        }
        return buffer.array().toByteArray()
    }

    private fun parsedSample(version: Int, start: UInt, s: Int) = HealthDataEntity(
        timestamp = (start + 60u * s.toUInt()).toLong(),
        steps = 10 + s,
        orientation = 0x21,
        intensity = 300 + s,
        lightIntensity = 3,
        activeMinutes = if (s % 2 == 0) 1 else 0,
        restingGramCalories = if (version >= 6) 20 + s else 0,
        activeGramCalories = if (version >= 6) 40 + s else 0,
        distanceCm = if (version >= 6) 700 + s else 0,
        heartRate = if (version >= 7) 60 + s else 0,
        heartRateZone = if (version >= 13) 1 + s % 3 else 0,
        heartRateWeight = if (version >= 12) 100 + s else 0,
    )

    private fun parsedRecord(version: Int, start: UInt) =
        (0 until SAMPLES_PER_RECORD).map { parsedSample(version, start, it) }

    @Test
    fun fullRecordsOfEachVersionAreReadToTheirLastByte() {
        for ((version, sampleSize) in sampleBytesByVersion) {
            val itemSize = HEADER_BYTES + SAMPLES_PER_RECORD * sampleSize
            val payload = minuteRecord(version, sampleSize) + minuteRecord(version, sampleSize, start = START + 900u)

            val records = parseStepsData(payload, itemSize.toUShort())

            assertEquals(parsedRecord(version, START) + parsedRecord(version, START + 900u), records, "version $version")
        }
    }

    @Test
    fun aNewerVersionWithSamplesOfVersion13sSizeIsRead() {
        val itemSize = HEADER_BYTES + SAMPLES_PER_RECORD * 16

        val records = parseStepsData(minuteRecord(version = 15, sampleSize = 16), itemSize.toUShort())

        assertEquals(parsedRecord(15, START), records)
    }

    @Test
    fun aSkippedRecordDoesNotShiftTheRecordAfterIt() {
        val itemSize = HEADER_BYTES + SAMPLES_PER_RECORD * 16
        val skipped = minuteRecord(version = 14, sampleSize = 15, written = 2, itemSize = itemSize)
        val read = minuteRecord(version = 13, sampleSize = 16, start = START + 900u)

        val records = parseStepsData(skipped + read, itemSize.toUShort())

        assertEquals(parsedRecord(13, START + 900u), records)
    }

    @Test
    fun aRecordClaimingMoreSamplesThanItHoldsYieldsTheWholeOnes() {
        val itemSize = HEADER_BYTES + 18 + 10
        val payload = minuteRecord(version = 14, sampleSize = 18, written = 1, claimed = 2, itemSize = itemSize)

        val records = parseStepsData(payload, itemSize.toUShort())

        assertEquals(listOf(parsedSample(14, START, 0)), records)
    }

    /** A version 3 session record: 18 bytes, then [typeData] as the four 16 bit fields of its type's data. */
    private fun sessionRecord(
        version: Int,
        type: OverlayType,
        start: UInt,
        utcToLocal: Int = 3600,
        typeData: List<Int> = listOf(0x1111, 0x2222, 0x3333, 0x4444),
    ): ByteArray {
        val buffer = DataBuffer(SESSION_BYTES)
        buffer.setEndian(Endian.Little)
        buffer.putUShort(version.toUShort())
        buffer.putUShort(SESSION_BYTES.toUShort())
        buffer.putUShort(type.value.toUShort())
        buffer.putInt(utcToLocal)
        buffer.putUInt(start)
        buffer.putUInt(1800u) // elapsed seconds
        typeData.forEach { buffer.putUShort(it.toUShort()) }
        return buffer.array().toByteArray()
    }

    @Test
    fun aSleepSessionAndAWalkOfExactlyTheRecordSizeAreBothRead() {
        // A walk's data: steps, active kcal, resting kcal, distance.
        val payload = sessionRecord(3, OverlayType.Sleep, START, utcToLocal = -25200) +
            sessionRecord(3, OverlayType.Walk, START + 7200u, typeData = listOf(1200, 40, 15, 800))

        val records = parseOverlayData(payload, SESSION_BYTES.toUShort())

        assertEquals(
            listOf(
                OverlayDataEntity(
                    startTime = START.toLong(), duration = 1800, type = OverlayType.Sleep.value, steps = 0,
                    restingKiloCalories = 0, activeKiloCalories = 0, distanceCm = 0, offsetUTC = -25200,
                ),
                OverlayDataEntity(
                    startTime = (START + 7200u).toLong(), duration = 1800, type = OverlayType.Walk.value, steps = 1200,
                    restingKiloCalories = 15, activeKiloCalories = 40, distanceCm = 800, offsetUTC = 3600,
                ),
            ),
            records,
        )
    }

    @Test
    fun aSessionRecordWithBit0OfItsVersionClearIsSkippedAndTheNextOneIsRead() {
        val payload = sessionRecord(4, OverlayType.Sleep, START) + sessionRecord(3, OverlayType.Sleep, START + 7200u)

        val records = parseOverlayData(payload, SESSION_BYTES.toUShort())

        assertEquals(listOf((START + 7200u).toLong()), records.map { it.startTime })
    }
}
