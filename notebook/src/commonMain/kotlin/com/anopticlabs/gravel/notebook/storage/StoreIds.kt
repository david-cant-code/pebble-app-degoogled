package com.anopticlabs.gravel.notebook.storage

private val STORE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
private val NOTE_ID = Regex("^[0-9a-f]{32}$")

/** A capture or completion id: plain enough to be a file name and a JSON key as it is. */
fun isValidStoreId(id: String): Boolean = STORE_ID.matches(id)

/** A note's id and the name of its file: 32 lowercase hex characters. */
fun isValidNoteId(id: String): Boolean = NOTE_ID.matches(id)

/** Epoch millis of 2200-01-01T00:00:00Z: a stored instant past it, or not after the epoch, is refused. */
const val MAX_STORED_INSTANT_MILLIS = 7_258_118_400_000L

fun isStoredInstant(millis: Long): Boolean = millis in 1..MAX_STORED_INSTANT_MILLIS
