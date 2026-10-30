package coredevices.coreapp

import com.russhwolf.settings.MapSettings
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.STTConfig
import coredevices.util.models.CactusSTTMode
import coredevices.util.models.WhisperModelCatalog
import coredevices.util.transcription.CactusModelPathProvider
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Host tests for [runSttModelMigration], the one-shot engine migration
 * every existing install runs on launch. It is destructive (deletes model
 * directories), config-mutating, and effectively runs once per user in
 * the field, so a regression is unrecoverable and invisible; these tests
 * cover the branches of the state machine: the sweep (stash, config
 * fallback, delete, generation-deduped notification), the stale-stash
 * drop, the deferred restore that waits for a usable model, and the
 * torn install that keeps the mode.
 */
class SttModelMigrationTest {

    private class FakeProvider(
        var incompatible: List<String> = emptyList(),
        var downloaded: List<String> = emptyList(),
        var installed: Set<String> = emptySet(),
        val failDeleteOf: Set<String> = emptySet(),
    ) : CactusModelPathProvider {
        val deleted = mutableListOf<String>()
        val calls = mutableListOf<String>()
        var beforeDelete: (String) -> Unit = {}
        override fun pruneOrphanedStaging() {
            calls += "prune"
        }
        override suspend fun getSTTModelPath(): String = error("unused in these tests")
        override suspend fun getLMModelPath(): String = error("unused in these tests")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String = error("unused in these tests")
        override fun isModelDownloaded(modelName: String): Boolean = modelName in installed
        override fun getDownloadedModels(): List<String> = downloaded
        override fun getIncompatibleModels(): List<String> = incompatible.also { calls += "incompatible" }
        override fun deleteModel(modelName: String) {
            beforeDelete(modelName)
            if (modelName in failDeleteOf) throw RuntimeException("delete failed (test)")
            deleted.add(modelName)
        }

        override fun getModelSizeBytes(modelName: String): Long = 0L
        override fun initTelemetry() {}
    }

    private val settings = MapSettings()
    private var notifications = 0
    private var recommended: String? = null

    /** Reads a config already stored in [settings] ahead of this one: a test with several rounds clears them first. */
    private fun holderWith(stt: STTConfig) =
        CoreConfigHolder(defaultValue = CoreConfig(sttConfig = stt), settings = settings, json = Json)

    private fun holderWith(mode: CactusSTTMode, modelName: String?) = holderWith(STTConfig(mode = mode, modelName = modelName))

    private fun run(provider: FakeProvider, holder: CoreConfigHolder) =
        runSttModelMigration(
            provider,
            settings,
            holder,
            recommended = { recommended ?: error("the recommendation was asked for") },
        ) { notifications++ }

    private fun assertNothingAnnouncedOrStashed(round: String) {
        assertEquals(0, notifications, round)
        assertFalse(settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY), round)
        assertFalse(settings.hasKey(STT_UPDATE_NOTIFIED_VERSION_KEY), round)
    }

    @Test
    fun sweepStashesLocalModeFallsBackToRemoteOnlyAndNotifies() {
        val provider = FakeProvider(incompatible = listOf("parakeet-tdt-0.6b-v3"))
        val holder = holderWith(CactusSTTMode.LocalFirst, "parakeet-tdt-0.6b-v3")
        run(provider, holder)

        assertEquals(CactusSTTMode.LocalFirst.id, settings.getInt(STT_MODE_BEFORE_UPDATE_KEY, -1))
        assertEquals(CactusSTTMode.RemoteOnly, holder.config.value.sttConfig.mode)
        assertNull(holder.config.value.sttConfig.modelName)
        assertEquals(listOf("parakeet-tdt-0.6b-v3"), provider.deleted)
        assertEquals(1, notifications)
        assertEquals(
            WhisperModelCatalog.GENERATION,
            settings.getStringOrNull(STT_UPDATE_NOTIFIED_VERSION_KEY),
        )
    }

    @Test
    fun sweepKeepsNonLocalModeButStillClearsTheModel() {
        // RebbleOnly never touches the local engine, so the sweep must not
        // yank the user to RemoteOnly; the stale model name still has to go.
        val provider = FakeProvider(incompatible = listOf("needle-pebble-ft"))
        val holder = holderWith(CactusSTTMode.RebbleOnly, "needle-pebble-ft")
        run(provider, holder)

        assertEquals(CactusSTTMode.RebbleOnly, holder.config.value.sttConfig.mode)
        assertNull(holder.config.value.sttConfig.modelName)
        assertEquals(CactusSTTMode.RebbleOnly.id, settings.getInt(STT_MODE_BEFORE_UPDATE_KEY, -1))
        assertEquals(1, notifications)
    }

    @Test
    fun notificationAndStashAreNotRepeatedOnLaterSweeps() {
        val provider = FakeProvider(incompatible = listOf("parakeet-tdt-0.6b-v3"))
        val holder = holderWith(CactusSTTMode.LocalFirst, "parakeet-tdt-0.6b-v3")
        run(provider, holder)
        // Directory deletion failed silently or reappeared: the second
        // launch sweeps again, but must neither re-notify for the same
        // catalog generation nor overwrite the stash with RemoteOnly.
        run(provider, holder)

        assertEquals(1, notifications)
        assertEquals(CactusSTTMode.LocalFirst.id, settings.getInt(STT_MODE_BEFORE_UPDATE_KEY, -1))
    }

    @Test
    fun deleteFailureDoesNotAbortTheSweep() {
        val provider = FakeProvider(
            incompatible = listOf("stubborn-dir", "needle-pebble-ft"),
            failDeleteOf = setOf("stubborn-dir"),
        )
        val holder = holderWith(CactusSTTMode.LocalOnly, null)
        run(provider, holder)

        assertEquals(listOf("needle-pebble-ft"), provider.deleted)
        assertEquals(1, notifications)
    }

    @Test
    fun staleStashIsDroppedWhenTheUserPickedTheirOwnMode() {
        settings.putInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.LocalFirst.id)
        val provider = FakeProvider(installed = setOf("whisper-base-en"), downloaded = listOf("whisper-base-en"))
        val holder = holderWith(CactusSTTMode.LocalOnly, "whisper-base-en")
        run(provider, holder)

        assertFalse(settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY))
        assertEquals(CactusSTTMode.LocalOnly, holder.config.value.sttConfig.mode)
        assertEquals("whisper-base-en", holder.config.value.sttConfig.modelName)
    }

    @Test
    fun restoreWaitsUntilAUsableModelIsActuallyInstalled() {
        settings.putInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.LocalFirst.id)
        // Listed but not in installed shape (torn install): not usable yet.
        val provider = FakeProvider(downloaded = listOf("whisper-base-en"))
        val holder = holderWith(CactusSTTMode.RemoteOnly, null)
        run(provider, holder)

        assertTrue(settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY), "the stash must survive until restore")
        assertEquals(CactusSTTMode.RemoteOnly, holder.config.value.sttConfig.mode)
        assertNull(holder.config.value.sttConfig.modelName)
    }

    @Test
    fun restoreCompletesOnceAUsableModelExists() {
        settings.putInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.LocalFirst.id)
        val provider = FakeProvider(
            downloaded = listOf("whisper-base-en"),
            installed = setOf("whisper-base-en"),
        )
        val holder = holderWith(CactusSTTMode.RemoteOnly, null)
        run(provider, holder)

        assertFalse(settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY))
        assertEquals(CactusSTTMode.LocalFirst, holder.config.value.sttConfig.mode)
        assertEquals("whisper-base-en", holder.config.value.sttConfig.modelName)
    }

    @Test
    fun cleanInstallIsANoop() {
        val provider = FakeProvider()
        val holder = holderWith(CactusSTTMode.RemoteOnly, null)
        run(provider, holder)

        assertEquals(0, notifications)
        assertFalse(settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY))
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    fun aStaleTextModelDirectoryLeavesDictationAlone() {
        val provider = FakeProvider(incompatible = listOf("qwen3-1_7b-q4_k_m"), installed = setOf("whisper-base-en"))
        val holder = holderWith(CactusSTTMode.LocalFirst, "whisper-base-en")
        run(provider, holder)

        assertEquals(listOf("qwen3-1_7b-q4_k_m"), provider.deleted)
        assertEquals(CactusSTTMode.LocalFirst, holder.config.value.sttConfig.mode)
        assertEquals("whisper-base-en", holder.config.value.sttConfig.modelName)
        assertFalse(settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY))
        assertEquals(0, notifications)
    }

    @Test
    fun aTornUnselectedSpeechModelLeavesDictationAlone() {
        val provider = FakeProvider(incompatible = listOf("whisper-small-en"), installed = setOf("whisper-base-en"))
        val holder = holderWith(CactusSTTMode.LocalOnly, "whisper-base-en")
        run(provider, holder)

        assertEquals(listOf("whisper-small-en"), provider.deleted)
        assertEquals(CactusSTTMode.LocalOnly, holder.config.value.sttConfig.mode)
        assertEquals("whisper-base-en", holder.config.value.sttConfig.modelName)
        assertEquals(0, notifications)
    }

    @Test
    fun aTornSelectedSpeechModelHandsTheSelectionOnAndKeepsTheMode() {
        val others = listOf("whisper-tiny-en", "whisper-base")
        for (mode in CactusSTTMode.entries) {
            for ((pick, expected) in listOf("whisper-tiny-en" to "whisper-tiny-en", "whisper-small-en" to "whisper-base")) {
                val round = "$mode $pick"
                recommended = pick
                settings.clear()
                val provider = FakeProvider(incompatible = listOf("whisper-base-en"), downloaded = others, installed = others.toSet())
                val before = STTConfig(mode, "whisper-base-en", spokenLanguage = "de", serverUrl = "https://a.example.net/inference")
                val holder = holderWith(before)
                val selectedAtDelete = mutableListOf<String?>()
                provider.beforeDelete = { selectedAtDelete += holder.config.value.sttConfig.modelName }
                run(provider, holder)

                assertEquals(before.copy(modelName = expected), holder.config.value.sttConfig, round)
                assertEquals(listOf("whisper-base-en"), provider.deleted, round)
                assertEquals(listOf<String?>(expected), selectedAtDelete, round)
                assertNothingAnnouncedOrStashed(round)
            }
        }
    }

    @Test
    fun aTornSelectedSpeechModelWithNoOtherInstalledClearsTheSelectionAndKeepsTheMode() {
        recommended = "whisper-small-en"
        for (mode in CactusSTTMode.entries) {
            settings.clear()
            // Listed but not in installed shape: the torn model itself and a second torn one.
            val provider = FakeProvider(
                incompatible = listOf("whisper-base-en", "whisper-tiny"),
                downloaded = listOf("whisper-base-en", "whisper-tiny"),
            )
            val before = STTConfig(mode, "whisper-base-en", spokenLanguage = "de", serverUrl = "https://a.example.net/inference")
            val holder = holderWith(before)
            run(provider, holder)

            assertEquals(before.copy(modelName = null), holder.config.value.sttConfig, "$mode")
            assertEquals(listOf("whisper-base-en", "whisper-tiny"), provider.deleted, "$mode")
            assertNothingAnnouncedOrStashed("$mode")
        }
    }

    @Test
    fun aTornSelectedSpeechModelMovesTheSelectionAndAStaleStashIsDropped() {
        recommended = "whisper-small-en"
        settings.putInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.LocalFirst.id)
        val provider = FakeProvider(
            incompatible = listOf("whisper-base-en"),
            downloaded = listOf("whisper-tiny-en"),
            installed = setOf("whisper-tiny-en"),
        )
        val holder = holderWith(CactusSTTMode.LocalOnly, "whisper-base-en")
        run(provider, holder)

        assertEquals(CactusSTTMode.LocalOnly, holder.config.value.sttConfig.mode)
        assertEquals("whisper-tiny-en", holder.config.value.sttConfig.modelName)
        assertFalse(settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY))
        assertEquals(0, notifications)
    }

    @Test
    fun aTornModelWithNoInstalledSelectionLeavesTheModeAndTheSelection() {
        for (mode in CactusSTTMode.entries) {
            for (selected in listOf(null, "whisper-small-en")) {
                for (torn in listOf("whisper-base-en", "qwen3-1_7b-q4_k_m")) {
                    val round = "$mode $selected $torn"
                    settings.clear()
                    val provider = FakeProvider(incompatible = listOf(torn))
                    val holder = holderWith(mode, selected)
                    run(provider, holder)

                    assertEquals(mode, holder.config.value.sttConfig.mode, round)
                    assertEquals(selected, holder.config.value.sttConfig.modelName, round)
                    assertEquals(listOf(torn), provider.deleted, round)
                    assertNothingAnnouncedOrStashed(round)
                }
            }
        }
    }

    @Test
    fun aPreviousEngineDirectoryBesideATornSelectedModelStillResetsDictation() {
        for (mode in CactusSTTMode.entries) {
            settings.clear()
            notifications = 0
            val provider = FakeProvider(
                incompatible = listOf("whisper-base-en", "parakeet-tdt-0.6b-v3"),
                downloaded = listOf("whisper-small-en"),
                installed = setOf("whisper-small-en"),
            )
            val holder = holderWith(mode, "whisper-base-en")
            run(provider, holder)

            val kept = mode.takeUnless { it.usesLocalCactus() } ?: CactusSTTMode.RemoteOnly
            assertEquals(kept, holder.config.value.sttConfig.mode, "$mode")
            assertNull(holder.config.value.sttConfig.modelName, "$mode")
            assertEquals(mode != CactusSTTMode.RemoteOnly, settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY), "$mode")
            assertEquals(mode.id, settings.getInt(STT_MODE_BEFORE_UPDATE_KEY, mode.id), "$mode")
            assertEquals(listOf("whisper-base-en", "parakeet-tdt-0.6b-v3"), provider.deleted, "$mode")
            assertEquals(1, notifications, "$mode")
        }
    }

    @Test
    fun aPreviousEngineDirectoryBesideAnInstalledSelectionLeavesDictationAlone() {
        for (mode in CactusSTTMode.entries) {
            settings.clear()
            val provider = FakeProvider(
                incompatible = listOf("parakeet-tdt-0.6b-v3"),
                downloaded = listOf("whisper-base-en"),
                installed = setOf("whisper-base-en"),
            )
            val holder = holderWith(mode, "whisper-base-en")
            run(provider, holder)

            assertEquals(mode, holder.config.value.sttConfig.mode, "$mode")
            assertEquals("whisper-base-en", holder.config.value.sttConfig.modelName, "$mode")
            assertEquals(listOf("parakeet-tdt-0.6b-v3"), provider.deleted, "$mode")
            assertNothingAnnouncedOrStashed("$mode")
        }
    }

    @Test
    fun aSweepWithNothingToResetIsNotAnnounced() {
        for (stale in listOf("qwen3-1_7b-q4_k_m", "parakeet-tdt-0.6b-v3")) {
            for (mode in CactusSTTMode.entries.filterNot { it.usesLocalCactus() }) {
                settings.clear()
                val provider = FakeProvider(incompatible = listOf(stale))
                val holder = holderWith(mode, null)
                run(provider, holder)

                assertEquals(listOf(stale), provider.deleted, "$stale $mode")
                assertEquals(mode, holder.config.value.sttConfig.mode, "$stale $mode")
                assertNothingAnnouncedOrStashed("$stale $mode")
            }
        }
    }

    @Test
    fun aSweepThatResetsNothingLetsAPendingRestoreComplete() {
        for (stale in listOf("qwen3-1_7b-q4_k_m", "parakeet-tdt-0.6b-v3")) {
            settings.clear()
            settings.putInt(STT_MODE_BEFORE_UPDATE_KEY, CactusSTTMode.LocalFirst.id)
            val provider = FakeProvider(
                incompatible = listOf(stale),
                downloaded = listOf("whisper-base-en"),
                installed = setOf("whisper-base-en"),
            )
            val holder = holderWith(CactusSTTMode.RemoteOnly, null)
            run(provider, holder)

            assertFalse(settings.hasKey(STT_MODE_BEFORE_UPDATE_KEY), stale)
            assertEquals(CactusSTTMode.LocalFirst, holder.config.value.sttConfig.mode, stale)
            assertEquals("whisper-base-en", holder.config.value.sttConfig.modelName, stale)
            assertEquals(0, notifications, stale)
        }
    }

    @Test
    fun stagingLeftoversArePrunedBeforeTheSweep() {
        val provider = FakeProvider()
        run(provider, holderWith(CactusSTTMode.RemoteOnly, null))
        assertEquals(listOf("prune", "incompatible"), provider.calls)
    }
}
