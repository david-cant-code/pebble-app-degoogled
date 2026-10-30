package com.anopticlabs.gravel.watchapp

import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageDictionary
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class WatchAppCapturesTest {
    private val app = Uuid.parse("d09c758e-a9ac-4ce7-be23-84c6c6caef2b")

    private class Recorder(val failOn: Int? = null) : WatchAppCaptures {
        override val appUuid = Uuid.parse("d09c758e-a9ac-4ce7-be23-84c6c6caef2b")
        val seen = mutableListOf<AppMessageDictionary>()
        override suspend fun capture(encoderInfo: VoiceEncoderInfo, frames: Flow<UByteArray>) = ""
        override suspend fun onMessage(data: AppMessageDictionary) {
            seen += data
            if (data[1] == failOn) error("handler failure")
        }
    }

    @Test
    fun eachMessageReachesTheHandlerAndIsAckedAfterIt() = runTest {
        val handler = Recorder(failOn = 2)
        val log = mutableListOf<String>()
        val messages = flowOf(
            AppMessageData(7u, app, mapOf(1 to 1)),
            AppMessageData(8u, app, mapOf(1 to 2)),
            AppMessageData(9u, app, mapOf(1 to 3)),
        )
        relayWatchAppMessages(messages, object : WatchAppCaptures by handler {
            override suspend fun onMessage(data: AppMessageDictionary) {
                log += "handled:${data[1]}"
                handler.onMessage(data)
            }
        }) { id -> log += "ack:$id" }
        assertEquals(listOf("handled:1", "ack:7", "handled:2", "ack:8", "handled:3", "ack:9"), log, "a handler failure still ACKs")
        assertEquals(3, handler.seen.size)
    }

    @Test
    fun sha256OfAFileMatchesAKnownVector() = runTest {
        val path = Path(SystemTemporaryDirectory, "gravel-digest-${Random.nextLong()}.pbw")
        try {
            SystemFileSystem.sink(path).buffered().use { it.write("abc".encodeToByteArray()) }
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex(path))
            assertNull(sha256Hex(path, maxBytes = 2), "a file over the cap gets no digest")
        } finally {
            SystemFileSystem.delete(path, mustExist = false)
        }
        assertNull(sha256Hex(path), "a missing file gets no digest")
    }

    @Test
    fun theCaptureAppIsRelayedAndNeverReachesTheCompanionPath() = runTest {
        val handler = Recorder()
        val log = mutableListOf<String>()
        suspend fun start(uuid: Uuid, injected: InjectedWatchAppCaptures) =
            startAppSession(uuid, injected, relay = { log += "relay:${it.appUuid}" }, companions = { log += "companions:$uuid" })
        val other = Uuid.parse("00000000-0000-0000-0000-0000000000aa")

        start(app, InjectedWatchAppCaptures(handler))
        start(other, InjectedWatchAppCaptures(handler))
        start(app, InjectedWatchAppCaptures(null))
        assertEquals(listOf("relay:$app", "companions:$other", "companions:$app"), log)
    }

    @Test
    fun onlyARegularFileCountsAsACachedPbw() = runTest {
        val dir = Path(SystemTemporaryDirectory, "gravel-pbw-${Random.nextLong()}")
        SystemFileSystem.createDirectories(dir)
        try {
            val file = Path(dir, "${app}_1.2.pbw")
            SystemFileSystem.sink(file).buffered().use { it.write("pbw".encodeToByteArray()) }
            assertTrue(isRegularFile(file))
            assertFalse(isRegularFile(Path(dir, "${app}_1.3.pbw")), "a missing file")
            val directory = Path(dir, "${app}_2.0.pbw").also { SystemFileSystem.createDirectories(it) }
            assertFalse(isRegularFile(directory), "a directory")
            assertNull(sha256Hex(directory))
        } finally {
            SystemFileSystem.list(dir).forEach { SystemFileSystem.delete(it) }
            SystemFileSystem.delete(dir)
        }
    }
}
