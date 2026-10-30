package com.anopticlabs.gravel.watchapp

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageDictionary
import io.rebble.libpebblecommon.voice.VoiceEncoderInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

/**
 * A watch app whose recordings and AppMessages the embedding app takes itself, in place of
 * dictation and of any companion app. libpebble3 routes on [appUuid] alone, which any watch app
 * can declare: whether the copy on the watch is one to accept is the handler's decision.
 */
interface WatchAppCaptures {
    val appUuid: Uuid

    /**
     * Takes one recording from [appUuid]. Called once the watch has been told to start, without
     * the dictation provider's readiness check; [frames] completes when the watch ends the
     * recording. Returns the text the watch shows, sent under the dictation deadline; blank text
     * is sent as a failed dictation, since the watch rejects a transcription with no words
     * (PebbleOS 70ed290, src/fw/services/voice/transcription.c, transcription_validate).
     */
    suspend fun capture(encoderInfo: VoiceEncoderInfo, frames: Flow<UByteArray>): String

    /** One inbound AppMessage from [appUuid], ACKed once this returns. */
    suspend fun onMessage(data: AppMessageDictionary)
}

/** The embedder's [WatchAppCaptures], or none. */
class InjectedWatchAppCaptures(val captures: WatchAppCaptures?)

/**
 * Starts the session of the running app [appUuid]: [relay] for the embedder's capture app, which
 * gets no companion app and so never reaches its PBW load, else [companions].
 */
suspend fun startAppSession(
    appUuid: Uuid,
    injected: InjectedWatchAppCaptures,
    relay: suspend (WatchAppCaptures) -> Unit,
    companions: suspend () -> Unit,
) {
    val captures = injected.captures?.takeIf { it.appUuid == appUuid }
    if (captures != null) relay(captures) else companions()
}

/**
 * Hands each of [messages] to [captures] and then ACKs it, until [messages] ends; a handler
 * failure is logged and the message still ACKed, so the watch app is not left waiting.
 */
suspend fun relayWatchAppMessages(
    messages: Flow<AppMessageData>,
    captures: WatchAppCaptures,
    ack: suspend (transactionId: UByte) -> Unit,
) {
    messages.collect { message ->
        try {
            captures.onMessage(message.data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "watch app message not handled" }
        }
        ack(message.transactionId)
    }
}

private val logger = Logger.withTag("WatchAppCaptures")
