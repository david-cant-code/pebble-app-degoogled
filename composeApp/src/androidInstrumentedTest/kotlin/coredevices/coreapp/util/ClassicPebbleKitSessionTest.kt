package coredevices.coreapp.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppInstance
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppRoot
import io.rebble.libpebblecommon.metadata.pbw.appinfo.CompanionApp
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import io.rebble.libpebblecommon.pebblekit.classic.PebbleKitClassic
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Drives a real classic PebbleKit session against a fake watch, pinning the two behaviors whose
 * pieces are tested elsewhere but whose wiring was not: the SEND handler relays a broadcast
 * only when it addresses the session's own watchapp (one session must not transmit another
 * watchapp's messages, or a message twice), and inbound watch data is broadcast per declared
 * companion with setPackage, falling back to an untargeted broadcast only when the watchapp
 * declares no companion. Upstream's version of PebbleKitClassic has neither the UUID filter nor
 * the targeting, so a mis-resolved upstream merge would revert exactly these lines; these tests
 * are what notices.
 *
 * Run with:
 * adb shell am instrument -w -e class \
 *   coredevices.coreapp.util.ClassicPebbleKitSessionTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
class ClassicPebbleKitSessionTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private var session: PebbleKitClassic? = null
    private var sessionScope: ConnectionCoroutineScope? = null
    private var registeredReceiver: BroadcastReceiver? = null

    @After
    fun tearDown() {
        runBlocking { session?.stop() }
        sessionScope?.cancel()
        registeredReceiver?.let { context.unregisterReceiver(it) }
        session = null
        sessionScope = null
        registeredReceiver = null
    }

    @Test
    fun relaysASendAddressedToTheSessionWatchapp() {
        val watch = startSession()

        val relayed = awaitSendRelayed(watch)

        assertEquals(SESSION_UUID, relayed.uuid.toString())
    }

    @Test
    fun dropsASendAddressedToAnotherWatchapp() {
        val watch = startSession()
        // Prove the receiver is live first, so silence below means "filtered", not "not yet
        // registered", then let the in-flight relays drain.
        awaitSendRelayed(watch)
        while (watch.sent.poll(700, TimeUnit.MILLISECONDS) != null) Unit

        broadcastSend(OTHER_UUID, transactionId = MISADDRESSED_TID)

        var message = watch.sent.poll(1500, TimeUnit.MILLISECONDS)
        while (message != null) {
            assertTrue(
                message.transactionId.toInt() != MISADDRESSED_TID,
                "a SEND addressed to another watchapp was relayed to the watch",
            )
            message = watch.sent.poll(500, TimeUnit.MILLISECONDS)
        }
    }

    @Test
    fun broadcastsInboundDataUntargetedWhenNoCompanionIsDeclared() {
        val received = deliverInbound(companionPackages = emptyList())

        assertNull(
            received.`package`,
            "with no declared companion the broadcast must stay untargeted",
        )
    }

    @Test
    fun targetsInboundDataAtTheDeclaredCompanion() {
        val received = deliverInbound(companionPackages = listOf(context.packageName))

        assertEquals(
            context.packageName,
            received.`package`,
            "the broadcast was not narrowed to the declared companion",
        )
    }

    @Test
    fun inboundDataTargetedAtAnotherCompanionDoesNotReachThisPackage() {
        val incoming = MutableSharedFlow<AppMessageData>(extraBufferCapacity = 4)
        startSession(
            companionPackages = listOf("com.example.not.this.package"),
            incoming = incoming,
        )
        val broadcasts = captureReceiveBroadcasts()
        awaitInboundCollected(incoming)

        assertTrue(incoming.tryEmit(inboundMessage()))

        assertNull(
            broadcasts.poll(1500, TimeUnit.MILLISECONDS),
            "watch data declared for another companion reached this package",
        )
    }

    // -- helpers --

    /** Fake watch: records what the session relays to it and ACKs everything. */
    private class RecordingWatch : ConnectedPebble.AppMessages {
        val sent = LinkedBlockingQueue<AppMessageData>()

        override val transactionSequence: Iterator<UByte> =
            generateSequence(0) { it + 1 }.map { it.toUByte() }.iterator()

        override suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult {
            sent.put(appMessageData)
            return AppMessageResult.ACK(appMessageData.transactionId)
        }

        override suspend fun sendAppMessageResult(appMessageResult: AppMessageResult) {}

        override fun inboundAppMessages(appUuid: Uuid): Flow<AppMessageData> = emptyFlow()
    }

    private fun startSession(
        companionPackages: List<String> = emptyList(),
        incoming: Flow<AppMessageData> = emptyFlow(),
    ): RecordingWatch {
        val watch = RecordingWatch()
        val device = CompanionAppDevice(
            identifier = object : PebbleIdentifier {
                override val asString = "test-watch"
            },
            watchInfo = testWatchInfo(),
            appMessages = watch,
        )
        val scope = ConnectionCoroutineScope(SupervisorJob() + Dispatchers.Default)
        sessionScope = scope
        val classic = PebbleKitClassic(device, appInfo(companionPackages), scope)
        session = classic
        runBlocking { classic.start(incoming) }
        return watch
    }

    /**
     * The session's receivers register asynchronously after start(), so broadcast repeatedly
     * until the first relay lands rather than racing a single broadcast against registration.
     */
    private fun awaitSendRelayed(watch: RecordingWatch): AppMessageData {
        repeat(25) {
            broadcastSend(SESSION_UUID, transactionId = LIVENESS_TID)
            watch.sent.poll(200, TimeUnit.MILLISECONDS)?.let { return it }
        }
        fail("the session never relayed a correctly addressed SEND")
    }

    private fun broadcastSend(uuid: String, transactionId: Int) {
        val intent = Intent("com.getpebble.action.app.SEND").apply {
            putExtra("uuid", uuid)
            putExtra("transaction_id", transactionId)
            putExtra("msg_data", """[{"key":1,"type":"string","length":0,"value":"ping"}]""")
        }
        context.sendOrderedBroadcast(intent, null)
    }

    /** Starts a session, feeds it one inbound message, and returns the RECEIVE broadcast. */
    private fun deliverInbound(companionPackages: List<String>): Intent {
        val incoming = MutableSharedFlow<AppMessageData>(extraBufferCapacity = 4)
        startSession(companionPackages = companionPackages, incoming = incoming)
        val broadcasts = captureReceiveBroadcasts()
        awaitInboundCollected(incoming)

        assertTrue(incoming.tryEmit(inboundMessage()))

        return broadcasts.poll(5, TimeUnit.SECONDS)
            ?: fail("inbound watch data was never broadcast")
    }

    private fun captureReceiveBroadcasts(): LinkedBlockingQueue<Intent> {
        val queue = LinkedBlockingQueue<Intent>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let(queue::put)
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter("com.getpebble.action.app.RECEIVE"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        registeredReceiver = receiver
        return queue
    }

    private fun awaitInboundCollected(incoming: MutableSharedFlow<AppMessageData>) {
        val deadline = System.currentTimeMillis() + 5000
        while (incoming.subscriptionCount.value == 0) {
            if (System.currentTimeMillis() > deadline) {
                fail("the session never collected the inbound message flow")
            }
            Thread.sleep(50)
        }
    }

    private fun inboundMessage() =
        AppMessageData(transactionId = 1u, uuid = Uuid.parse(SESSION_UUID), data = mapOf(1 to "pong"))

    private fun appInfo(companionPackages: List<String>) = PbwAppInfo(
        uuid = SESSION_UUID,
        shortName = "test",
        versionLabel = "1.0",
        resources = Resources(),
        companionApp = if (companionPackages.isEmpty()) null else CompanionApp(
            android = AndroidCompanionAppRoot(
                apps = companionPackages.map { AndroidCompanionAppInstance(pkg = it) },
            ),
        ),
    )

    private fun testWatchInfo() = WatchInfo(
        runningFwVersion = testFirmwareVersion(),
        recoveryFwVersion = null,
        platform = WatchHardwarePlatform.UNKNOWN,
        bootloaderTimestamp = Instant.DISTANT_PAST,
        board = "test",
        serial = "TESTSERIAL",
        btAddress = "00:00:00:00:00:00",
        resourceCrc = 0L,
        resourceTimestamp = Instant.DISTANT_PAST,
        language = "en_US",
        languageVersion = 1,
        capabilities = emptySet(),
        isUnfaithful = false,
        healthInsightsVersion = null,
        javascriptVersion = null,
        color = WatchColor.entries.first(),
    )

    private fun testFirmwareVersion() = FirmwareVersion(
        stringVersion = "1.0.0",
        timestamp = Instant.DISTANT_PAST,
        major = 1,
        minor = 0,
        patch = 0,
        suffix = null,
        gitHash = "",
        isRecovery = false,
        isDualSlot = false,
        isSlot0 = false,
    )

    private companion object {
        const val SESSION_UUID = "864369ab-1f37-4a2e-9243-dd6b21af9c14"
        const val OTHER_UUID = "5f2c1e08-9f61-4d3e-8a35-0d2f8e1b7a90"
        const val LIVENESS_TID = 1
        const val MISADDRESSED_TID = 99
    }
}
