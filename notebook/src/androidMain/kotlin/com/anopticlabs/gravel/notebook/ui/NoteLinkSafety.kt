package com.anopticlabs.gravel.notebook.ui

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/** Which of a note's links may be handed to another app, and what the confirmation shows of them. */
object NoteLinkSafety {
    private val ALLOWED_SCHEMES = setOf("https", "mailto", "tel")

    /**
     * The link to open for [link] as a note wrote it: the trimmed text when it is printable ASCII
     * with an https, mailto or tel scheme, and, for https, a host and no user part; null for
     * anything else, including text that is not a URI. Printable ASCII only, so the confirmation
     * shows the characters that open, with no direction override, invisible or look-alike letter.
     */
    fun allowed(link: String): String? {
        val trimmed = link.trim()
        if (trimmed.isEmpty() || trimmed.any { it !in '!'..'~' }) return null
        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return null
        }
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in ALLOWED_SCHEMES) return null
        if (scheme == "https" && (uri.host.isNullOrEmpty() || uri.rawUserInfo != null)) return null
        return trimmed
    }

    /** The host an allowed https link opens, for the confirmation to show on its own; null for mailto and tel. */
    fun host(allowedLink: String): String? = try {
        URI(allowedLink).takeIf { it.scheme.equals("https", ignoreCase = true) }?.host
    } catch (e: URISyntaxException) {
        null
    }
}
