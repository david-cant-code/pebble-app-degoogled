package coredevices.coreapp

import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Pins the one piece of InferenceBoostService that upstream's equivalent
 * lacks and that nothing would catch if it silently disappeared: the
 * onTimeout override. When the shortService budget (about 3 minutes)
 * expires, a service that does not stop promptly ANRs the whole app, and
 * the failure only reproduces on transcriptions long enough to blow the
 * budget, so it would ship unnoticed. The service's start/stop behavior
 * itself is covered on-device by InferenceBoostLifecycleTest.
 */
class InferenceBoostServiceContractTest {

    @Test
    fun onTimeoutOverrideIsDeclared() {
        // getDeclaredMethod sees only methods declared on the class itself,
        // so inheriting the framework's no-op default fails this test.
        assertNotNull(
            InferenceBoostService::class.java.getDeclaredMethod("onTimeout", Int::class.javaPrimitiveType),
            "InferenceBoostService must override onTimeout or a long transcription ANRs the app",
        )
    }
}
