package coredevices.pebble.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.Flow

fun interface SnackbarDisplay {
    fun showSnackbar(message: String)
}

@Stable
data class TopBarParams(
    val searchAvailable: (SearchState?) -> Unit,
    val actions: (@Composable RowScope.() -> Unit) -> Unit,
    val title: (String) -> Unit,
    val overrideGoBack: Flow<Unit>,
    private val showSnackbar: (String) -> Unit,
    val scrollToTop: Flow<Unit>,
    /** Hide the chrome's TopAppBar for screens that render their own
     *  inline header (e.g. the Index home view). Defaults to no-op so
     *  callers that don't care don't have to opt in. */
    val setHidden: (Boolean) -> Unit = {},
) : SnackbarDisplay {
    override fun showSnackbar(message: String) = showSnackbar.invoke(message)
}

@Composable
fun rememberSearchState() = remember { SearchState() }

class SearchState {
    private val queryState = mutableStateOf("")

    /**
     * What the field shows. It changes on every keystroke and is meant for
     * on-device filtering only.
     */
    var query: String
        get() = queryState.value
        set(value) {
            queryState.value = value
            // Emptying the field withdraws the submitted query as well, so no
            // store results linger for text that is no longer there.
            if (value.isEmpty()) submittedQuery = ""
        }

    /**
     * The text the user committed with the search action. Anything that
     * leaves the phone (the store search) reads this and never [query], so a
     * query goes out as the user submitted it and not as each of its
     * prefixes. Screens compare the two to tell an edited, not yet submitted
     * field from a submitted one.
     */
    var submittedQuery by mutableStateOf("")
        private set

    fun submit() {
        submittedQuery = query
    }

    var typing by mutableStateOf(false)
    var show by mutableStateOf(false)
}
