package com.anopticlabs.gravel.notebook.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.anopticlabs.gravel.notebook.backup.LockAnswer

/** Whether [context]'s phone has a screen lock (PIN, pattern or password) to confirm; true when that cannot be read. */
fun hasScreenLock(context: Context): Boolean = context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure ?: true

/**
 * How the lock is asked for on Android [sdk]: API 30 on allows a strong biometric or the
 * credential (`setAllowedAuthenticators`), API 29 a biometric or the credential through
 * `setDeviceCredentialAllowed`, and API 28, where BiometricPrompt has no credential option, uses
 * the keyguard's confirmation activity (pie-release `KeyguardManager.createConfirmDeviceCredentialIntent`,
 * deprecated from android10-release in favor of `setDeviceCredentialAllowed`, itself replaced by
 * `setAllowedAuthenticators`). BiometricPrompt needs `USE_BIOMETRIC` (main, `BiometricPrompt.authenticate`).
 */
enum class LockMethod { AUTHENTICATORS, CREDENTIAL_ALLOWED, KEYGUARD_INTENT }

fun lockMethodFor(sdk: Int): LockMethod = when {
    sdk >= Build.VERSION_CODES.R -> LockMethod.AUTHENTICATORS
    sdk >= Build.VERSION_CODES.Q -> LockMethod.CREDENTIAL_ALLOWED
    else -> LockMethod.KEYGUARD_INTENT
}

/** A prompt error the user caused by backing out or leaving is a cancel; any other is a failure. */
fun lockAnswerForError(code: Int): LockAnswer = when (code) {
    BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> LockAnswer.CANCELED
    else -> LockAnswer.FAILED
}

fun lockAnswerForResult(resultCode: Int): LockAnswer = if (resultCode == Activity.RESULT_OK) LockAnswer.CONFIRMED else LockAnswer.CANCELED

/** The keyguard gives no confirmation intent when no credential is needed (pie-release `createConfirmDeviceCredentialIntent`); confirmed only while the phone has no lock. */
fun lockAnswerForNoIntent(secure: Boolean): LockAnswer = if (secure) LockAnswer.FAILED else LockAnswer.CONFIRMED

/** A function that asks Android to confirm the phone's screen lock and hands its answer to the function given with the request. */
@Composable
internal fun rememberScreenLockPrompt(): (title: String, subtitle: String, onAnswer: (LockAnswer) -> Unit) -> Unit {
    val context = LocalContext.current
    val waiting = remember { arrayOfNulls<(LockAnswer) -> Unit>(1) }
    val keyguard = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        waiting[0]?.invoke(lockAnswerForResult(result.resultCode))
        waiting[0] = null
    }
    return remember(context, keyguard) {
        { title, subtitle, onAnswer ->
            try {
                when (lockMethodFor(Build.VERSION.SDK_INT)) {
                    LockMethod.KEYGUARD_INTENT -> {
                        val keyguardManager = context.getSystemService(KeyguardManager::class.java)
                        @Suppress("DEPRECATION")
                        val intent: Intent? = keyguardManager?.createConfirmDeviceCredentialIntent(title, subtitle)
                        if (intent == null) {
                            onAnswer(lockAnswerForNoIntent(keyguardManager?.isDeviceSecure ?: true))
                        } else {
                            waiting[0] = onAnswer
                            keyguard.launch(intent)
                        }
                    }
                    else -> {
                        val builder = BiometricPrompt.Builder(context).setTitle(title).setSubtitle(subtitle)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            builder.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                        } else {
                            @Suppress("DEPRECATION")
                            builder.setDeviceCredentialAllowed(true)
                        }
                        builder.build().authenticate(
                            CancellationSignal(), context.mainExecutor,
                            object : BiometricPrompt.AuthenticationCallback() {
                                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onAnswer(LockAnswer.CONFIRMED)

                                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onAnswer(lockAnswerForError(errorCode))
                            },
                        )
                    }
                }
            } catch (e: Exception) {
                onAnswer(LockAnswer.FAILED)
            }
        }
    }
}
