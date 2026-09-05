package coredevices.pebble.ui

import coredevices.util.transcription.ServerUrlProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the server dialog's pure decisions: the words for a refused URL
 * and a test status, and which token a request carries once the URL has
 * been edited.
 */
class SelfHostedServerDialogTest {

    @Test
    fun urlProblemsAreNamedAndAnEmptyUrlIsNotOne() {
        assertEquals("", serverUrlProblemText(ServerUrlProblem.Empty))
        assertTrue(serverUrlProblemText(ServerUrlProblem.NotHttps).startsWith("Only https:// URLs"))
        assertTrue(serverUrlProblemText(ServerUrlProblem.HasCredentials).contains("token field"))
        for (problem in ServerUrlProblem.entries.filter { it != ServerUrlProblem.Empty }) {
            assertTrue(serverUrlProblemText(problem).isNotBlank(), problem.name)
        }
    }

    @Test
    fun testStatusNamesTheTokenAndThePathCases() {
        assertEquals("Server answered. Dictation can use it.", serverTestStatusText(200))
        assertEquals("Server answered. Dictation can use it.", serverTestStatusText(204))
        assertTrue(serverTestStatusText(401).contains("token"))
        assertTrue(serverTestStatusText(403).contains("token"))
        val notFound = serverTestStatusText(404)
        assertTrue(notFound.contains("/inference") && notFound.contains("/v1/audio/transcriptions"))
        assertEquals("Server returned HTTP 502.", serverTestStatusText(502))
    }

    @Test
    fun theSavedTokenStaysWithTheServerItWasSavedFor() {
        val saved = "tok-123"
        val home = "stt.example.net:443"
        assertEquals(saved, effectiveServerToken("", clearToken = false, saved, home, home))
        // Same server, different path: still the same host and port.
        assertEquals(saved, effectiveServerToken("  ", clearToken = false, saved, home, "stt.example.net:443"))
        assertNull(effectiveServerToken("", clearToken = false, saved, home, "stt.exarnple.net:443"))
        assertNull(effectiveServerToken("", clearToken = false, saved, home, "stt.example.net:8443"))
        assertNull(effectiveServerToken("", clearToken = false, saved, home, null), "no server, no token")
        assertNull(effectiveServerToken("", clearToken = true, saved, home, home))
        assertEquals("typed", effectiveServerToken(" typed ", clearToken = false, saved, home, "other.test:443"))
        assertNull(effectiveServerToken("", clearToken = false, saved = null, savedHostPort = null, hostPort = home))
    }

    @Test
    fun theTokenLabelSaysWhetherTheSavedTokenApplies() {
        val home = "stt.example.net:443"
        assertEquals("Bearer token (optional)", tokenFieldLabel("", clearToken = false, hasSaved = false, home, home))
        assertEquals("Bearer token (saved; type to replace)", tokenFieldLabel("", clearToken = false, hasSaved = true, home, home))
        assertEquals(
            "Bearer token (the saved one stays with the previous server)",
            tokenFieldLabel("", clearToken = false, hasSaved = true, home, "other.test:443"),
        )
        assertEquals("Bearer token (optional)", tokenFieldLabel("typed", clearToken = false, hasSaved = true, home, home))
        assertEquals("Bearer token (optional)", tokenFieldLabel("", clearToken = true, hasSaved = true, home, home))
    }
}
