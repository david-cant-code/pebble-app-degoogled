package coredevices.libindex

import kotlinx.coroutines.flow.StateFlow

/**
 * Present only when the open ring build is selected. The devices screen
 * uses that presence as the scan entry, and [status] for the last sync line.
 */
interface IndexRingClient {
    val status: StateFlow<String?>
}
