package coredevices.coreapp.indexfeed

import PlatformUiContext

enum class ArchiveSaveResult {
    Saved,
    Cancelled,
}

/** Saves or shares a zip the caller already built. */
interface IndexArchiveSharer {
    suspend fun saveWithPicker(uiContext: PlatformUiContext, suggestedName: String, zip: ByteArray): ArchiveSaveResult

    suspend fun share(uiContext: PlatformUiContext, suggestedName: String, zip: ByteArray)
}
