package com.anopticlabs.gravel.notebook.ui

import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest

/**
 * Asks the keyboard of every text field in [content], dialogs included, not to learn from what is
 * typed there (`IME_FLAG_NO_PERSONALIZED_LEARNING`); a keyboard that ignores the flag still learns.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun NoKeyboardLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(interceptor = noLearning, content = content)
}

// One instance: a different one passed while a session runs restarts it (ui-android 1.11.1, InterceptPlatformTextInput).
@OptIn(ExperimentalComposeUiApi::class)
private val noLearning = PlatformTextInputInterceptor { request, nextHandler ->
    nextHandler.startInputMethod(
        PlatformTextInputMethodRequest { outAttributes ->
            request.createInputConnection(outAttributes).also {
                outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            }
        },
    )
}
