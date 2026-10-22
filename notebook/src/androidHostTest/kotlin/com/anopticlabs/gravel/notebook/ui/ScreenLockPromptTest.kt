package com.anopticlabs.gravel.notebook.ui

import android.app.Activity
import android.hardware.biometrics.BiometricPrompt
import com.anopticlabs.gravel.notebook.backup.LockAnswer
import kotlin.test.Test
import kotlin.test.assertEquals

/** How the screen-lock prompt is chosen per Android version and how its endings map to answers. */
class ScreenLockPromptTest {

    @Test
    fun eachAndroidVersionGetsItsOwnWayToAsk() {
        assertEquals(LockMethod.KEYGUARD_INTENT, lockMethodFor(28))
        assertEquals(LockMethod.CREDENTIAL_ALLOWED, lockMethodFor(29))
        assertEquals(LockMethod.AUTHENTICATORS, lockMethodFor(30))
        assertEquals(LockMethod.AUTHENTICATORS, lockMethodFor(36))
    }

    @Test
    fun onlyASuccessConfirmsBackingOutCancelsAndAnyOtherErrorFails() {
        assertEquals(LockAnswer.CANCELED, lockAnswerForError(BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED))
        assertEquals(LockAnswer.CANCELED, lockAnswerForError(BiometricPrompt.BIOMETRIC_ERROR_CANCELED))
        assertEquals(LockAnswer.FAILED, lockAnswerForError(BiometricPrompt.BIOMETRIC_ERROR_LOCKOUT))
        assertEquals(LockAnswer.FAILED, lockAnswerForError(BiometricPrompt.BIOMETRIC_ERROR_HW_UNAVAILABLE))
        assertEquals(LockAnswer.CONFIRMED, lockAnswerForResult(Activity.RESULT_OK))
        assertEquals(LockAnswer.CANCELED, lockAnswerForResult(Activity.RESULT_CANCELED))
    }

    @Test
    fun noConfirmationIntentConfirmsOnlyWhileThePhoneHasNoLock() {
        assertEquals(LockAnswer.CONFIRMED, lockAnswerForNoIntent(secure = false))
        assertEquals(LockAnswer.FAILED, lockAnswerForNoIntent(secure = true))
    }
}
