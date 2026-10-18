package com.anopticlabs.gravel.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import coredevices.pebble.ui.TopBarParams
import kotlinx.coroutines.flow.StateFlow
import org.koin.compose.getKoin

/**
 * The Notebook tab as the tab bar sees it. Only platforms with the Notebook bind it, so common
 * code looks it up with `getOrNull` and treats a missing binding as no tab.
 */
interface NotebookTab {
    /** False where the phone cannot run the Notebook; the Settings switch is hidden then. */
    val supported: Boolean

    /** The user's switch, and never true where [supported] is false. */
    val shown: StateFlow<Boolean>

    fun setShown(shown: Boolean)

    /**
     * [openTextModels] opens Settings > AI > LLM, offered while processing waits on the text model, and
     * [openSpeechSettings] Settings > AI > Speech Recognition, while it waits on the speech setting or the server.
     */
    @Composable
    fun Screen(topBarParams: TopBarParams, openTextModels: () -> Unit, openSpeechSettings: () -> Unit)
}

@Composable
fun rememberNotebookTab(): NotebookTab? {
    val koin = getKoin()
    return remember(koin) { koin.getOrNull<NotebookTab>() }
}
