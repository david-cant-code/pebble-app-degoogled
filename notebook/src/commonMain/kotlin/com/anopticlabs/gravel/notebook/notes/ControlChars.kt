package com.anopticlabs.gravel.notebook.notes

/** Removes the C0 control characters and DEL; tabs and newlines go with them. */
internal fun String.stripControlChars(): String =
    if (none { it.isControlChar() }) this else filterNot { it.isControlChar() }

private fun Char.isControlChar(): Boolean = code <= 0x1f || code == 0x7f
