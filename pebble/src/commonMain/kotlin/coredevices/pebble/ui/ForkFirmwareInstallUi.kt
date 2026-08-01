package coredevices.pebble.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coredevices.pebble.firmware.ForkFirmwareInstallState
import coredevices.pebble.firmware.VerifiedFirmwareInstaller
import coredevices.ui.CoreLinearProgressIndicator
import io.rebble.libpebblecommon.connection.PebbleDevice

/**
 * Compose glue between the fork's verified installer and the upstream watch
 * UI. Kept in its own file so the edits inside upstream screens stay tiny
 * (fewer merge conflicts).
 */
@Composable
fun VerifiedFirmwareInstaller.installStateFor(watch: PebbleDevice): State<ForkFirmwareInstallState> {
    // stateFor mints a fresh read-only wrapper per call; without remember,
    // every recomposition would hand collectAsState a new flow identity and
    // restart its collection coroutine.
    val flow = remember(this, watch.identifier) { stateFor(watch.identifier) }
    return flow.collectAsState()
}

/** Suffix for the watch state line, matching upstream's " - ..." style. */
fun ForkFirmwareInstallState.stateTextSuffix(): String =
    describe()?.let { " - $it" } ?: ""

/** Download progress bar in the same style as the upstream transfer bar. */
@Composable
fun ForkInstallProgressBar(state: ForkFirmwareInstallState) {
    if (state is ForkFirmwareInstallState.Downloading) {
        val progress = state.progress
        if (progress != null) {
            CoreLinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
            )
        }
    }
}
