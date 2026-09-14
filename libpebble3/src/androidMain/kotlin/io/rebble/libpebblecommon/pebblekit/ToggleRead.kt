package io.rebble.libpebblecommon.pebblekit

/**
 * Fork: reads a PebbleKit toggle where the system can call in before the Koin graph is loaded,
 * since providers are installed before `Application.onCreate` (android16-release
 * ActivityThread.handleBindApplication). A setting that cannot be read counts as off.
 */
internal inline fun toggleAllows(read: () -> Boolean?): Boolean =
    runCatching { read() }.getOrNull() == true
