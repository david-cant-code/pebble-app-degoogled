package com.anopticlabs.gravel.models

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.anopticlabs.gravel.llm.ChatMessage
import com.anopticlabs.gravel.llm.LlmEngineClient
import com.anopticlabs.gravel.llm.LlmGenerateStats
import com.anopticlabs.gravel.llm.StopReason
import com.anopticlabs.gravel.llm.isLlmSupported
import coredevices.util.transcription.CactusModelPathProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assume
import org.junit.Test
import org.koin.mp.KoinPlatform
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * On-device check of each text model in the catalog: the pinned file
 * resolves through the app's model provider (its load-time re-hash against
 * the pin), the engine loads it and renders a conversation in the
 * catalog's chat format, and a prompt ending in the catalog's suffix is
 * answered directly within a short token budget.
 *
 * Needs the pinned files: a missing install is copied once, through the
 * shell, from `/data/local/tmp/gravel-models/<fileName>` into the app's
 * model directory, and a model with neither is skipped. Never downloads.
 * Run on its own against a persistent install:
 *   adb push <fileName> /data/local/tmp/gravel-models/<fileName>
 *   adb shell am instrument -w \
 *     -e class com.anopticlabs.gravel.models.LlmCatalogModelsDeviceTest \
 *     com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class LlmCatalogModelsDeviceTest {

    private companion object {
        const val TAG = "LlmCatalogModels"
        const val STAGING = "/data/local/tmp/gravel-models"
        const val CONTEXT = 2048
        const val THREADS = 4
        const val MAX_TOKENS = 128
        const val NOTE_PROMPT = "Reply with one JSON object with the keys \"title\" (a short title) and \"tags\" " +
            "(a list of one to three lowercase words) for this note, and nothing else: buy milk and eggs on the way home"
    }

    private fun log(line: String) {
        Log.i(TAG, line)
        println("[$TAG] $line")
    }

    @Test
    fun everyCatalogTextModelHasACase() {
        assertEquals(setOf("qwen3-1_7b-q4_k_m", "qwen3_5-0_8b-q5_k_m"), LlmModelCatalog.MODELS.map { it.id }.toSet())
    }

    @Test
    fun qwen3With1_7bParameters() = check("qwen3-1_7b-q4_k_m")

    @Test
    fun qwen3_5With0_8bParameters() = check("qwen3_5-0_8b-q5_k_m")

    private fun check(id: String) {
        val model = LlmModelCatalog.byId(id)!!
        assertTrue(LlmEngineClient.isAttached, "the engine client was not attached in the application's onCreate")
        Assume.assumeTrue("engine unsupported on this device", isLlmSupported())
        stage(model)

        val provider = KoinPlatform.getKoin().get<CactusModelPathProvider>()
        val start = SystemClock.elapsedRealtime()
        val path = runBlocking { provider.getModelPath(id, allowReinstall = false) }
        log("$id resolved and verified in ${SystemClock.elapsedRealtime() - start} ms")

        val handle = LlmEngineClient.init(File(path), CONTEXT)
        try {
            val rendered = LlmEngineClient.formatChat(
                handle,
                listOf(ChatMessage("system", "You write JSON."), ChatMessage("user", "Say hi.")),
            )
            when (model.chatFormat) {
                ChatFormat.ChatMl -> assertEquals(
                    "<|im_start|>system\nYou write JSON.<|im_end|>\n" +
                        "<|im_start|>user\nSay hi.<|im_end|>\n<|im_start|>assistant\n",
                    rendered,
                )
            }

            val prompt = LlmEngineClient.formatChat(handle, listOf(ChatMessage("user", NOTE_PROMPT))) + model.promptSuffix
            val stats = LlmGenerateStats()
            val answer = LlmEngineClient.generate(
                handle, prompt, MAX_TOKENS, THREADS, callId = SystemClock.elapsedRealtimeNanos(), stats = stats,
            )
            log(
                "$id answer=${answer.replace("\n", "\\n")} prompt=${stats.promptTokens} tokens/${stats.prefillMillis} ms " +
                    "generated=${stats.generatedTokens} tokens/${stats.decodeMillis} ms stop=${stats.stopReason}",
            )
            assertEquals(StopReason.END_OF_GENERATION, stats.stopReason, "the answer did not end within $MAX_TOKENS tokens")
            assertTrue(answer.contains("\"title\""), "the answer is not the requested JSON")
            assertFalse(answer.contains("<think>") || answer.contains("</think>"), "the model reasoned aloud")
        } finally {
            LlmEngineClient.free(handle)
        }
    }

    /** Copies the staged file into the model's install path unless an install of the pinned size is there. */
    private fun stage(model: LlmModel) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val installed = File(context.filesDir, "models/${model.id}/${model.fileName}")
        if (installed.isFile && installed.length() == model.sizeBytes) return
        val source = "$STAGING/${model.fileName}"
        val size = shell("stat -c %s $source").toLongOrNull()
        Assume.assumeTrue("$source not staged on the device; tests never download", size == model.sizeBytes)
        val partial = File(context.cacheDir, "${model.fileName}.copying")
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $source")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> partial.outputStream().use { input.copyTo(it) } }
        if (partial.length() != model.sizeBytes) {
            partial.delete()
            fail("the staged ${model.fileName} did not copy whole")
        }
        // The directory appears only with its file: an empty one is a torn install to the startup sweep.
        installed.parentFile!!.mkdirs()
        assertTrue(partial.renameTo(installed))
        log("staged ${installed.length()} bytes of ${model.id} into the app's model directory")
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString().trim() }
    }
}
