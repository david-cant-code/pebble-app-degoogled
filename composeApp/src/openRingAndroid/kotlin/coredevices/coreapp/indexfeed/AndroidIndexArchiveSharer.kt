package coredevices.coreapp.indexfeed

import PlatformUiContext
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class AndroidIndexArchiveSharer(private val context: Context) : IndexArchiveSharer {
    override suspend fun saveWithPicker(
        uiContext: PlatformUiContext,
        suggestedName: String,
        zip: ByteArray,
    ): ArchiveSaveResult {
        val activity = uiContext.activity
        val registry = activity as? ActivityResultRegistryOwner ?: return ArchiveSaveResult.Cancelled
        val uri = suspendCancellableCoroutine { continuation ->
            lateinit var launcher: ActivityResultLauncher<Intent>
            launcher = registry.activityResultRegistry.register(
                "gravelIndexExport",
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                launcher.unregister()
                if (!continuation.isActive) return@register
                val picked = if (result.resultCode == Activity.RESULT_OK) result.data?.data else null
                continuation.resume(picked)
            }
            continuation.invokeOnCancellation { launcher.unregister() }
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/zip"
                putExtra(Intent.EXTRA_TITLE, suggestedName)
            }
            launcher.launch(intent)
        }
        if (uri == null) return ArchiveSaveResult.Cancelled
        writeZip(activity, uri, zip)
        return ArchiveSaveResult.Saved
    }

    override suspend fun share(uiContext: PlatformUiContext, suggestedName: String, zip: ByteArray) {
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "index-exports").apply { mkdirs() }
            File(dir, suggestedName).apply { writeBytes(zip) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        uiContext.activity.startActivity(Intent.createChooser(send, null))
    }

    private suspend fun writeZip(activity: Activity, uri: Uri, zip: ByteArray) {
        withContext(Dispatchers.IO) {
            activity.contentResolver.openOutputStream(uri)?.use { it.write(zip) }
                ?: throw IOException("Could not open the export file")
        }
    }
}
