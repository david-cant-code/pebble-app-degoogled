package com.anopticlabs.gravel.models

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coredevices.util.models.ModelDownloadManager
import coredevices.util.models.ModelDownloadService
import coredevices.util.models.ModelDownloadStatus
import coredevices.util.models.WhisperModelCatalog
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A download job that is scheduled and has not run, against a download manager built after it. The job may not
 * start for an hour and names a model that starts no download: none of a catalog, which the provider refuses, or
 * a speech model the phone already has.
 */
@RunWith(AndroidJUnit4::class)
class DownloadJobKeptDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scheduler = context.getSystemService(JobScheduler::class.java)
    private val service = ComponentName(context, ModelDownloadService::class.java)

    @Before
    @After
    fun cancelTheJob() = scheduler.cancel(JOB_ID)

    private fun schedule(slug: String) {
        val job = JobInfo.Builder(JOB_ID, service)
            .setExtras(PersistableBundle().apply { putString(ModelDownloadService.KEY_MODEL_SLUG, slug) })
            .setMinimumLatency(60 * 60 * 1000L)
            .build()
        assertEquals(JobScheduler.RESULT_SUCCESS, scheduler.schedule(job))
        assertTrue(scheduled())
    }

    private fun scheduled() = scheduler.allPendingJobs.any { it.id == JOB_ID }

    /** A manager built over a job for an installed speech model, on a phone where no other download is scheduled. */
    private fun managerShowingAScheduledJob(): Pair<ModelDownloadManager, String> {
        val installed = WhisperModelCatalog.MODELS.firstOrNull { File(context.filesDir, "models/${it.id}/${it.fileName}").length() == it.sizeBytes }
        assumeTrue("no speech model is installed", installed != null)
        assumeTrue("another download job is scheduled", scheduler.allPendingJobs.none { it.service == service })
        val slug = installed!!.id
        schedule(slug)
        val manager = ModelDownloadManager(context)
        assertEquals(ModelDownloadStatus.Downloading(slug), manager.downloadStatus.value)
        return manager to slug
    }

    @Test
    fun aScheduledJobWithNoHeartbeatOutlivesTheManagersBuild() {
        schedule("device-test-not-a-model")

        ModelDownloadManager(context)

        assertTrue("building the download manager canceled a scheduled job", scheduled())
    }

    @Test
    fun aCancelOfOneModelsJobThatHasNotRunClearsWhatItsLaneShows() {
        val (manager, slug) = managerShowingAScheduledJob()

        manager.cancelDownloadOf(slug)

        assertFalse(scheduled())
        assertEquals(ModelDownloadStatus.Idle, manager.downloadStatus.value)
    }

    @Test
    fun aCancelOfTheSpeechLaneClearsAJobThatHasNotRunFromIt() {
        val (manager, _) = managerShowingAScheduledJob()

        manager.cancelDownload()

        assertFalse(scheduled())
        assertEquals(ModelDownloadStatus.Idle, manager.downloadStatus.value)
    }

    private companion object {
        const val JOB_ID = 0x6b657074
    }
}
