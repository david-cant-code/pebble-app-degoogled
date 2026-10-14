package com.anopticlabs.gravel.notebook.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Drives [TestDocumentsProvider] and [TestGrantProvider], which run in the test package's own
 * process, through the app's resolver; documents are reachable only through the grants [grant]
 * obtains, as the picker's result is in production.
 */
class TestDocsClient(private val targetContext: Context) {

    val resolver: ContentResolver = targetContext.contentResolver

    fun connect() {
        grant(TestDocumentsProvider.rootUri())
        control(TestDocumentsProvider.METHOD_RESET)
    }

    fun reset() = control(TestDocumentsProvider.METHOD_RESET)

    fun grant(vararg uris: Uri, persistable: Boolean = false, prefix: Boolean = false) {
        val extras = Bundle().apply {
            putParcelableArrayList(TestGrantProvider.EXTRA_URIS, ArrayList(uris.toList()))
            putBoolean(TestGrantProvider.EXTRA_PERSISTABLE, persistable)
            putBoolean(TestGrantProvider.EXTRA_PREFIX, prefix)
        }
        val result = resolver.call(TestGrantProvider.URI, TestGrantProvider.METHOD_GRANT, targetContext.packageName, extras)
        check(result?.getInt("granted") == uris.size) { "grant helper did not grant ${uris.size} uri(s): $result" }
    }

    fun control(method: String, arg: String? = null, extras: Bundle? = null): Bundle =
        checkNotNull(resolver.call(TestDocumentsProvider.rootUri(), method, arg, extras)) { "control call $method returned null" }

    fun configure(
        corruptReads: Boolean = false,
        refuseWrite: Boolean = false,
        reportSize: Boolean = true,
    ) {
        control(
            TestDocumentsProvider.METHOD_CONFIG,
            extras = Bundle().apply {
                putBoolean(TestDocumentsProvider.EXTRA_CORRUPT_READS, corruptReads)
                putBoolean(TestDocumentsProvider.EXTRA_REFUSE_WRITE, refuseWrite)
                putBoolean(TestDocumentsProvider.EXTRA_REPORT_SIZE, reportSize)
            },
        )
    }

    /** A document holding [bytes] (empty by default, as the picker creates it), granted to the app. */
    fun createGranted(name: String, bytes: ByteArray = ByteArray(0), persistable: Boolean = false): Uri {
        control(TestDocumentsProvider.METHOD_CREATE, name, Bundle().apply { putByteArray(TestDocumentsProvider.EXTRA_BYTES, bytes) })
        val uri = TestDocumentsProvider.documentUri(name)
        grant(uri, persistable = persistable)
        return uri
    }

    /** The root as a folder the user picked: a persistable grant on its tree and every document under it. */
    fun grantFolder(): Uri = TestDocumentsProvider.treeUri().also { grant(it, persistable = true, prefix = true) }

    fun delete(name: String) {
        control(TestDocumentsProvider.METHOD_DELETE, name)
    }

    fun names(): List<String> = control(TestDocumentsProvider.METHOD_LIST).getStringArrayList(TestDocumentsProvider.EXTRA_NAMES)!!

    fun bytesOf(name: String): ByteArray = control(TestDocumentsProvider.METHOD_READ, name).getByteArray(TestDocumentsProvider.EXTRA_BYTES)!!
}
