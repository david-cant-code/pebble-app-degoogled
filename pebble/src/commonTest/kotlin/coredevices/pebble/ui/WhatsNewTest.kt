package coredevices.pebble.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Fork: pins the What's-new content conventions (see WhatsNew.kt). These are data
 * invariants a later edit could silently break: forgetting the revision bump when an
 * entry is added means the notice never auto-shows for it, and bumping without an
 * entry re-announces stale news as if it were new.
 */
class WhatsNewTest {

    @Test
    fun revisionEqualsEntryCount() {
        assertEquals(
            WHATS_NEW_VERSION,
            whatsNewEntries.size,
            "One prepended entry per revision bump: WHATS_NEW_VERSION and the entry " +
                "count must move together",
        )
    }

    @Test
    fun entriesHaveRealContent() {
        assertTrue(whatsNewEntries.isNotEmpty(), "the popup must have something to show")
        whatsNewEntries.forEach { entry ->
            assertTrue(entry.title.isNotBlank(), "entry title must not be blank")
            assertTrue(entry.body.isNotBlank(), "entry body must not be blank")
        }
    }
}
