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
 * pin all three branches of the state machine: the sweep (stash, config
 * fallback, delete, generation-deduped notification), the stale-stash
 * drop, and the deferred restore that waits for a usable model.
 */
class SttModelMigrationTest {

    private class FakeProvider(
        var incompatible: List<String> = emptyList(),
        var downloaded: List<String> = emptyList(),
        var installed: Set<String> = emptySet(),
        val failDeleteOf: Set<String> = emptySet(),
        var vadInstalled: Boolean = false,
    ) : CactusModelPathProvider {
        val deleted = mutableListOf<String>()
        override fun isVadModelInstalled(): Boolean = vadInstalled
        override suspend fun getSTTModelPath(): String = error("unused in these tests")
        override suspend fun getLMModelPath(): String = error("unused in these tests")
        override suspend fun getModelPath(modelId: String, allowReinstall: Boolean): String = error("unused in these tests")
        override fun isModelDownloaded(modelName: String): Boolean = modelName in installed
        override fun getDownloadedModels(): List<String> = downloaded
        override fun getIncompatibleModels(): List<String> = incompatible
        override fun deleteModel(modelName: String) {
            if (modelName in failDeleteOf) throw RuntimeException("delete failed (test)")
            deleted.add(modelName)
        }

        override fun getModelSizeBytes(modelName: String): Long = 0L
        override fun initTelemetry() {}
    }

    private val settings = MapSettings()
    private var notifications = 0

    private fun holderWith(mode: CactusSTTMode, modelName: String?) = CoreConfigHolder(
        defaultValue = CoreConfig(sttConfig = STTConfig(mode = mode, modelName = modelName)),
        settings = settings,
        json = Json,
    )

    private fun run(provider: FakeProvider, holder: CoreConfigHolder) =
        runSttModelMigration(provider, settings, holder) { notifications++ }

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
    fun detectorDownloadIsNeededOnlyWithASpeechModelAndNoDetector() {
        // No speech model: nothing to trim for, no download.
        assertFalse(vadDownloadNeeded(FakeProvider()))
        // A stale directory that is not an installed catalog model does not count.
        assertFalse(vadDownloadNeeded(FakeProvider(downloaded = listOf("parakeet-tdt-0.6b-v3"))))
        // Installed speech model, no detector: fetch it.
        assertTrue(
            vadDownloadNeeded(
                FakeProvider(downloaded = listOf("whisper-base-en"), installed = setOf("whisper-base-en")),
            ),
        )
        // Detector already present: nothing to do.
        assertFalse(
            vadDownloadNeeded(
                FakeProvider(
                    downloaded = listOf("whisper-base-en"),
                    installed = setOf("whisper-base-en"),
                    vadInstalled = true,
                ),
            ),
        )
    }
}
