package io.rebble.libpebblecommon.pebblekit.classic

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageDictionary
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.libpebblecommon.util.asFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class PebbleKitClassic(
    private val device: CompanionAppDevice,
    val appInfo: PbwAppInfo,
    private val connectionScope: ConnectionCoroutineScope,
) :
    LibPebbleKoinComponent, CompanionApp {
    companion object {
        private val logger = Logger.withTag(PebbleKitClassic::class.simpleName!!)
    }

    val uuid: Uuid by lazy { Uuid.parse(appInfo.uuid) }
    private var runningScope: CoroutineScope? = null

    private val context: Context = getKoin().get()

    private val companionPackages: List<String> by lazy { appInfo.androidCompanionPackages() }

    /**
     * Delivers watch data to the watchapp's declared companion apps rather than to every app on
     * the device.
     *
     * Classic PebbleKit has no permission and never has, so an untargeted broadcast hands the
     * contents of every message the watch sends to any app that registers a receiver. Watchapps
     * predating companion declarations have nobody to target, so those keep broadcasting as
     * before: narrowing where the declaration exists costs nothing, whereas dropping the
     * fallback would break apps that still work today.
     */
    private fun broadcastToCompanions(intent: Intent) {
        if (companionPackages.isEmpty()) {
            // Regular broadcasts are sometimes delayed on Android 14+. Use ordered ones instead.
            // https://stackoverflow.com/questions/77842817/slow-intent-broadcast-delivery-on-android-14
            context.sendOrderedBroadcast(intent, null)
            return
        }
        companionPackages.forEach { pkg ->
            context.sendOrderedBroadcast(Intent(intent).setPackage(pkg), null)
        }
    }

    private suspend fun replyNACK(id: UByte) {
        withTimeoutOrNull(1000) {
            device.sendAppMessageResult(AppMessageResult.ACK(id))
        }
    }

    private suspend fun replyACK(id: UByte) {
        withTimeoutOrNull(1000) {
            device.sendAppMessageResult(AppMessageResult.ACK(id))
        }
    }

    private fun launchIncomingAppMessageHandler(messages: Flow<AppMessageData>, scope: CoroutineScope) {
        messages.onEach { appMessageData ->
            logger.d { "Got inbound message" }

            val pebbleDictionary = appMessageData.data.toPebbleDictionary()
            val intent = Intent(INTENT_APP_RECEIVE).apply {
                putExtra(APP_UUID, appMessageData.uuid.toJavaUuid())
                putExtra(TRANSACTION_ID, appMessageData.transactionId.toInt())

                putExtra(MSG_DATA, pebbleDictionary.toJsonString())
            }

            broadcastToCompanions(intent)
        }.catch {
            logger.e(it) { "Error receiving app message: ${it.message}" }
        }.launchIn(scope)
    }

    // TODO app start and stop intents

    private fun launchOutgoingAppMessageHandlers(device: ConnectedPebble.AppMessages, scope: CoroutineScope) {
        scope.launch {
            IntentFilter(INTENT_APP_ACK).asFlow(context, exported = true).collect { intent ->
                logger.d { "Got outbound ack" }
                val transactionId: Int = intent.getIntExtra(TRANSACTION_ID, 0)
                replyACK(transactionId.toUByte())
            }
        }

        scope.launch {
            IntentFilter(INTENT_APP_NACK).asFlow(context, exported = true).collect { intent ->
                logger.d { "Got outbound nack" }
                val transactionId: Int = intent.getIntExtra(TRANSACTION_ID, 0)
                replyNACK(transactionId.toUByte())
            }
        }

        scope.launch {
            IntentFilter(INTENT_APP_SEND).asFlow(context, exported = true).collect { intent ->
                logger.d { "Got outbound message" }
                val uuid = (intent.getSerializableExtra(APP_UUID) as? UUID?)?.toKotlinUuid() ?:
                    // Fallback to string
                    intent.getStringExtra(APP_UUID)?.let { Uuid.parseOrNull(it) } ?: return@collect

                // These receivers are exported and every live session sees every SEND broadcast,
                // so without this a session would relay messages addressed to a different
                // watchapp, and two concurrent sessions would each send the same message once.
                if (uuid != this@PebbleKitClassic.uuid) {
                    return@collect
                }

                val dictionary: PebbleClassicDictionary = PebbleClassicDictionary.fromJson(
                    intent.getStringExtra(MSG_DATA)
                )
                val transactionId: Int = intent.getIntExtra(TRANSACTION_ID, 0)

                val msg = AppMessageData(transactionId.toUByte(), uuid, dictionary.toAppMessageDict())
                val result = device.sendAppMessage(msg)
                logger.d { "Result from the app: $result" }
                val intentAction = when (result) {
                    is AppMessageResult.ACK -> INTENT_APP_RECEIVE_ACK
                    is AppMessageResult.NACK -> INTENT_APP_RECEIVE_NACK
                }

                val intent = Intent(intentAction).apply {
                    putExtra(TRANSACTION_ID, result.transactionId.toInt())
                }

                broadcastToCompanions(intent)
            }
        }
    }

    override suspend fun start(incomingAppMessages: Flow<AppMessageData>) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            logger.e(throwable) { "Unhandled exception in PebbleKitClassic: ${throwable.message}" }
        }
        val scope = connectionScope + Job() + CoroutineName("PebbleKitClassic-$uuid") + exceptionHandler
        runningScope = scope
        launchIncomingAppMessageHandler(incomingAppMessages, scope)
        launchOutgoingAppMessageHandlers(device, scope)
    }

    override suspend fun stop() {
        runningScope?.cancel()
    }
}

/**
 * Android packages the watchapp declares as its companions, deduplicated.
 *
 * An empty result means "no declaration to narrow to", which callers treat as the pre-existing
 * untargeted broadcast rather than as "send to nobody".
 */
internal fun PbwAppInfo.androidCompanionPackages(): List<String> =
    companionApp?.android?.apps.orEmpty().mapNotNull { it.pkg }.distinct()

private fun PebbleClassicDictionary.toAppMessageDict(): AppMessageDictionary {
    return tuples.mapValues {
        when (it.value.type) {
            PebbleTuple.TupleType.BYTES -> it.value.value as ByteArray
            PebbleTuple.TupleType.STRING -> it.value.value as String
            PebbleTuple.TupleType.UINT -> when (it.value.length) {
                1 -> (it.value.value as Number).toLong().toUByte()
                2 -> (it.value.value as Number).toLong().toUShort()
                4 -> (it.value.value as Number).toLong().toUInt()
                else -> error("Unknown UINT size ${it.value.length}")
            }

            PebbleTuple.TupleType.INT -> when (it.value.length) {
                1 -> (it.value.value as Number).toByte()
                2 -> (it.value.value as Number).toShort()
                4 -> (it.value.value as Number).toInt()
                else -> error("Unknown INT size ${it.value.length}")
            }
        }
    }
}

private fun AppMessageDictionary.toPebbleDictionary(): PebbleClassicDictionary {
    val dict = PebbleClassicDictionary()
    for ((k, value) in this) {
        when (value) {
            is String -> dict.addString(k, value)
            is UByteArray -> dict.addBytes(k, value.toByteArray())
            is ByteArray -> dict.addBytes(k, value)
            is Int -> dict.addInt32(k, value)
            is Long -> dict.addInt32(k, value.toInt())
            is ULong -> dict.addUint32(k, value.toInt())
            is UInt -> dict.addUint32(k, value.toInt())
            is Short -> dict.addInt16(k, value)
            is UShort -> dict.addUint16(k, value.toShort())
            is Byte -> dict.addInt8(k, value)
            is UByte -> dict.addUint8(k, value.toByte())
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
        }
    }

    return dict
}

/**
 * Intent broadcast to pebble.apk to indicate that a message was received from the watch. To avoid protocol timeouts
 * on the watch, applications *must* ACK or NACK all received messages.
 */
private const val INTENT_APP_ACK = "com.getpebble.action.app.ACK"

/**
 * Intent broadcast to pebble.apk to indicate that a message was unsuccessfully received from the watch.
 */
private const val INTENT_APP_NACK = "com.getpebble.action.app.NACK"

/**
 * Intent broadcast from pebble.apk containing one-or-more key-value pairs sent from the watch to the phone.
 */
private const val INTENT_APP_RECEIVE = "com.getpebble.action.app.RECEIVE"

/**
 * Intent broadcast from pebble.apk indicating that a sent message was successfully received by a watch app.
 */
private const val INTENT_APP_RECEIVE_ACK = "com.getpebble.action.app.RECEIVE_ACK"

/**
 * Intent broadcast from pebble.apk indicating that a sent message was not received by a watch app.
 */
private const val INTENT_APP_RECEIVE_NACK = "com.getpebble.action.app.RECEIVE_NACK"

/**
 * Intent broadcast to pebble.apk containing one-or-more key-value pairs to be sent to the watch from the phone.
 */
private const val INTENT_APP_SEND = "com.getpebble.action.app.SEND"

/**
 * The bundle-key used to store a message's transaction id.
 */
private const val TRANSACTION_ID = "transaction_id"

/**
 * The bundle-key used to store a message's UUID.
 */
private const val APP_UUID = "uuid"

/**
 * The bundle-key used to store a message's JSON payload send-to or received-from the watch.
 */
private const val MSG_DATA = "msg_data"
