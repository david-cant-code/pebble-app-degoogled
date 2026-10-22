package coredevices.coreapp.indexfeed

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import coredevices.pebble.ui.TopBarParams

@Composable
fun GravelIndexFeedScreen(topBarParams: TopBarParams) {
    LaunchedEffect(Unit) {
        topBarParams.setHidden(false)
        topBarParams.title("Index")
    }
}
