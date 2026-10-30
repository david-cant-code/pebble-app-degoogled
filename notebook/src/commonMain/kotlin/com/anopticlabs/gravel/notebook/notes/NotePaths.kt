package com.anopticlabs.gravel.notebook.notes

/** The shape of a note's name within its folder: a plain ASCII file name. */
object NotePaths {
    const val MAX_NAME_CHARS = 128

    private val NOTE_NAME = Regex("^[A-Za-z0-9_-][A-Za-z0-9._-]*$")

    fun isValidName(name: String): Boolean = name.length <= MAX_NAME_CHARS && NOTE_NAME.matches(name)
}
