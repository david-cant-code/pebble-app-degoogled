package coredevices.coreapp

import coredevices.util.transcription.InferenceBoost
import org.junit.Test
import org.koin.mp.KoinPlatform
import kotlin.test.assertIs

/**
 * Smoke test against the live app graph (MainApplication's Koin): the
 * InferenceBoost seam must resolve to the fork's Android implementation,
 * not fall through to utilModule's no-op fallback. A JVM koinApplication
 * cannot check this binding because androidDefaultModule needs a real
 * Context to instantiate.
 */
class InferenceBoostSeamTest {

    @Test
    fun liveGraphResolvesTheAndroidBoost() {
        assertIs<AndroidInferenceBoost>(KoinPlatform.getKoin().get<InferenceBoost>())
    }
}
