package dev.oms.remote.pairing

import dev.oms.remote.protocol.api.CollabLink
import dev.oms.remote.protocol.api.CollabSessions

/** Finds a collab link in free text (a paste, a share, a scanned QR). */
internal object LinkText {
    private val separators = Regex("\\s+")
    private val wrappers = charArrayOf('"', '\'', '`', '<', '>', '(', ')', '[', ']', '“', '”', '‘', '’')

    /**
     * Parses the whole trimmed text; failing that, the first whitespace-separated token that parses
     * (with quotes/brackets stripped, so `oms join "<link>"` and "join me: <link>" both work).
     *
     * @throws IllegalArgumentException the whole-text parse error (usually an
     *   [dev.oms.remote.protocol.api.InvalidCollabLinkException]) when nothing parses.
     */
    fun extract(text: String): CollabLink {
        val trimmed = text.trim()
        val wholeError = try {
            return CollabSessions.parseLink(trimmed)
        } catch (e: IllegalArgumentException) {
            e
        }
        for (token in trimmed.split(separators)) {
            val candidate = token.trim(*wrappers)
            if (candidate.isEmpty() || candidate == trimmed) continue
            try {
                return CollabSessions.parseLink(candidate)
            } catch (_: IllegalArgumentException) {
                // Not this token; keep looking.
            }
        }
        throw wholeError
    }
}
