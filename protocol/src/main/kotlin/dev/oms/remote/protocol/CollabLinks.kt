package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.CollabLink
import dev.oms.remote.protocol.api.InvalidCollabLinkException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Collab link parser: a string-level port of `parseCollabLink` (spec 1.3) that follows the
 * WHATWG URL rules the TypeScript relies on (lowercase scheme/host, default ports dropped,
 * fragment = everything after the first `#`).
 */
internal object CollabLinks {
    const val DEFAULT_RELAY = "wss://my.omp.sh"

    private const val KEY_BYTES = 32
    private const val TOKEN_BYTES = 16
    private const val VIEW_SECRET_CHARS = 43
    private const val FULL_SECRET_CHARS = 64

    const val ERR_EMPTY = "Paste or scan an oms collab link"
    const val ERR_NOT_A_LINK = "Not an oms collab link"
    const val ERR_MISSING_SECRET = "The link is missing its secret key"
    const val ERR_SECRET_CHARS = "The link's secret key is not valid base64url"
    const val ERR_SECRET_LENGTH = "Secret has the wrong length"
    const val ERR_SCHEME_PREFIX = "Unsupported link scheme: "

    private val ROOM_PATH = Regex("^(.*)/r/([A-Za-z0-9_-]{10,64})(?:\\.([A-Za-z0-9_-]+))?$")
    private val BARE = Regex("^([A-Za-z0-9_-]{10,64})[#.]([A-Za-z0-9_-]+)$")
    private val HAS_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val B64URL = Regex("^[A-Za-z0-9_-]+$")
    private val PERCENT_23 = Regex("%23", RegexOption.IGNORE_CASE)
    private val JOIN_COMMAND = Regex("^oms\\s+join(?:\\s+(.*))?$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val QUOTES = mapOf('"' to '"', '\'' to '\'', '“' to '”', '‘' to '’', '«' to '»')
    private val HTTP_SCHEMES = setOf("http", "https")

    /** @throws InvalidCollabLinkException with a short user-readable message. */
    fun parse(input: String): CollabLink {
        val text = unwrapJoinCommand(input.trim())
        if (text.isEmpty()) throw InvalidCollabLinkException(ERR_EMPTY)
        return when (val result = parseLink(text)) {
            is Result.Ok -> CollabLink(
                roomUrl = result.roomUrl,
                roomId = result.roomId,
                key = result.key,
                writeToken = result.writeToken,
                raw = text,
            )
            is Result.Err -> throw InvalidCollabLinkException(result.message)
        }
    }

    /** `/collab` prints `oms join "<link>"` for custom relays (spec 1.5); accept it pasted verbatim. */
    private fun unwrapJoinCommand(text: String): String {
        val match = JOIN_COMMAND.matchEntire(text) ?: return text
        val arg = match.groupValues[1].trim()
        val close = arg.firstOrNull()?.let(QUOTES::get) ?: return arg
        val end = arg.indexOf(close, startIndex = 1)
        return (if (end > 0) arg.substring(1, end) else arg.substring(1)).trim()
    }

    private sealed interface Result {
        class Ok(val roomUrl: String, val roomId: String, val key: ByteArray, val writeToken: ByteArray?) : Result

        class Err(val message: String) : Result
    }

    private fun parseLink(link: String): Result {
        var text = link.trim().replace(PERCENT_23, "#")
        val bare = BARE.matchEntire(text)
        if (bare != null) {
            text = "$DEFAULT_RELAY/r/${bare.groupValues[1]}.${bare.groupValues[2]}"
        } else if (!HAS_SCHEME.containsMatchIn(text)) {
            text = "wss://$text"
        }
        val url = SimpleUrl.parse(text) ?: return Result.Err(ERR_NOT_A_LINK)
        val fragment = url.fragment.orEmpty()
        var fragmentError: Result.Err? = null
        if (url.scheme in HTTP_SCHEMES && fragment.isNotEmpty()) {
            when (val inner = parseLink(fragment)) {
                is Result.Ok -> return inner
                is Result.Err -> fragmentError = inner
            }
        }
        val match = ROOM_PATH.matchEntire(url.path)
        val base = relayBase(url, match?.groupValues?.get(1).orEmpty())
            ?: return Result.Err(ERR_SCHEME_PREFIX + url.scheme)
        if (match == null) {
            if (fragment.isNotEmpty() && url.scheme !in HTTP_SCHEMES) return parseLink(fragment)
            // A browser link's fragment is the real payload; its error is the more precise one.
            return fragmentError ?: Result.Err(ERR_NOT_A_LINK)
        }
        val roomId = match.groupValues[2]
        val secretText = match.groupValues[3].ifEmpty { fragment }
        if (secretText.isEmpty()) return Result.Err(ERR_MISSING_SECRET)
        if (!B64URL.matches(secretText)) return Result.Err(ERR_SECRET_CHARS)
        if (secretText.length != VIEW_SECRET_CHARS && secretText.length != FULL_SECRET_CHARS) {
            return Result.Err(ERR_SECRET_LENGTH)
        }
        val secret = runCatching { Base64Url.decode(secretText) }.getOrNull()
        if (secret == null || (secret.size != KEY_BYTES && secret.size != KEY_BYTES + TOKEN_BYTES)) {
            return Result.Err(ERR_SECRET_LENGTH)
        }
        return Result.Ok(
            roomUrl = "$base/r/$roomId",
            roomId = roomId,
            key = secret.copyOfRange(0, KEY_BYTES),
            writeToken = if (secret.size > KEY_BYTES) secret.copyOfRange(KEY_BYTES, KEY_BYTES + TOKEN_BYTES) else null,
        )
    }

    /** `normalizeRelayBaseUrl`: ws(s) scheme, host, non-default port, prefix without trailing slashes. */
    private fun relayBase(url: SimpleUrl, prefix: String): String? {
        val wsScheme = when (url.scheme) {
            "wss", "https" -> "wss"
            "ws", "http" -> "ws"
            else -> return null
        }
        val port = url.port?.let { ":$it" }.orEmpty()
        return "$wsScheme://${url.host}$port${prefix.trimEnd('/')}"
    }

    /** The subset of a WHATWG URL the parser needs. [port] is null when absent or the scheme default. */
    private class SimpleUrl(val scheme: String, val host: String, val port: Int?, val path: String, val fragment: String?) {
        companion object {
            private val DEFAULT_PORTS = mapOf("http" to 80, "https" to 443, "ws" to 80, "wss" to 443, "ftp" to 21)

            fun parse(text: String): SimpleUrl? {
                val schemeEnd = text.indexOf("://")
                if (schemeEnd <= 0) return null
                val scheme = text.substring(0, schemeEnd).lowercase()
                val rest = text.substring(schemeEnd + 3)
                val hashAt = rest.indexOf('#')
                val fragment = if (hashAt >= 0) rest.substring(hashAt + 1) else null
                val beforeHash = if (hashAt >= 0) rest.substring(0, hashAt) else rest
                val beforeQuery = beforeHash.substringBefore('?')
                val slashAt = beforeQuery.indexOf('/')
                val authority = if (slashAt >= 0) beforeQuery.substring(0, slashAt) else beforeQuery
                val path = if (slashAt >= 0) beforeQuery.substring(slashAt) else "/"
                val hostPort = authority.substringAfterLast('@')
                val (rawHost, rawPort) = splitHostPort(hostPort) ?: return null
                val host = canonicalHost(rawHost) ?: return null
                val port = when {
                    rawPort.isNullOrEmpty() -> null
                    !rawPort.all(Char::isDigit) -> return null
                    else -> rawPort.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
                }
                return SimpleUrl(scheme, host, port?.takeIf { it != DEFAULT_PORTS[scheme] }, path, fragment)
            }

            private fun splitHostPort(hostPort: String): Pair<String, String?>? {
                if (hostPort.startsWith("[")) {
                    val close = hostPort.indexOf(']')
                    if (close < 0) return null
                    val after = hostPort.substring(close + 1)
                    if (after.isNotEmpty() && !after.startsWith(":")) return null
                    return hostPort.substring(0, close + 1) to after.removePrefix(":").takeIf { after.isNotEmpty() }
                }
                val colon = hostPort.lastIndexOf(':')
                return if (colon >= 0) hostPort.substring(0, colon) to hostPort.substring(colon + 1) else hostPort to null
            }

            /** Lowercased, IDNA-mapped host via OkHttp; IPv6 stays bracketed. Null when invalid. */
            private fun canonicalHost(raw: String): String? {
                if (raw.isEmpty()) return null
                val host = "http://$raw/".toHttpUrlOrNull()?.host ?: return null
                return if (':' in host) "[$host]" else host
            }
        }
    }
}
