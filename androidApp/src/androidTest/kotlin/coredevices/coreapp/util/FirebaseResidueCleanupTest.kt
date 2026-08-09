package coredevices.coreapp.util

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.After
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exercises the closed vector directly: seeds the exact file shapes the
 * Firebase Auth/Firestore/Installations SDKs persisted on pre-strip installs
 * and asserts the cleanup removes them while leaving neighboring app data
 * alone. Runs instrumented because the target paths are the real app-private
 * layout (dataDir/shared_prefs, dataDir/databases, filesDir).
 */
class FirebaseResidueCleanupTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val sharedPrefsDir = File(context.dataDir, "shared_prefs")
    private val databasesDir = File(context.dataDir, "databases")

    // Names copied from a real pre-strip install's layout.
    private val residue = listOf(
        File(sharedPrefsDir, "com.google.firebase.auth.api.Store.APPID.xml"),
        File(sharedPrefsDir, "com.google.firebase.auth.internal.KEEP_SYNCED.xml"),
        File(sharedPrefsDir, "FirebaseHeartBeatW0RFRkFVTFRd.xml"),
        File(databasesDir, "firestore.default.project.(default)"),
        File(databasesDir, "firestore.default.project.(default)-journal"),
        File(context.filesDir, "PersistedInstallation.W0RFRkFVTFRd.json"),
    )

    // Must survive: same directories, non-Firebase names.
    private val decoys = listOf(
        File(sharedPrefsDir, "residue_test_decoy.xml"),
        File(databasesDir, "residue_test_decoy.db"),
        File(context.filesDir, "residue_test_decoy.json"),
    )

    @After
    fun tearDown() {
        (residue + decoys).forEach { it.delete() }
    }

    @Test
    fun deletesFirebaseResidueAndNothingElse() {
        (residue + decoys).forEach { file ->
            file.parentFile?.mkdirs()
            file.writeText("test payload")
            assertTrue(file.exists(), "failed to seed ${file.name}")
        }

        FirebaseResidueCleanup.delete(context)

        residue.forEach { assertFalse(it.exists(), "${it.name} should have been deleted") }
        decoys.forEach { assertTrue(it.exists(), "${it.name} should have been left alone") }
    }

    @Test
    fun cleanInstallIsANoOp() {
        // No residue seeded: nothing to delete, nothing thrown.
        FirebaseResidueCleanup.delete(context)
    }
}
