package com.anopticlabs.gravel.app

import com.anopticlabs.gravel.models.LlmModel
import com.anopticlabs.gravel.models.LlmModelCatalog
import coredevices.util.models.ModelDownloadStatus
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidTextModelsTest {
    private val test = TestScope(UnconfinedTestDispatcher())
    private val scope = CoroutineScope(test.coroutineContext + SupervisorJob() + CoroutineExceptionHandler { _, _ -> })
    private val large = LlmModelCatalog.MODELS.maxBy { it.sizeBytes }
    private val small = LlmModelCatalog.MODELS.minBy { it.sizeBytes }
    private val host = FakeHost()
    private val models = AndroidTextModels(supported = true, hostOf = { host }, scope = scope)

    @AfterTest
    fun tearDown() = scope.cancel()

    private class FakeHost : TextModelHost {
        var supported = true
        var scheduled = true
        var failInstalledOnce = false
        var failForget = false
        var failOnSchedule = false
        val installedIds = mutableListOf<String>()
        val partials = mutableMapOf<String, Long>()
        val calls = mutableListOf<String>()
        var drains = 0
        override var chosen: String? = null
        override val download = MutableStateFlow<ModelDownloadStatus>(ModelDownloadStatus.Idle)
        override fun engineSupported() = supported
        override fun installed(): List<String> {
            if (failInstalledOnce) {
                failInstalledOnce = false
                throw IllegalStateException("disk")
            }
            return installedIds.toList()
        }
        override fun inUse() = installedIds.firstOrNull { it == chosen } ?: installedIds.firstOrNull()
        override fun lastLoadFailed() = false
        override fun partialBytes(modelId: String) = partials[modelId] ?: 0L
        override fun startDownload(model: LlmModel): Boolean {
            calls += "download ${model.id}"
            // As a job that starts and fails before the call returns would.
            if (failOnSchedule) download.value = ModelDownloadStatus.Failed(model.id, "Download failed")
            return scheduled
        }
        override fun setDownload(modelId: String, status: ModelDownloadStatus) {
            download.value = status
        }
        override fun cancelDownload() {
            calls += "cancel"
        }
        override fun cancelDownloadOf(modelId: String) {
            calls += "cancel ${modelId}"
        }
        override fun deleteInstalled(modelId: String) {
            calls += "delete ${modelId}"
            installedIds -= modelId
        }
        override suspend fun discardPartial(modelId: String) {
            calls += "discard ${modelId}"
            partials -= modelId
        }
        override suspend fun forgetDeleted(modelId: String) {
            calls += "forget ${modelId}"
            if (failForget) throw IllegalStateException("engine")
        }
        override fun requestDrain() {
            drains++
        }
    }

    /** As the download job ends a download: the file in place, the lane idle, then its report. */
    private fun finish(id: String, reportTo: AndroidTextModels = models) {
        host.download.value = ModelDownloadStatus.Downloading(id)
        host.installedIds += id
        host.download.value = ModelDownloadStatus.Idle
        report(id, reportTo)
    }

    private fun report(id: String, to: AndroidTextModels = models) {
        scope.launch { to.finished(id) }
        test.runCurrent()
    }

    @Test
    fun aFinishedDownloadBecomesTheChoiceAndStartsWaitingRecordings() {
        host.chosen = small.id
        host.installedIds += small.id
        models.refresh()
        finish(large.id)
        assertEquals(large.id, host.chosen)
        assertEquals(1, host.drains)
        assertEquals(large.id, models.facts.value.inUse)
    }

    @Test
    fun aDownloadFinishedWhereNoEntryPointRanBecomesTheChoiceAndStartsWaitingRecordings() {
        host.chosen = small.id
        host.installedIds += listOf(small.id, large.id)
        report(large.id)
        assertEquals(large.id, host.chosen)
        assertEquals(1, host.drains)
        assertEquals(large.id, models.facts.value.chosen)
    }

    /** The download job waits for its report: the follow-up is done when the call returns. */
    @Test
    fun aReportIsFollowedBeforeItReturns() = runBlocking {
        val neverRun = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
        val waitedFor = AndroidTextModels(supported = true, hostOf = { host }, scope = neverRun)
        host.installedIds += large.id
        withTimeout(5.seconds) { waitedFor.finished(large.id) }
        assertEquals(large.id, host.chosen)
        assertEquals(1, host.drains)
        assertEquals(large.id, waitedFor.facts.value.chosen)
        neverRun.cancel()
    }

    @Test
    fun aReportForAModelThatIsNotInstalledOrNotATextModelChangesNothing() {
        host.installedIds += "whisper-base-en"
        var built = 0
        val lazyModels = AndroidTextModels(supported = true, hostOf = { built++; host }, scope = scope)
        report("whisper-base-en", lazyModels)
        assertEquals(0, built, "a speech model's report built the host")
        report(large.id, lazyModels)
        assertNull(host.chosen)
        assertEquals(0, host.drains)
    }

    @Test
    fun aLaneThatGoesIdleWithNoReportChangesNoChoice() {
        models.refresh()
        host.download.value = ModelDownloadStatus.Downloading(large.id)
        host.installedIds += large.id
        host.download.value = ModelDownloadStatus.Idle
        host.download.value = ModelDownloadStatus.Downloading(small.id)
        host.download.value = ModelDownloadStatus.Failed(small.id, "Download failed")
        test.runCurrent()
        assertNull(host.chosen)
        assertEquals(0, host.drains)
        assertEquals(ModelDownloadStatus.Failed(small.id, "Download failed"), models.facts.value.download)
    }

    @Test
    fun aDownloadTheUserCancelledIsNotChosenEvenIfItsFileWasInPlace() {
        models.refresh()
        host.download.value = ModelDownloadStatus.Downloading(large.id)
        host.installedIds += large.id
        models.cancelDownload()
        test.runCurrent()
        assertEquals(listOf("cancel"), host.calls)
        assertEquals(ModelDownloadStatus.Idle, host.download.value)
        report(large.id)
        assertNull(host.chosen)
        assertEquals(0, host.drains)
        // That report used up the cancel.
        report(large.id)
        assertEquals(large.id, host.chosen)
    }

    @Test
    fun aDownloadCanceledAndStartedAgainIsChosenWhenItFinishes() {
        models.download(large.id)
        test.runCurrent()
        models.cancelDownload()
        test.runCurrent()
        models.download(large.id)
        test.runCurrent()
        finish(large.id)
        assertEquals(large.id, host.chosen)
        assertEquals(1, host.drains)
    }

    @Test
    fun aCancelOfADownloadThatNeverRanStopsShowingIt() {
        models.download(small.id)
        test.runCurrent()
        assertEquals(ModelDownloadStatus.Downloading(small.id), models.facts.value.download)
        models.cancelDownload()
        test.runCurrent()
        assertEquals(ModelDownloadStatus.Idle, models.facts.value.download)
    }

    @Test
    fun aScheduledDownloadShowsAsStartedAndARefusedOneAsFailed() {
        models.download(small.id)
        test.runCurrent()
        assertEquals(ModelDownloadStatus.Downloading(small.id), host.download.value)
        host.download.value = ModelDownloadStatus.Idle
        host.scheduled = false
        models.download(large.id)
        test.runCurrent()
        assertEquals(ModelDownloadStatus.Failed(large.id, "Download could not be started"), models.facts.value.download)
    }

    @Test
    fun aFailedReadDoesNotStopFollowingTheLane() {
        models.refresh()
        test.runCurrent()
        host.failInstalledOnce = true
        host.download.value = ModelDownloadStatus.Downloading(small.id)
        test.runCurrent()
        assertEquals(ModelDownloadStatus.Idle, models.facts.value.download)
        host.download.value = ModelDownloadStatus.Failed(small.id, "Download failed")
        test.runCurrent()
        assertEquals(ModelDownloadStatus.Failed(small.id, "Download failed"), models.facts.value.download)
    }

    @Test
    fun theHostIsBuiltOnFirstUseNotWithTheClass() {
        var built = 0
        val lazyModels = AndroidTextModels(supported = true, hostOf = { built++; host }, scope = scope)
        test.runCurrent()
        assertEquals(0, built)
        lazyModels.refresh()
        test.runCurrent()
        assertEquals(1, built)
        assertTrue(lazyModels.facts.value.read)
        // With the lane watch running, one report asks for one run.
        finish(small.id, lazyModels)
        assertEquals(1, host.drains)
    }

    @Test
    fun aReportWhoseFollowingFailsStillReadsAndTheNextOneIsFollowed() {
        host.installedIds += small.id
        host.partials[large.id] = 900L
        host.failInstalledOnce = true
        report(small.id)
        assertNull(host.chosen)
        assertEquals(mapOf(large.id to 900L), models.facts.value.partialBytes)
        report(small.id)
        assertEquals(small.id, host.chosen)
    }

    @Test
    fun aJobThatFailsAtOnceKeepsItsFailure() {
        host.failOnSchedule = true
        models.download(small.id)
        test.runCurrent()
        assertEquals(ModelDownloadStatus.Failed(small.id, "Download failed"), models.facts.value.download)
    }

    @Test
    fun choosingAnInstalledModelSavesItAndStartsWaitingRecordings() {
        host.installedIds += listOf(large.id, small.id)
        models.choose(small.id)
        test.runCurrent()
        assertEquals(small.id, host.chosen)
        assertEquals(1, host.drains)
        assertEquals(small.id, models.facts.value.chosen)
    }

    @Test
    fun aModelThatIsNotInstalledCannotBeChosen() {
        models.choose(small.id)
        test.runCurrent()
        assertNull(host.chosen)
        assertEquals(0, host.drains)
    }

    @Test
    fun deletingTheChosenModelClearsTheChoiceFreesItAndStartsWaitingRecordings() {
        host.installedIds += listOf(large.id, small.id)
        host.chosen = large.id
        models.delete(large.id)
        test.runCurrent()
        assertEquals(listOf("delete ${large.id}", "forget ${large.id}"), host.calls)
        assertNull(host.chosen)
        assertEquals(1, host.drains)
        assertEquals(setOf(small.id), models.facts.value.installed)
    }

    @Test
    fun deletingAnotherModelKeepsTheChoiceAndStillFreesIt() {
        host.installedIds += listOf(large.id, small.id)
        host.chosen = large.id
        models.delete(small.id)
        test.runCurrent()
        assertEquals(large.id, host.chosen)
        assertEquals(listOf("delete ${small.id}", "forget ${small.id}"), host.calls)
        assertEquals(1, host.drains)
    }

    @Test
    fun aDeleteWhoseFreeFailsStillStartsWaitingRecordings() {
        host.installedIds += small.id
        host.failForget = true
        models.delete(small.id)
        test.runCurrent()
        assertEquals(1, host.drains)
    }

    @Test
    fun deletingAKeptPartCancelsThatModelsScheduledDownloadFirst() {
        host.partials[large.id] = 900L
        models.refresh()
        test.runCurrent()
        assertEquals(mapOf(large.id to 900L), models.facts.value.partialBytes)
        models.delete(large.id)
        test.runCurrent()
        assertEquals(listOf("cancel ${large.id}", "discard ${large.id}"), host.calls)
        assertEquals(0, host.drains)
        assertTrue(models.facts.value.partialBytes.isEmpty())
    }

    @Test
    fun anIdOutsideTheCatalogIsIgnored() {
        models.delete("../whisper-base-en")
        models.download("../whisper-base-en")
        test.runCurrent()
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun aPhoneThatCannotRunTheEngineStartsNoDownload() {
        host.supported = false
        models.download(small.id)
        test.runCurrent()
        assertTrue(host.calls.isEmpty())
        assertEquals(false, models.facts.value.engineSupported)
    }

    @Test
    fun aRunningDownloadIsNotReplacedByAnother() {
        host.download.value = ModelDownloadStatus.Downloading(large.id)
        models.download(small.id)
        test.runCurrent()
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun aDownloadAndACancelReachTheTextLane() {
        models.download(small.id)
        models.cancelDownload()
        test.runCurrent()
        assertEquals(listOf("download ${small.id}", "cancel"), host.calls)
    }

    @Test
    fun anUnsupportedPhoneFollowsNoDownloads() {
        val unsupported = AndroidTextModels(supported = false, hostOf = { host }, scope = scope)
        unsupported.refresh()
        finish(small.id, unsupported)
        assertNull(host.chosen)
    }
}
