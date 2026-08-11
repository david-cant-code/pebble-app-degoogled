package coredevices.coreapp.model

import coredevices.util.models.WhisperModelCatalog
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the provider's directory-shape decisions through the static
 * helpers, with temp dirs standing in for filesDir/models. These
 * decisions drive the migration sweep: every Cactus-era directory must
 * read as incompatible (that is what triggers its deletion and the
 * re-download prompt), while a healthy whisper install must never be
 * swept, since that would cost users a multi-hundred-MB re-download.
 */
class WhisperModelProviderDecisionTest {

    private val modelsDir: File = Files.createTempDirectory("provider-decision-test").toFile()

    private val model = WhisperModelCatalog.byId("whisper-base-en")!!

    /** Sparse file of exactly [size] bytes; content irrelevant to the shape checks. */
    private fun fileOfSize(file: File, size: Long) {
        file.parentFile!!.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    @Test
    fun exactSizeCatalogInstallIsInstalledShape() {
        fileOfSize(modelsDir.resolve(model.id).resolve(model.fileName), model.sizeBytes)
        assertTrue(WhisperModelProvider.isInstalledShapeIn(modelsDir, model))
        assertEquals(emptyList(), WhisperModelProvider.incompatibleIn(modelsDir))
    }

    @Test
    fun wrongSizeCatalogDirIsIncompatible() {
        fileOfSize(modelsDir.resolve(model.id).resolve(model.fileName), model.sizeBytes - 1)
        assertFalse(WhisperModelProvider.isInstalledShapeIn(modelsDir, model))
        assertEquals(listOf(model.id), WhisperModelProvider.incompatibleIn(modelsDir))
    }

    @Test
    fun catalogDirWithoutItsFileIsIncompatible() {
        modelsDir.resolve(model.id).mkdirs()
        assertEquals(listOf(model.id), WhisperModelProvider.incompatibleIn(modelsDir))
    }

    @Test
    fun cactusEraDirectoriesAreIncompatible() {
        // The exact on-disk shape the previous engine left behind; the
        // sweep must claim both the STT and LM installs.
        for (legacy in listOf("parakeet-tdt-0.6b-v3", "needle-pebble-ft")) {
            val dir = modelsDir.resolve(legacy).also { it.mkdirs() }
            dir.resolve("config.txt").writeText("cfg")
            dir.resolve("model.weights").writeText("weights")
            dir.resolve(".cactus_version").writeText("v2.0.1")
        }
        assertEquals(
            setOf("parakeet-tdt-0.6b-v3", "needle-pebble-ft"),
            WhisperModelProvider.incompatibleIn(modelsDir).toSet(),
        )
    }

    @Test
    fun stagingIsNeverReportedAsAModel() {
        val staging = modelsDir.resolve(ModelFileInstaller.STAGING_DIR).also { it.mkdirs() }
        staging.resolve("whisper-base-en.partial").writeText("partial bytes")
        assertEquals(emptyList(), WhisperModelProvider.incompatibleIn(modelsDir))
    }

    @Test
    fun emptyModelsDirHasNothingToSweep() {
        assertEquals(emptyList(), WhisperModelProvider.incompatibleIn(modelsDir))
    }

    @Test
    fun singleSegmentNamesAreSafeModelDirNames() {
        // Everything a legitimate caller can produce: catalog ids and
        // directory names from a modelsDir listing.
        assertTrue(WhisperModelProvider.isSafeModelDirName("whisper-base-en"))
        assertTrue(WhisperModelProvider.isSafeModelDirName("parakeet-tdt-0.6b-v3"))
        assertTrue(WhisperModelProvider.isSafeModelDirName(".cactus_version"))
    }

    @Test
    fun traversingAndAbsoluteNamesAreRejected() {
        // deleteModel resolves its argument into a recursive delete, so a
        // name that escapes modelsDir must never reach it.
        assertFalse(WhisperModelProvider.isSafeModelDirName(""))
        assertFalse(WhisperModelProvider.isSafeModelDirName("."))
        assertFalse(WhisperModelProvider.isSafeModelDirName(".."))
        assertFalse(WhisperModelProvider.isSafeModelDirName("../databases"))
        assertFalse(WhisperModelProvider.isSafeModelDirName("a/b"))
        assertFalse(WhisperModelProvider.isSafeModelDirName("a\\b"))
        assertFalse(WhisperModelProvider.isSafeModelDirName("/data/data/com.anopticlabs.gravel/files"))
    }
}
