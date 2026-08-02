package io.rebble.libpebblecommon.pebblekit.two

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

/**
 * The registry is the single authorization oracle for the exported PebbleKit 2 provider and the
 * sender service, so its decision semantics are pinned here: fail-closed before the first scan,
 * locker membership as the source of authority (a cached file alone grants nothing), rescans
 * when a store install's PBW lands after its locker row, and resilience of one watchapp's
 * grant to another watchapp's corrupt file. A regression in any of these either reopens the
 * watch-state read path or silently locks every companion out.
 */
class PebbleKitCompanionRegistryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val watchappUuid = Uuid.parse("864369ab-1f37-4a2e-9243-dd6b21af9c14")
    private val otherUuid = Uuid.parse("5f2c1e08-9f61-4d3e-8a35-0d2f8e1b7a90")

    @Test
    fun `denies everything before the first scan completes`() = runTest {
        writePbw("app.pbw", watchappUuid, "com.example.companion")
        val registry = startRegistry(MutableStateFlow(listOf(watchappUuid)))

        // The scheduler is deliberately not advanced: the scan has not run yet, and a query in
        // that window must fail closed rather than expose watch state.
        assertFalse(registry.isAuthorized("com.example.companion"))
        assertFalse(registry.isAuthorizedFor("com.example.companion", watchappUuid.toString()))
    }

    @Test
    fun `authorizes a declared companion of an installed watchapp`() = runTest {
        writePbw("app.pbw", watchappUuid, "com.example.companion")
        val registry = startRegistry(MutableStateFlow(listOf(watchappUuid)))
        runCurrent()

        assertTrue(registry.isAuthorized("com.example.companion"))
        assertTrue(registry.isAuthorizedFor("com.example.companion", watchappUuid.toString()))
        assertFalse(registry.isAuthorized("com.example.uninvolved"))
        assertFalse(registry.isAuthorizedFor("com.example.companion", otherUuid.toString()))
    }

    @Test
    fun `accepts a mixed-case uuid from the caller`() = runTest {
        writePbw("app.pbw", watchappUuid, "com.example.companion")
        val registry = startRegistry(MutableStateFlow(listOf(watchappUuid)))
        runCurrent()

        val uppercase = watchappUuid.toString().uppercase()
        assertTrue(registry.isAuthorizedFor("com.example.companion", uppercase))
    }

    @Test
    fun `denies a null caller and a null watchapp`() = runTest {
        writePbw("app.pbw", watchappUuid, "com.example.companion")
        val registry = startRegistry(MutableStateFlow(listOf(watchappUuid)))
        runCurrent()

        assertFalse(registry.isAuthorized(null))
        assertFalse(registry.isAuthorizedFor(null, watchappUuid.toString()))
        assertFalse(registry.isAuthorizedFor("com.example.companion", null))
    }

    @Test
    fun `merges packages across cached versions of one watchapp`() = runTest {
        writePbw("app_1.0.pbw", watchappUuid, "com.example.old")
        writePbw("app_1.1.pbw", watchappUuid, "com.example.new")
        val registry = startRegistry(MutableStateFlow(listOf(watchappUuid)))
        runCurrent()

        assertTrue(registry.isAuthorizedFor("com.example.old", watchappUuid.toString()))
        assertTrue(registry.isAuthorizedFor("com.example.new", watchappUuid.toString()))
    }

    @Test
    fun `skips a corrupt pbw without revoking other companions`() = runTest {
        File(temp.root, "corrupt.pbw").writeBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x01))
        writePbw("app.pbw", watchappUuid, "com.example.companion")
        val registry = startRegistry(MutableStateFlow(listOf(watchappUuid)))
        runCurrent()

        assertTrue(registry.isAuthorized("com.example.companion"))
    }

    @Test
    fun `contributes nothing for a watchapp with no companion declaration`() = runTest {
        writePbw("app.pbw", watchappUuid)
        val registry = startRegistry(MutableStateFlow(listOf(watchappUuid)))
        runCurrent()

        assertFalse(registry.isAuthorizedFor("com.example.companion", watchappUuid.toString()))
    }

    @Test
    fun `a cached pbw grants nothing when its watchapp is not in the locker`() = runTest {
        writePbw("app.pbw", watchappUuid, "com.example.companion")
        val registry = startRegistry(MutableStateFlow(emptyList()))
        runCurrent()

        assertFalse(registry.isAuthorized("com.example.companion"))
    }

    @Test
    fun `revokes when the watchapp leaves the locker even if its file remains`() = runTest {
        // A web-sync removal marks the locker row deleted without deleting the cached file, so
        // revocation must come from locker membership, not from the file disappearing.
        writePbw("app.pbw", watchappUuid, "com.example.companion")
        val uuids = MutableStateFlow(listOf(watchappUuid))
        val registry = startRegistry(uuids)
        runCurrent()
        assertTrue(registry.isAuthorized("com.example.companion"))

        uuids.value = emptyList()
        runCurrent()

        assertFalse(registry.isAuthorized("com.example.companion"))
    }

    @Test
    fun `rescans when a pbw lands in the cache after its locker row`() = runTest {
        // The store-install order: the locker row is inserted first and the PBW is downloaded
        // later as a file-only write, so the scan triggered by the row insert finds nothing.
        val uuids = MutableStateFlow(listOf(watchappUuid))
        val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val registry = startRegistry(uuids, changes)
        runCurrent()
        assertFalse(registry.isAuthorized("com.example.companion"))

        writePbw("app.pbw", watchappUuid, "com.example.companion")
        changes.tryEmit(Unit)
        runCurrent()

        assertTrue(registry.isAuthorized("com.example.companion"))
    }

    private fun TestScope.startRegistry(
        uuids: Flow<List<Uuid>>,
        changes: Flow<Unit> = MutableSharedFlow(),
    ): PebbleKitCompanionRegistry {
        val registry = PebbleKitCompanionRegistry(
            lockerUuids = uuids,
            cachedPbws = ::cachedPbws,
            pbwFilesChanged = changes,
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
        registry.init()
        return registry
    }

    private fun cachedPbws(): List<Path> = temp.root.listFiles().orEmpty()
        .filter { it.name.endsWith(".pbw") }
        .map { Path(it.absolutePath) }

    /** A minimal but genuinely parseable PBW: a zip holding only appinfo.json. */
    private fun writePbw(name: String, uuid: Uuid, vararg packages: String) {
        val companion = if (packages.isEmpty()) "" else {
            val apps = packages.joinToString(",") { """{"package":"$it"}""" }
            ""","companionApp":{"android":{"apps":[$apps]}}"""
        }
        val json =
            """{"uuid":"$uuid","shortName":"test","versionLabel":"1.0","resources":{"media":[]}$companion}"""
        ZipOutputStream(FileOutputStream(File(temp.root, name))).use { zip ->
            zip.putNextEntry(ZipEntry("appinfo.json"))
            zip.write(json.encodeToByteArray())
            zip.closeEntry()
        }
    }
}
