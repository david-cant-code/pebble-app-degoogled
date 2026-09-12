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

    /**
     * The list can come from the engine process, so a range that would
     * expand to billions of ids must read as malformed, not allocate.
     */
    @Test
    fun cpuListRejectsIdsAboveTheBound() {
        assertNull(parseCpuList("0-2000000000"))
        assertNull(parseCpuList("0-${MAX_CPU_ID + 1}"))
        assertNull(parseCpuList("${MAX_CPU_ID + 1}"))
        assertNull(parseCpuList("0-3,${Int.MAX_VALUE}"))
        assertEquals(MAX_CPU_ID + 1, parseCpuListCount("0-$MAX_CPU_ID"))
        assertEquals(listOf(MAX_CPU_ID), parseCpuList("$MAX_CPU_ID"))
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
            snapshot = EngineRuntimeSnapshot(
                allowedCpus = 8, cpuset = "/foreground", importance = null, oomScoreAdj = 1,
                process = EngineRuntimeSnapshot.PROCESS_ENGINE,
            ),
            audioSeconds = 3.456,
            initWaitMillis = 0,
            decodeMillis = 1234,
            outcome = "ok",
        )
        assertEquals(
            "dictation engine: model=whisper-base-en threads=4 proc=engine allowedCpus=8 " +
                "cpuset=/foreground importance=? oomAdj=1 audioSec=3.46 initWaitMs=0 decodeMs=1234 outcome=ok",
            line,
        )
    }

    @Test
    fun unknownSnapshotFieldsPrintAsQuestionMarks() {
        val line = formatEngineDiagnostics(
            model = null,
            threads = 1,
            snapshot = EngineRuntimeSnapshot(null, null, null, null, process = EngineRuntimeSnapshot.PROCESS_HOST),
            audioSeconds = 15.0,
            initWaitMillis = 4210,
            decodeMillis = 0,
            outcome = "error:IllegalStateException",
        )
        assertEquals(
            "dictation engine: model=? threads=1 proc=host allowedCpus=? cpuset=? importance=? oomAdj=? " +
                "audioSec=15.00 initWaitMs=4210 decodeMs=0 outcome=error:IllegalStateException",
            line,
        )
    }

    @Test
    fun coldPathLineHasAFixedLayout() {
        val line = formatColdPathDiagnostics(
            model = "whisper-small-en",
            snapshot = EngineRuntimeSnapshot(
                allowedCpus = 4, cpuset = "/background", importance = 400, oomScoreAdj = 700,
                process = EngineRuntimeSnapshot.PROCESS_HOST,
            ),
            modelPathMillis = 3120,
            bindMillis = 498,
            engineInitMillis = 2290,
            warmUpMillis = 640,
            outcome = "ok",
        )
        assertEquals(
            "dictation coldpath: model=whisper-small-en proc=host allowedCpus=4 cpuset=/background importance=400 " +
                "oomAdj=700 modelPathMs=3120 bindMs=498 engineInitMs=2290 warmUpMs=640 outcome=ok",
            line,
        )
    }

    @Test
    fun coldPathTermsNeverReachedPrintAsQuestionMarks() {
        val line = formatColdPathDiagnostics(
            model = "whisper-small-en",
            snapshot = EngineRuntimeSnapshot(null, null, null, null, process = EngineRuntimeSnapshot.PROCESS_HOST),
            modelPathMillis = 3120,
            bindMillis = null,
            engineInitMillis = null,
            warmUpMillis = null,
            outcome = "error:RuntimeException",
        )
        assertEquals(
            "dictation coldpath: model=whisper-small-en proc=host allowedCpus=? cpuset=? importance=? oomAdj=? " +
                "modelPathMs=3120 bindMs=? engineInitMs=? warmUpMs=? outcome=error:RuntimeException",
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
