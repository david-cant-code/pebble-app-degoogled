package com.anopticlabs.gravel.notebook.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.Choreographer
import android.view.Window
import android.view.WindowManager
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.SecureFlagPolicy
import java.util.WeakHashMap

/**
 * Counts the Notebook screens holding a window secure. The flag goes on with the first hold and
 * off [NotebookWindowSecurity.HOLD_FRAMES] frames after the last one ends, unless a new hold came
 * meanwhile or the window was already secure before the first. Main thread only.
 */
internal class SecureHolds<W : Any>(
    private val isSecure: (W) -> Boolean,
    private val setSecure: (W, Boolean) -> Unit,
    private val afterFrames: (Int, () -> Unit) -> Unit,
) {
    private class Hold(val wasSecure: Boolean) {
        var count = 0
        var releases = 0
    }

    private val holds = WeakHashMap<W, Hold>()

    fun acquire(window: W) {
        val hold = holds.getOrPut(window) { Hold(isSecure(window)) }
        hold.count++
        if (!hold.wasSecure) setSecure(window, true)
    }

    fun release(window: W) {
        val hold = holds[window] ?: return
        if (--hold.count > 0) return
        val release = ++hold.releases
        afterFrames(NotebookWindowSecurity.HOLD_FRAMES) {
            if (hold.count > 0 || hold.releases != release || holds[window] !== hold) return@afterFrames
            holds.remove(window)
            if (!hold.wasSecure) setSecure(window, false)
        }
    }
}

object NotebookWindowSecurity {
    /** Set only by the debug build's adb receiver, so Notebook screens can be captured while testing. */
    var liftedForDebug: Boolean by mutableStateOf(false)

    // The window server applies a flag change in a later transaction (android16-release,
    // WindowState.setSecureLocked), so content waits this many frames after the flag goes on, and
    // the flag stays this many frames after the content goes.
    internal const val HOLD_FRAMES = 2

    internal val holds = SecureHolds<Window>(
        isSecure = { it.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0 },
        setSecure = { window, secure ->
            if (secure) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        },
        afterFrames = ::afterFrames,
    )

    private fun afterFrames(frames: Int, action: () -> Unit) {
        if (frames <= 0) action() else Choreographer.getInstance().postFrameCallback { afterFrames(frames - 1, action) }
    }
}

/**
 * Shows [content] [NotebookWindowSecurity.HOLD_FRAMES] frames after setting FLAG_SECURE on its
 * activity window, and the flag outlasts it by as many (see [SecureHolds]); without a window it
 * shows nothing. Its text fields ask the keyboard not to learn ([NoKeyboardLearning]).
 */
@Composable
internal fun NotebookSecureContent(content: @Composable () -> Unit) {
    val lifted = NotebookWindowSecurity.liftedForDebug
    val window = LocalView.current.context.findActivity()?.window
    var ready by remember(window, lifted) { mutableStateOf(lifted) }
    if (!lifted) {
        if (window == null) return
        DisposableEffect(window) {
            NotebookWindowSecurity.holds.acquire(window)
            onDispose { NotebookWindowSecurity.holds.release(window) }
        }
        LaunchedEffect(window) {
            repeat(NotebookWindowSecurity.HOLD_FRAMES) { withFrameNanos { } }
            ready = true
        }
    }
    if (ready) NoKeyboardLearning(content)
}

/** Dialogs, sheets and menus are windows of their own, so each sets FLAG_SECURE itself unless the debug switch lifted it. */
private fun notebookSecurePolicy() = if (NotebookWindowSecurity.liftedForDebug) SecureFlagPolicy.Inherit else SecureFlagPolicy.SecureOn

internal fun notebookDialogProperties(fullScreen: Boolean = false, dismissOnClickOutside: Boolean = true) = DialogProperties(
    dismissOnClickOutside = dismissOnClickOutside,
    usePlatformDefaultWidth = !fullScreen,
    securePolicy = notebookSecurePolicy(),
)

internal fun notebookSheetProperties() = ModalBottomSheetProperties(
    securePolicy = notebookSecurePolicy(),
    shouldDismissOnBackPress = true,
    shouldDismissOnClickOutside = true,
)

internal fun notebookMenuProperties() = PopupProperties(focusable = true, securePolicy = notebookSecurePolicy())

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
