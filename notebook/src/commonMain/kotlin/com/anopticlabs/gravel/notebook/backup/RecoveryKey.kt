package com.anopticlabs.gravel.notebook.backup

/**
 * The key automatic backups are sealed with: [LENGTH] characters from [ALPHABET], held as a char
 * array the owner wipes. Shown uppercase in groups of four with `O` as `#` and `0` as `=`, so the
 * two never look alike, the mapping of Signal-Android main,
 * `core/models-jvm/.../AccountEntropyPool.kt`, `CHARACTER_DISPLAY_MAP`.
 */
object RecoveryKey {
    const val LENGTH = 64
    const val GROUP = 4
    const val ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** [random] returns a uniform int in `0 until bound`; on Android, `SecureRandom::nextInt`. */
    fun generate(random: (bound: Int) -> Int): CharArray = CharArray(LENGTH) { ALPHABET[random(ALPHABET.length)] }

    /** [key] as shown: `ABCD EFGH IJKL MNOP`, four groups to a line. */
    fun display(key: CharArray): String = buildString {
        for ((i, c) in key.withIndex()) {
            if (i > 0 && i % (GROUP * 4) == 0) append('\n') else if (i > 0 && i % GROUP == 0) append(' ')
            append(
                when (c) {
                    'o' -> '#'
                    '0' -> '='
                    else -> c.uppercaseChar()
                },
            )
        }
    }

    /**
     * The key in [typed], as [display] shows it or as stored: `#` and `=` map back, letters are
     * lowercased, spaces, dashes and other ASCII punctuation are dropped. Null when anything else
     * is in it or it does not hold exactly [LENGTH] characters.
     */
    fun parse(typed: CharSequence): CharArray? {
        val key = CharArray(LENGTH)
        var n = 0
        for (c in typed) {
            val k = when (c) {
                '#' -> 'o'
                '=' -> '0'
                in '0'..'9', in 'a'..'z' -> c
                in 'A'..'Z' -> c.lowercaseChar()
                else -> if (c.code < 0x80 && !c.isLetterOrDigit()) null else return key.wiped()
            } ?: continue
            if (n == LENGTH) return key.wiped()
            key[n++] = k
        }
        return if (n == LENGTH) key else key.wiped()
    }

    private fun CharArray.wiped(): CharArray? {
        fill('\u0000')
        return null
    }
}
