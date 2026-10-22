package com.anopticlabs.gravel.notebook.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NoteLinkSafetyTest {

    @Test
    fun httpsMailtoAndTelAreAllowedAsTrimmed() {
        assertEquals("https://example.org/a?b=c", NoteLinkSafety.allowed("  https://example.org/a?b=c \n"))
        assertEquals("mailto:someone@example.org", NoteLinkSafety.allowed("mailto:someone@example.org"))
        assertEquals("tel:+15555550100", NoteLinkSafety.allowed("tel:+15555550100"))
        assertEquals("HTTPS://example.org", NoteLinkSafety.allowed("HTTPS://example.org"))
    }

    @Test
    fun anyOtherSchemeNoSchemeOrNoUriIsRefused() {
        for (link in listOf(
            "http://example.org", "intent://x#Intent;scheme=https;end", "javascript:alert(1)", "file:///sdcard/x",
            "content://x/y", "market://details?id=x", "//example.org/x", "www.example.org", "notes/x.md", "", "   ",
            "https://exa mple.org", "sms:5550100", "data:text/html,x",
        )) {
            assertNull(NoteLinkSafety.allowed(link), link)
        }
    }

    @Test
    fun directionControlsInvisiblesLookAlikesAndUserPartsAreRefused() {
        for (link in listOf(
            "https://\u202E@moc.elgoog.ec/", "https://\u202Eevil.com", "https://a.example/\u2067x", "https://goo\u200Bgle.com",
            "https://a.b\u00ADc.com", "https://g\u043Eogle.com", "https://good.example@evil.example", "https://u:p@example.org",
            "https:///path-only", "https:relative",
        )) {
            assertNull(NoteLinkSafety.allowed(link), link)
        }
    }

    @Test
    fun theConfirmationShowsTheHostOfAnHttpsLinkOnly() {
        assertEquals("example.org", NoteLinkSafety.host("https://example.org/a?b=c"))
        assertNull(NoteLinkSafety.host("mailto:someone@example.org"))
        assertNull(NoteLinkSafety.host("tel:+15555550100"))
    }
}
