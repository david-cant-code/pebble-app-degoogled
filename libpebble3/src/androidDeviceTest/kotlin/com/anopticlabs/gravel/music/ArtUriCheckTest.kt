package com.anopticlabs.gravel.music

import android.net.Uri
import androidx.test.filters.SmallTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SmallTest
class ArtUriCheckTest {
    private val own = setOf("com.anopticlabs.gravel.fileprovider")
    private val reader = ArtUriReader(CoroutineScope(SupervisorJob()), Dispatchers.IO)

    private val refused = listOf(
        "content://com.anopticlabs.gravel.fileprovider/screenshots/x.png",
        "content://%63om.anopticlabs.gravel.fileprovider/screenshots/x.png",
        "content://0@com.anopticlabs.gravel.fileprovider/screenshots/x.png",
        "content://1%402@com.anopticlabs.gravel.fileprovider/screenshots/x.png",
        "file:///data/data/com.anopticlabs.gravel/files/x.png",
        "android.resource://com.anopticlabs.gravel/raw/x",
        "content:///x.png",
        "content:x.png",
    )
    private val allowed = listOf(
        "content://media/external/images/media/1",
        "content://com.example.player.art/cover/1",
        "content://10@com.example.player.art/cover/1",
    )

    @Test
    fun refusedUrisAreNeverOpened() = runBlocking {
        refused.forEach { uri ->
            val opened = mutableListOf<Uri>()
            val bytes = reader.readArtUri(Uri.parse(uri), own, open = { opened += it; ByteArrayInputStream(ByteArray(1)) }, abandon = {})
            assertNull(bytes, uri)
            assertEquals(emptyList(), opened, uri)
        }
    }

    @Test
    fun otherAppsContentUrisAreOpened() = runBlocking {
        allowed.forEach { uri ->
            val parsed = Uri.parse(uri)
            val opened = mutableListOf<Uri>()
            val bytes = reader.readArtUri(parsed, own, open = { opened += it; ByteArrayInputStream(ByteArray(4)) }, abandon = {})
            assertContentEquals(ByteArray(4), bytes, uri)
            assertEquals(listOf(parsed), opened, uri)
        }
    }
}
