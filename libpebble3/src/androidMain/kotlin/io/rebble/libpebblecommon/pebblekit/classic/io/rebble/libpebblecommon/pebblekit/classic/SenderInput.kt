package io.rebble.libpebblecommon.pebblekit.classic

import kotlin.coroutines.cancellation.CancellationException

/**
 * Fork: handles one broadcast an exported classic PebbleKit receiver took in, dropping it if the
 * handling throws anything but cancellation, errors included (below); uncaught, the throw would
 * end the collector, a session's receivers with it. A read that never returns blocks the
 * collector (KNOWN_ISSUES).
 *
 * Reading the extras deserializes what the sending app wrote. Platform behaviour relied on:
 * - oreo-release to android12L-release, BaseBundle.unparcel: the first read of any extra
 *   deserializes every entry, so an unloadable Serializable under any key throws
 *   (Parcel.readSerializable).
 * - android16-release, BaseBundle.initializeFromParcelLocked, Parcel.readLazyValue: from
 *   Android 13 an entry is deserialized when its key is read, and an unknown type code throws
 *   BadParcelableException (Parcel.readValue) on the first read of any extra.
 * - android16-release, libcore ObjectInputStream.readArray: arrays are allocated at the stated
 *   length, so a few bytes raise OutOfMemoryError; nested arrays raise StackOverflowError.
 * - android16-release, Parcel.readSerializableInternal: a typed read checks a class name the
 *   sender writes apart from the serialized bytes, so it does not bound what is deserialized.
 */
internal inline fun <T> handleSenderInput(onDropped: (Throwable) -> Unit, block: () -> T): T? =
    try {
        block()
    } catch (t: Throwable) {
        if (t is CancellationException) throw t
        onDropped(t)
        null
    }
