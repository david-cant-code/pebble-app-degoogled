package com.anopticlabs.gravel.models

import androidx.test.platform.app.InstrumentationRegistry
import coredevices.coreapp.model.WhisperModelProvider
import coredevices.util.Platform
import io.ktor.client.HttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.io.File
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The app's model provider against its real model directory: the startup
 * sweep's staging prune, the speech-only installed check, and dictation's
 * refusal of a text model. The last two need an installed text model
 * (`LlmCatalogModelsDeviceTest` stages them) and are skipped without one,
 * so a regression cannot start a download.
 */
class ModelProviderDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun provider(configured: String? = null): WhisperModelProvider {
        val koin = KoinPlatform.getKoin()
        return WhisperModelProvider(context, koin.get<HttpClient>(), koin.get<Platform>()) { configured }
    }

    private fun installedTextModel(): LlmModel {
        val installed = LlmModelCatalog.MODELS.firstOrNull {
            File(context.filesDir, "models/${it.id}/${it.fileName}").length() == it.sizeBytes
        }
        Assume.assumeTrue("no text model installed; run LlmCatalogModelsDeviceTest first", installed != null)
        return installed!!
    }

    @Test
    fun theProviderPrunesStagingLeftovers() {
        val staging = File(context.filesDir, "models/.staging").also { it.mkdirs() }
        val orphan = File(staging, "retired-model.0000000000000000.partial").apply { writeText("x") }
        val orphanDir = File(staging, "retired-dir").apply { mkdirs() }

        provider().pruneOrphanedStaging()

        assertFalse(orphan.exists())
        assertFalse(orphanDir.exists())
    }

    @Test
    fun anInstalledTextModelIsNeitherASpeechModelNorSwept() {
        val text = installedTextModel()
        val provider = provider()
        assertFalse(provider.isModelDownloaded(text.id))
        assertFalse(text.id in provider.getIncompatibleModels())
    }

    @Test
    fun dictationRefusesATextModel() {
        val text = installedTextModel()
        assertFailsWith<IllegalStateException> { runBlocking { provider(configured = text.id).getSTTModelPath() } }
    }
}
