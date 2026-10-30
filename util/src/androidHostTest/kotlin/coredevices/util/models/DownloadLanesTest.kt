package coredevices.util.models

import com.anopticlabs.gravel.models.ModelRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DownloadLanesTest {

    private val speech = "whisper-base-en"
    private val otherSpeech = "whisper-small-en"
    private val text = "qwen3-1_7b-q4_k_m"
    private val otherText = "qwen3_5-0_8b-q5_k_m"

    @Test
    fun modelsDownloadInTheirRolesLane() {
        assertEquals(ModelRole.Speech, laneOf(speech))
        assertEquals(ModelRole.Text, laneOf(text))
        assertEquals(ModelRole.Speech, laneOf("not-a-catalog-model"))
        assertEquals(ModelRole.Speech, laneOf(null))
    }

    @Test
    fun aLaneHoldsOnlyItsOwnRolesJobs() {
        val pending = listOf(
            PendingDownload(1, speech, stale = false),
            PendingDownload(2, text, stale = true),
            PendingDownload(3, null, stale = true),
        )
        assertEquals(listOf(pending[0], pending[2]), jobsInLane(ModelRole.Speech, pending))
        assertEquals(listOf(pending[1]), jobsInLane(ModelRole.Text, pending))
    }

    @Test
    fun aLiveJobForTheSameModelAloneInItsLaneIsKept() {
        val pending = listOf(PendingDownload(1, speech, stale = false), PendingDownload(2, text, stale = false))
        assertNull(jobsReplacedBy(speech, pending))
        assertNull(jobsReplacedBy(text, pending))
    }

    @Test
    fun aRequestReplacesOnlyItsOwnLane() {
        val pending = listOf(PendingDownload(1, speech, stale = false), PendingDownload(2, text, stale = false))
        assertEquals(listOf(pending[0]), jobsReplacedBy(otherSpeech, pending))
        assertEquals(listOf(pending[1]), jobsReplacedBy(otherText, pending))
    }

    @Test
    fun aStaleJobForTheSameModelIsReplaced() {
        val pending = listOf(PendingDownload(1, text, stale = true))
        assertEquals(pending, jobsReplacedBy(text, pending))
    }

    @Test
    fun aRequestIntoAnEmptyLaneReplacesNothing() {
        assertEquals(emptyList(), jobsReplacedBy(text, listOf(PendingDownload(1, speech, stale = false))))
    }

    @Test
    fun eachLaneStartsFromItsOwnScheduledJobWhetherOrNotItHasRun() {
        val pending = listOf(PendingDownload(1, speech, stale = true), PendingDownload(2, text, stale = false))
        assertEquals(ModelDownloadStatus.Downloading(speech), laneStatusAtStartup(ModelRole.Speech, pending))
        assertEquals(ModelDownloadStatus.Downloading(text), laneStatusAtStartup(ModelRole.Text, pending))
        assertEquals(ModelDownloadStatus.Idle, laneStatusAtStartup(ModelRole.Text, pending.take(1)))
        val unshown = listOf(PendingDownload(3, null, stale = true), PendingDownload(4, "not-a-catalog-model", stale = false))
        assertEquals(ModelDownloadStatus.Idle, laneStatusAtStartup(ModelRole.Speech, unshown))
        assertEquals(ModelDownloadStatus.Idle, laneStatusAtStartup(ModelRole.Text, unshown))
    }

    @Test
    fun aCancelClearsWhatItsLaneShowsAsDownloading() {
        val downloading = ModelDownloadStatus.Downloading(text)
        assertEquals(ModelDownloadStatus.Idle, laneStatusAfterCancel(downloading))
        assertEquals(ModelDownloadStatus.Idle, laneStatusAfterCancel(downloading, text))
        assertEquals(downloading, laneStatusAfterCancel(downloading, otherText))
        val failed = ModelDownloadStatus.Failed(text, "Download failed")
        assertEquals(failed, laneStatusAfterCancel(failed))
        assertEquals(failed, laneStatusAfterCancel(failed, text))
    }

    @Test
    fun aStopReachesOnlyTheJobItNames() {
        val running = RunningDownloads()
        val speechJob = Job()
        val textJob = Job()
        running.add(1, RunningDownloads.Entry(speech, speechJob))
        running.add(2, RunningDownloads.Entry(text, textJob))

        val stopped = running.stop(1, CancellationException("test"))

        assertEquals(speech, stopped?.slug)
        assertTrue(stopped!!.stoppedBySystem.get())
        assertTrue(speechJob.isCancelled)
        assertFalse(textJob.isCancelled)
        assertNull(running.stop(3, CancellationException("test")))
    }

    @Test
    fun aFinishedDownloadRemovesOnlyItsOwnEntry() {
        val running = RunningDownloads()
        val old = RunningDownloads.Entry(speech, Job())
        val rescheduled = RunningDownloads.Entry(speech, Job())
        running.add(1, old)
        running.add(1, rescheduled)

        running.remove(1, old)

        assertSame(rescheduled, running.stop(1, CancellationException("test")))
    }
}
