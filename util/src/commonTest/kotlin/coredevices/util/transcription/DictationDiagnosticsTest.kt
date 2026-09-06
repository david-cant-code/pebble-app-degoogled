package coredevices.util.transcription

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the cpulist parser and the diagnostics line layout. The line is
 * what a reporter's log zip carries, so its keys are a contract with
 * whoever reads that zip later, not an implementation detail.
 */
class DictationDiagnosticsTest {

    @Test
    fun cpuListCountsRangesAndSingles() {
        assertEquals(8, parseCpuListCount("0-7"))
        assertEquals(5, parseCpuListCount("0-3,6"))
        assertEquals(6, parseCpuListCount("0-3,6-7"))
        assertEquals(1, parseCpuListCount("4"))
        assertEquals(2, parseCpuListCount(" 6,7 \n"))
    }

    @Test
    fun cpuListYieldsSortedDistinctIds() {
        assertEquals(listOf(0, 1, 2, 3, 6), parseCpuList("0-3,6"))
        assertEquals(listOf(4, 6, 7), parseCpuList("6-7,4"))
        assertEquals(listOf(2), parseCpuList("2,2"))
    }

    @Test
    fun cpuListRejectsMalformedInput() {
        assertNull(parseCpuListCount(""))
        assertNull(parseCpuListCount("   "))
        assertNull(parseCpuListCount("a-b"))
        assertNull(parseCpuListCount("3-1"))
        assertNull(parseCpuListCount("0-3,"))
        assertNull(parseCpuListCount("-1"))
    }

    @Test
    fun engineLineHasAFixedLayout() {
        val line = formatEngineDiagnostics(
            model = "whisper-base-en",
            threads = 4,
            snapshot = EngineRuntimeSnapshot(allowedCpus = 8, cpuset = "/foreground", importance = 125),
            audioSeconds = 3.456,
            decodeMillis = 1234,
            outcome = "ok",
        )
        assertEquals(
            "dictation engine: model=whisper-base-en threads=4 allowedCpus=8 " +
                "cpuset=/foreground importance=125 audioSec=3.46 decodeMs=1234 outcome=ok",
            line,
        )
    }

    @Test
    fun unknownSnapshotFieldsPrintAsQuestionMarks() {
        val line = formatEngineDiagnostics(
            model = null,
            threads = 1,
            snapshot = EngineRuntimeSnapshot(null, null, null),
            audioSeconds = 15.0,
            decodeMillis = 0,
            outcome = "error:IllegalStateException",
        )
        assertEquals(
            "dictation engine: model=? threads=1 allowedCpus=? cpuset=? importance=? " +
                "audioSec=15.00 decodeMs=0 outcome=error:IllegalStateException",
            line,
        )
    }

    @Test
    fun sessionLineHasAFixedLayout() {
        assertEquals(
            "dictation session: audioSec=2.00 resultAfterMs=870 outcome=ok:5words",
            formatSessionDiagnostics(audioSeconds = 2.0, sinceAudioEndMillis = 870, outcome = "ok:5words"),
        )
    }
}
