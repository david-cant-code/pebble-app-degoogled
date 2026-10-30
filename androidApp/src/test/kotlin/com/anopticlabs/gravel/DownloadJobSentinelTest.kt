package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The download job reports a finished download once, on its success path: a speech model into the counter the
 * Notebook's watch follows, and any model to the text models' follower. The job is a JobService and its manager
 * reads the system's job list, so no host test runs either. Checked as text, lines that start with `//` left out.
 */
class DownloadJobSentinelTest {

    private fun code(path: String) =
        TrackedTree.file(path).readLines().filterNot { it.trim().startsWith("//") }.joinToString("\n")

    private val job by lazy { code("util/src/androidMain/kotlin/coredevices/util/models/ModelDownloadManager.android.kt") }

    @Test
    fun theJobReportsADownloadRightAfterItsSuccess() {
        assertEquals(1, Regex("""speechInstalls\s*\.\s*downloaded\(""").findAll(job).count(), "calls of speechInstalls.downloaded")
        assertEquals(1, Regex("""textDownloads\s*\.\s*finished\(""").findAll(job).count(), "calls of textDownloads.finished")
        assertTrue(
            Regex(
                """updateDownloadStatus\(modelSlug, ModelDownloadStatus\.Idle\)\n\s*speechInstalls\.downloaded\(modelSlug\)\n\s*textDownloads\.finished\(modelSlug\)\n\s*\} catch \(e: TimeoutCancellationException\)""",
            ).containsMatchIn(job),
            "the job no longer reports a download between its success status and its first catch",
        )
    }

    @Test
    fun theJobAndTheWatchShareOneCounter() {
        assertTrue(
            Regex("""(?m)^\s*private val speechInstalls: SpeechModelInstalls by inject\(\)\s*$""").containsMatchIn(job),
            "the job no longer takes its counter from the app's graph",
        )
        val appModule = code("composeApp/src/androidMain/kotlin/coredevices/coreapp/di/androidDefaultModule.kt")
        assertTrue(
            Regex("""(?m)^\s*singleOf\(::SpeechModelInstalls\)\s*$""").containsMatchIn(appModule),
            "androidDefaultModule no longer registers SpeechModelInstalls as a single",
        )
        val engines = code("composeApp/src/androidMain/kotlin/com/anopticlabs/gravel/app/NotebookEngines.kt")
        assertTrue(
            Regex("""single\s*\{\s*SpeechSettingWatch\(speechWatched\(get\(\), get\(\)\),""").containsMatchIn(engines),
            "notebookEnginesModule no longer gives the watch the graph's config and counter",
        )
    }

    @Test
    fun theJobsFollowerIsTheTextModelsSingle() {
        assertTrue(
            Regex("""(?m)^\s*private val textDownloads: TextDownloadFollower by inject\(\)\s*$""").containsMatchIn(job),
            "the job no longer takes its follower from the app's graph",
        )
        val textModels = code("composeApp/src/androidMain/kotlin/com/anopticlabs/gravel/app/AndroidTextModels.kt")
        assertTrue(
            Regex(
                """single\s*\{\s*AndroidTextModels\(.*?\n\s*\}\s*binds arrayOf\(TextModels::class, TextDownloadFollower::class\)""",
                RegexOption.DOT_MATCHES_ALL,
            ).containsMatchIn(textModels),
            "textModelsModule no longer binds its AndroidTextModels single as the job's follower",
        )
        val app = code("composeApp/src/androidMain/kotlin/coredevices/coreapp/MainApplication.kt")
        assertTrue(
            Regex("""startKoin \{\s*modules\(.*?\n\s*textModelsModule,\s*\n""", RegexOption.DOT_MATCHES_ALL).containsMatchIn(app),
            "MainApplication no longer loads textModelsModule into the app's graph",
        )
    }

    /** A job still scheduled when the manager is built is the system's to run: a download that waits for a network. */
    @Test
    fun theManagersJobCancelsAreInItsThreeRequestFunctions() {
        val manager = job.substringAfter("actual class ModelDownloadManager(").substringBefore("internal class RunningDownloads")
        assertFalse(Regex("""\binit\s*\{""").containsMatchIn(manager), "the manager runs a block at construction")
        assertFalse(manager.contains("cancelAll"), "the manager cancels every job of the app somewhere")
        val owners = Regex("""jobScheduler\s*\.\s*cancel\(""").findAll(manager)
            .map { call -> Regex("""fun (\w+)\(""").findAll(manager.substring(0, call.range.first)).last().groupValues[1] }
            .toList()
        assertEquals(listOf("scheduleDownload", "cancelDownload", "cancelDownloadOf"), owners, "the functions that cancel a job")
        // The lines that take a canceled download off its lane, and the two the lanes start from the scheduled jobs with.
        for (line in listOf(
            "val lane = laneStatuses.getValue(laneOf(modelInfo.slug))",
            "job.slug?.takeIf { it != modelInfo.slug }?.let { other -> lane.update { laneStatusAfterCancel(it, other) } }",
            "laneStatuses.getValue(role).update { laneStatusAfterCancel(it) }",
            "laneStatuses.getValue(laneOf(slug)).update { laneStatusAfterCancel(it, slug) }",
            "pendingDownloads().let { pending ->",
            "ModelRole.entries.associateWith { MutableStateFlow(laneStatusAtStartup(it, pending)) }",
        )) {
            assertEquals(1, manager.lines().count { it.trim() == line }, "lines of the manager that read: $line")
        }
        // Their declarations and the speech lane's one-line delegate: the manager itself calls neither anywhere else.
        assertEquals(3, Regex("""\bcancelDownload\(""").findAll(manager).count(), "mentions of cancelDownload( in the manager")
        assertEquals(1, Regex("""\bcancelDownloadOf\(""").findAll(manager).count(), "mentions of cancelDownloadOf( in the manager")
    }
}
