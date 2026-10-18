package com.anopticlabs.gravel.app

import androidx.compose.runtime.Composable
import com.anopticlabs.gravel.notebook.alarms.NotebookPreferences
import com.anopticlabs.gravel.notebook.isNotebookSupported
import com.anopticlabs.gravel.notebook.ui.NotebookLink
import com.anopticlabs.gravel.notebook.ui.NotebookLinkToken
import com.anopticlabs.gravel.notebook.ui.NotebookNavigator
import com.anopticlabs.gravel.notebook.ui.NotebookScreen
import com.anopticlabs.gravel.notebook.ui.isNotebookLinkAction
import com.anopticlabs.gravel.notebook.ui.notebookLinkFor
import com.anopticlabs.gravel.ui.NotebookTab
import coredevices.pebble.PebbleDeepLinkHandler
import coredevices.pebble.ui.PebbleNavBarRoutes
import coredevices.pebble.ui.TopBarParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.koin.dsl.module

/** Gravel: the Notebook tab, shown by the user's switch on phones that can run the Notebook. */
class AndroidNotebookTab(
    override val supported: Boolean,
    savedShown: () -> Boolean,
    private val saveShown: (Boolean) -> Unit,
) : NotebookTab {
    private val _shown = MutableStateFlow(supported && savedShown())
    override val shown: StateFlow<Boolean> = _shown.asStateFlow()

    override fun setShown(shown: Boolean) {
        saveShown(shown)
        _shown.value = supported && shown
    }

    @Composable
    override fun Screen(topBarParams: TopBarParams, openTextModels: () -> Unit, openSpeechSettings: () -> Unit) {
        NotebookScreen(
            topBarParams,
            openTextModels,
            openSpeechSettings = openSpeechSettings,
        )
    }
}

/**
 * Gravel: the Notebook's notification taps, for MainActivity. A tap is taken whether or not it
 * routes, so its data URI never reaches the link handlers; it routes only with the Notebook's
 * link token (see [notebookLinkFor]) and while the tab is shown.
 */
class NotebookIntents(
    private val tabShown: () -> Boolean,
    private val token: () -> String,
    private val navigator: NotebookNavigator,
    private val openTab: () -> Unit,
) {
    /** True when [action] is a Notebook tap, which the caller then treats as handled. */
    fun take(
        action: String?,
        readString: (String) -> String?,
        readInt: (String) -> Int?,
        readLong: (String) -> Long?,
        launchedFromHistory: Boolean,
    ): Boolean {
        if (!isNotebookLinkAction(action)) return false
        val link: NotebookLink = notebookLinkFor(action, readString, readInt, readLong, launchedFromHistory, token) ?: return true
        if (tabShown()) {
            navigator.open(link)
            openTab()
        }
        return true
    }
}

val notebookTabModule = module {
    single<NotebookTab> {
        val prefs = get<NotebookPreferences>()
        AndroidNotebookTab(isNotebookSupported, { prefs.tabShown }, prefs::setTabShown)
    }
    single {
        val tab = get<NotebookTab>()
        val linkToken = get<NotebookLinkToken>()
        val deepLinks = get<PebbleDeepLinkHandler>()
        NotebookIntents(
            tabShown = { tab.shown.value },
            token = { linkToken.value },
            navigator = get(),
            openTab = { deepLinks.navigateToTab(PebbleNavBarRoutes.NotebookRoute) },
        )
    }
}
