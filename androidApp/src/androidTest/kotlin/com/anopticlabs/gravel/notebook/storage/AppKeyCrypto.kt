package com.anopticlabs.gravel.notebook.storage

import androidx.test.platform.app.InstrumentationRegistry
import javax.crypto.SecretKey

private fun appRoot() = NotebookFiles.root(InstrumentationRegistry.getInstrumentation().targetContext)

/** The codec on the app's own key, guarded by the app's own Notebook files as the app guards it. */
fun appKeyCrypto(): NotebookCrypto = NotebookFiles.crypto(NotebookKeystore(), appRoot())

/** The app's own key under the same guard. */
fun appKey(): SecretKey = NotebookKeystore().getOrCreateKey { !NotebookFiles.holdsSealedFiles(appRoot()) }
