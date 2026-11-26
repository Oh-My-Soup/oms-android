package dev.oms.remote.protocol

import dev.oms.remote.protocol.api.CollabSessions
import dev.oms.remote.protocol.api.InvalidCollabLinkException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollabLinksTest {
    private val fullSecret = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh-goaKjpKWmp6ipqqusra6v"

    /** Every accepted form in the TypeScript-generated vectors resolves to the same room, key and token. */
    @Test
    fun parsesEveryAcceptedVectorForm() {
        val accepted = Vectors.links.filter { "error" !in it.fields }
        assertTrue(accepted.size >= 10, "vector file should list the accepted forms")
        for (case in accepted) {
            val link = CollabSessions.parseLink(case.input)
            assertEquals(case.fields.getValue("wsUrl"), link.roomUrl, case.input)
            assertEquals(case.fields.getValue("roomId"), link.roomId, case.input)
            assertContentEquals(Fixtures.hex(case.fields.getValue("key")), link.key, case.input)
            val token = case.fields.getValue("writeToken")
            if (token.startsWith("(none")) {
                assertNull(link.writeToken, case.input)
            } else {
                assertContentEquals(Fixtures.hex(token.substringBefore(' ')), link.writeToken, case.input)
            }
        }
    }

    /** Each rejected vector maps to the matching user-readable error. */
    @Test
    fun rejectsInvalidVectorFormsWithReadableErrors() {
        val expected = mapOf(
            "Unsupported relay URL scheme: ftp:" to CollabLinks.ERR_SCHEME_PREFIX + "ftp",
            "Collab link key must be 32 (view) or 48 (full) base64url bytes" to CollabLinks.ERR_SECRET_LENGTH,
            "Collab link must contain a /r/<roomId> path" to CollabLinks.ERR_NOT_A_LINK,
        )
        val rejected = Vectors.links.filter { "error" in it.fields }
        assertEquals(expected.keys, rejected.map { it.fields.getValue("error") }.toSet())
        for (case in rejected) {
            val error = assertFailsWith<InvalidCollabLinkException>(case.input) { CollabSessions.parseLink(case.input) }
            assertEquals(expected.getValue(case.fields.getValue("error")), error.message, case.input)
        }
    }

    @Test
    fun acceptsTheOmsJoinCommandPrintedForCustomRelays() {
        val link = CollabSessions.parseLink("  oms join \"relay.example.com:8443/infra/oms/r/${Fixtures.ROOM_ID}.$fullSecret\"\n")
        assertEquals("wss://relay.example.com:8443/infra/oms/r/${Fixtures.ROOM_ID}", link.roomUrl)
        assertContentEquals(Fixtures.TOKEN, link.writeToken)
        assertEquals("relay.example.com:8443/infra/oms/r/${Fixtures.ROOM_ID}.$fullSecret", link.raw)
    }

    @Test
    fun normalizesHostCaseIpv6AndTrailingPrefixSlashes() {
        val ipv6 = CollabSessions.parseLink("ws://[::1]:7466/r/${Fixtures.ROOM_ID}.$fullSecret")
        assertEquals("ws://[::1]:7466/r/${Fixtures.ROOM_ID}", ipv6.roomUrl)
        assertEquals("[::1]:7466", ipv6.relayHost)
        val prefixed = CollabSessions.parseLink("HTTPS://user@Relay.Example:443/a//r/${Fixtures.ROOM_ID}.$fullSecret?x=1")
        assertEquals("wss://relay.example/a/r/${Fixtures.ROOM_ID}", prefixed.roomUrl)
    }

    @Test
    fun rejectsMissingSecretsBadCharactersAndEmptyInput() {
        fun error(input: String) = assertFailsWith<InvalidCollabLinkException> { CollabSessions.parseLink(input) }.message
        assertEquals(CollabLinks.ERR_MISSING_SECRET, error("wss://relay.test/r/${Fixtures.ROOM_ID}"))
        assertEquals(CollabLinks.ERR_SECRET_CHARS, error("wss://relay.test/r/${Fixtures.ROOM_ID}#${fullSecret.dropLast(1)}!"))
        assertEquals(CollabLinks.ERR_SECRET_LENGTH, error("${Fixtures.ROOM_ID}.${fullSecret}AAAA"))
        assertEquals(CollabLinks.ERR_EMPTY, error("   "))
        assertEquals(CollabLinks.ERR_NOT_A_LINK, error("hello world"))
    }
}
