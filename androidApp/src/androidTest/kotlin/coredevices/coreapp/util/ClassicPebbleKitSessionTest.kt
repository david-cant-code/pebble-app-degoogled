package coredevices.coreapp.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Parcel
import androidx.core.content.ContextCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppInstance
import io.rebble.libpebblecommon.metadata.pbw.appinfo.AndroidCompanionAppRoot
import io.rebble.libpebblecommon.metadata.pbw.appinfo.CompanionApp
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import io.rebble.libpebblecommon.pebblekit.classic.PebbleKitClassic
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
import kotlin.uuid.Uuid

/**
 * Drives a real classic PebbleKit session against a fake watch, pinning the two behaviors whose
 * pieces are tested elsewhere but whose wiring was not: the SEND handler relays a broadcast
 * only when it addresses the session's own watchapp (one session must not transmit another
 * watchapp's messages, or a message twice), and inbound watch data is broadcast per declared
 * companion with setPackage, falling back to an untargeted broadcast only when the watchapp
 * declares no companion. Upstream's version of PebbleKitClassic has neither the UUID filter nor
 * the targeting, so a mis-resolved upstream merge would revert exactly these lines; these tests
 * are what notices. The classic PebbleKit toggle is pinned here too: a session built while the
 * toggle is off registers no receiver and broadcasts nothing, and a session running when the
 * toggle goes off relays nothing in either direction from then on, although its receivers stay
 * registered until the restart that tears it down.
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

    @Test
    fun registersNoReceiverWhileClassicIsOff() {
        val watch = startSession(classicEnabled = false)

        // No liveness probe is possible when the point is that nothing registers, so
        // broadcast a few times and require silence throughout.
        repeat(5) {
            broadcastSend(SESSION_UUID, transactionId = LIVENESS_TID)
            assertNull(
                watch.sent.poll(300, TimeUnit.MILLISECONDS),
                "a SEND was relayed while classic PebbleKit is off",
            )
        }
    }

    @Test
    fun stopsRelayingSendsAfterClassicGoesOff() {
        val config = configFlow(classicEnabled = true)
        val watch = startSession(config = config)
        awaitSendRelayed(watch)
        drainRelays(watch)

        config.value = config.value.withClassic(false)
        repeat(5) {
            broadcastSend(SESSION_UUID, transactionId = LIVENESS_TID)
            assertNull(
                watch.sent.poll(300, TimeUnit.MILLISECONDS),
                "a SEND was relayed after classic PebbleKit went off",
            )
        }
    }

    @Test
    fun relaysAnAckAndANackWhileClassicIsOn() {
        val watch = startSession()
        awaitResultRelayed(watch, ACK)
        awaitResultRelayed(watch, NACK)
    }

    @Test
    fun stopsRelayingAcksAndNacksAfterClassicGoesOff() {
        val config = configFlow(classicEnabled = true)
        val watch = startSession(config = config)
        // Both receivers proven live first, so silence below means "gated", not "not registered".
        awaitResultRelayed(watch, ACK)
        awaitResultRelayed(watch, NACK)
        drainResults(watch)

        config.value = config.value.withClassic(false)
        repeat(5) {
            broadcastResult(ACK, transactionId = LIVENESS_TID)
            broadcastResult(NACK, transactionId = LIVENESS_TID)
            assertNull(
                watch.results.poll(300, TimeUnit.MILLISECONDS),
                "an ACK or NACK reached the watch after classic PebbleKit went off",
            )
        }
    }

    @Test
    fun aSendWithUnreadableMsgDataIsDroppedAndTheSessionKeepsRelaying() {
        val watch = startSession()
        awaitSendRelayed(watch)
        drainRelays(watch)

        val malformed = Intent(SEND).apply {
            putExtra("uuid", SESSION_UUID)
            putExtra("transaction_id", MISADDRESSED_TID)
            putExtra("msg_data", "not json")
        }
        context.sendOrderedBroadcast(malformed, null)
        assertNull(watch.sent.poll(1500, TimeUnit.MILLISECONDS), "a SEND with unreadable msg_data was relayed")

        // The session survives it: a well-formed SEND still gets through.
        awaitSendRelayed(watch)
    }

    @Test
    fun aSendWithNoMsgDataIsDroppedAndTheSessionKeepsRelaying() {
        val watch = startSession()
        awaitSendRelayed(watch)
        drainRelays(watch)

        val empty = Intent(SEND).apply {
            putExtra("uuid", SESSION_UUID)
            putExtra("transaction_id", MISADDRESSED_TID)
        }
        context.sendOrderedBroadcast(empty, null)
        assertNull(watch.sent.poll(1500, TimeUnit.MILLISECONDS), "a SEND with no msg_data was relayed")

        awaitSendRelayed(watch)
    }

    @Test
    fun aSendWithUnreadableExtrasIsDroppedAndTheSessionKeepsRelaying() {
        val watch = startSession()
        awaitSendRelayed(watch)
        drainRelays(watch)

        context.sendOrderedBroadcast(Intent(SEND).replaceExtras(unreadableExtras()), null)
        assertNull(watch.sent.poll(1500, TimeUnit.MILLISECONDS), "a SEND with unreadable extras was relayed")

        awaitSendRelayed(watch)
    }

    @Test
    fun anAckWithUnreadableExtrasIsDroppedAndTheSessionKeepsRelaying() {
        assertAResultBroadcastWithUnreadableExtrasIsDropped(ACK)
    }

    @Test
    fun aNackWithUnreadableExtrasIsDroppedAndTheSessionKeepsRelaying() {
        assertAResultBroadcastWithUnreadableExtrasIsDropped(NACK)
    }

    private fun assertAResultBroadcastWithUnreadableExtrasIsDropped(action: String) {
        val watch = startSession()
        awaitResultRelayed(watch, action)
        drainResults(watch)

        context.sendOrderedBroadcast(Intent(action).replaceExtras(unreadableExtras()), null)
        assertNull(watch.results.poll(1500, TimeUnit.MILLISECONDS), "a $action with unreadable extras reached the watch")

        // The session survives it: a well-formed broadcast of the same action still gets through.
        awaitResultRelayed(watch, action)
    }

    @Test
    fun stopsBroadcastingInboundDataAfterClassicGoesOff() {
        val incoming = MutableSharedFlow<AppMessageData>(extraBufferCapacity = 4)
        val config = configFlow(classicEnabled = true)
        startSession(config = config, incoming = incoming)
        val broadcasts = captureReceiveBroadcasts()
        awaitInboundCollected(incoming)

        config.value = config.value.withClassic(false)
        assertTrue(incoming.tryEmit(inboundMessage()))
        assertNull(
            broadcasts.poll(1500, TimeUnit.MILLISECONDS),
            "inbound watch data was broadcast after classic PebbleKit went off",
        )
    }

    @Test
    fun broadcastsNoInboundDataWhileClassicIsOff() {
        val incoming = MutableSharedFlow<AppMessageData>(extraBufferCapacity = 4)
        startSession(classicEnabled = false, incoming = incoming)
        val broadcasts = captureReceiveBroadcasts()

        // The gated session never subscribes, so this cannot wait for a subscriber; the
        // buffered emission is simply dropped, which is the behaviour under test.
        assertTrue(incoming.tryEmit(inboundMessage()))

        assertNull(
            broadcasts.poll(1500, TimeUnit.MILLISECONDS),
            "inbound watch data was broadcast while classic PebbleKit is off",
        )
        assertEquals(
            0,
            incoming.subscriptionCount.value,
            "the session subscribed to the inbound flow while classic PebbleKit is off",
        )
    }

    // -- helpers --

    private fun configFlow(classicEnabled: Boolean) =
        MutableStateFlow(LibPebbleConfig(watchConfig = WatchConfig(classicPebbleKitEnabled = classicEnabled)))

    private fun LibPebbleConfig.withClassic(enabled: Boolean) =
        copy(watchConfig = watchConfig.copy(classicPebbleKitEnabled = enabled))

    /** [config] is the session's own toggle source, kept by the caller so a test can flip it mid-session. */
    private fun startSession(
        companionPackages: List<String> = emptyList(),
        incoming: Flow<AppMessageData> = emptyFlow(),
        classicEnabled: Boolean = true,
        config: MutableStateFlow<LibPebbleConfig> = configFlow(classicEnabled),
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
        val classic = PebbleKitClassic(
            device,
            appInfo(companionPackages),
            scope,
            WatchConfigFlow(config),
        )
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

    /** Lets the SENDs still in flight from a liveness probe finish before a negative check. */
    private fun drainRelays(watch: RecordingWatch) {
        while (watch.sent.poll(700, TimeUnit.MILLISECONDS) != null) Unit
    }

    /**
     * The ACK/NACK receivers register asynchronously too; broadcast [action] until a result
     * carrying its probe's transaction id lands. The ids differ per action because both actions
     * put the same result type on the watch, so a late result of the other probe is skipped.
     */
    private fun awaitResultRelayed(watch: RecordingWatch, action: String): AppMessageResult {
        val transactionId = if (action == ACK) ACK_PROBE_TID else NACK_PROBE_TID
        repeat(25) {
            broadcastResult(action, transactionId)
            while (true) {
                val result = watch.results.poll(200, TimeUnit.MILLISECONDS) ?: break
                if (result.transactionId.toInt() == transactionId) return result
            }
        }
        fail("the session never relayed a $action to the watch")
    }

    /** Lets the results still in flight from a liveness probe finish before a negative check. */
    private fun drainResults(watch: RecordingWatch) {
        while (watch.results.poll(700, TimeUnit.MILLISECONDS) != null) Unit
    }

    /**
     * Extras whose first read throws in the receiving process: a single entry whose value type
     * code the platform does not know, written as raw bundle bytes. The Intent carries parcelled
     * extras to the receiver without reading them, so the throw happens in the session's own read
     * (the platform code is named at handleSenderInput).
     */
    private fun unreadableExtras(): Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeInt(0) // bundle length, patched below
            parcel.writeInt(BUNDLE_MAGIC)
            val start = parcel.dataPosition()
            parcel.writeInt(1) // entry count
            parcel.writeString("unreadable")
            parcel.writeInt(UNKNOWN_VALUE_TYPE)
            val end = parcel.dataPosition()
            parcel.setDataPosition(0)
            parcel.writeInt(end - start)
            parcel.setDataPosition(end)
            parcel.writeInt(0) // the has-intent flag android16-release BaseBundle.readFromParcelInner reads after the map
            parcel.setDataPosition(0)
            return Bundle.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    private fun broadcastResult(action: String, transactionId: Int) {
        context.sendOrderedBroadcast(Intent(action).putExtra("transaction_id", transactionId), null)
    }

    private fun broadcastSend(uuid: String, transactionId: Int) {
        val intent = Intent(SEND).apply {
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

    private companion object {
        const val SESSION_UUID = "864369ab-1f37-4a2e-9243-dd6b21af9c14"
        const val OTHER_UUID = "5f2c1e08-9f61-4d3e-8a35-0d2f8e1b7a90"
        const val LIVENESS_TID = 1
        const val ACK_PROBE_TID = 2
        const val NACK_PROBE_TID = 3
        const val MISADDRESSED_TID = 99
        const val SEND = "com.getpebble.action.app.SEND"
        const val ACK = "com.getpebble.action.app.ACK"
        const val NACK = "com.getpebble.action.app.NACK"
        const val BUNDLE_MAGIC = 0x4C444E42 // 'B' 'N' 'D' 'L'
        const val UNKNOWN_VALUE_TYPE = 1_000_000
    }
}
