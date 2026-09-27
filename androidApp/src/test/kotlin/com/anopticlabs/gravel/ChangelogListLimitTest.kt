package com.anopticlabs.gravel

import coredevices.coreapp.TrackedTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The changelog workflow's parser and the app's list reader use the same size limit. */
class ChangelogListLimitTest {

    private fun limit(path: String, pattern: String) = assertNotNull(
        Regex(pattern, RegexOption.MULTILINE).find(TrackedTree.file(path).readText())?.groupValues?.get(1),
        "no MAX_LIST_BYTES in $path",
    ).split("*").map { it.trim().toLong() }.reduce(Long::times)

    @Test
    fun theParserAndTheAppShareTheSizeLimit() {
        assertEquals(
            limit("pebble/src/commonMain/kotlin/com/anopticlabs/gravel/firmware/PebbleOsChangelogList.kt", """const val MAX_LIST_BYTES = ([0-9 *]+)$"""),
            limit(".github/changelog/pebbleos_changelog.py", """^MAX_LIST_BYTES = ([0-9 *]+)$"""),
        )
    }
}
