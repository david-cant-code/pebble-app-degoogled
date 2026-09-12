package coredevices.util.transcription

import coredevices.whisper.WhisperEngineRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins how the engine process's report about itself becomes the
 * placement facts the diagnostics lines and the thread count use, and
 * that without a bound engine process (this host JVM never binds one)
 * every read degrades to this process's own facts rather than failing.
 */
class EngineProcessTest {

    @Test
    fun engineProcessReportMapsToTheSnapshot() {
        val runtime = WhisperEngineRuntime(
            cpusAllowedList = "0-3,6", cpuset = "/foreground", importance = null,
            oomScoreAdj = 1, pid = 4242, uid = 99010, openFds = 12,
        )
        assertEquals(
            EngineRuntimeSnapshot(
                allowedCpus = 5, cpuset = "/foreground", importance = null, oomScoreAdj = 1,
                process = EngineRuntimeSnapshot.PROCESS_ENGINE,
            ),
            engineProcessSnapshot(runtime),
        )
    }

    @Test
    fun unreadableEngineProcessFactsStayUnknown() {
        val runtime = WhisperEngineRuntime(
            cpusAllowedList = null, cpuset = null, importance = null,
            oomScoreAdj = null, pid = 1, uid = 99000, openFds = null,
        )
        val snapshot = engineProcessSnapshot(runtime)
        assertNull(snapshot.allowedCpus)
        assertNull(snapshot.cpuset)
        assertNull(snapshot.oomScoreAdj)
        assertEquals(EngineRuntimeSnapshot.PROCESS_ENGINE, snapshot.process)
    }

    /** A hostile engine report degrades to unknown facts; it never costs the app process memory. */
    @Test
    fun hostileCpuListInTheEngineReportReadsAsUnknown() {
        val runtime = WhisperEngineRuntime(
            cpusAllowedList = "0-2000000000", cpuset = "/foreground", importance = null,
            oomScoreAdj = 1, pid = 4242, uid = 99010, openFds = 12,
        )
        assertNull(engineProcessSnapshot(runtime).allowedCpus)
    }

    @Test
    fun withoutABoundEngineProcessThisProcessAnswers() {
        assertNull(engineProcessSnapshot())
        assertNull(engineProcessCpuIds())
        assertNull(engineProcessBindMillis())
        assertEquals(0L, engineProcessGeneration())
        assertEquals(EngineRuntimeSnapshot.PROCESS_HOST, engineRuntimeSnapshot().process)
        assertTrue(transcriptionThreadCount() in 1..MAX_ENGINE_THREADS)
    }
}
