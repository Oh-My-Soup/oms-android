package dev.oms.remote.protocol

import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollabCryptoTest {
    private val cipher = RoomCipher(Fixtures.KEY)
    private val fixedIv = Fixtures.hex("000102030405060708090a0b")

    /** Guest frames encode to the exact plaintext the TypeScript guest produces (optional fields omitted). */
    @Test
    fun guestFramesMatchVectorPlaintexts() {
        val byName = Vectors.seals.associateBy { it.name }
        assertEquals(
            byName.getValue("guest hello (full link, paged history)").plaintext,
            encodeJson(GuestFrames.hello("android", Fixtures.TOKEN)),
        )
        assertEquals(byName.getValue("guest prompt").plaintext, encodeJson(GuestFrames.prompt("héllo 👋")))
        assertEquals(
            byName.getValue("guest ui-response cancel (value omitted)").plaintext,
            encodeJson(GuestFrames.uiResponse(7, null)),
        )
        assertEquals(byName.getValue("guest snapshot-ack").plaintext, encodeJson(GuestFrames.snapshotAck(1)))
        assertEquals("""{"t":"hello","proto":5,"name":"android","history":"paged"}""", encodeJson(GuestFrames.hello("android", null)))
    }

    @Test
    fun sealsAndEnvelopesMatchVectorsByteForByte() {
        val guestCases = Vectors.seals.filter { it.name.startsWith("guest") }
        assertEquals(4, guestCases.size)
        for (case in guestCases) {
            val sealed = cipher.seal(case.plaintext.toByteArray(Charsets.UTF_8), fixedIv)
            assertContentEquals(case.sealed, sealed, case.name)
            assertContentEquals(case.envelope, Envelope.pack(sealed), case.name)
        }
    }

    @Test
    fun opensTargetedHostEnvelope() {
        val case = Vectors.seals.single { it.name.startsWith("host") }
        val envelope = requireNotNull(Envelope.unpack(case.envelope))
        assertEquals(3L, envelope.peerId)
        assertEquals(case.plaintext, cipher.open(envelope.sealed).toString(Charsets.UTF_8))
        assertEquals(HostFrame.UiRequestEnd(7), HostFrames.decode(case.plaintext))
    }

    @Test
    fun rejectsTamperedWrongKeyAndTruncatedFrames() {
        val sealed = Vectors.seals.first().sealed
        val tampered = sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFailsWith<GeneralSecurityException> { cipher.open(tampered) }
        assertFailsWith<GeneralSecurityException> { RoomCipher(ByteArray(32)).open(sealed) }
        assertFailsWith<GeneralSecurityException> { cipher.open(sealed.copyOf(12)) }
        assertNull(Envelope.unpack(byteArrayOf(0, 0, 0)))
    }

    @Test
    fun productionSealUsesFreshIvs() {
        val plaintext = "{}".toByteArray()
        val a = cipher.seal(plaintext)
        val b = cipher.seal(plaintext)
        assertTrue(!a.copyOf(12).contentEquals(b.copyOf(12)), "two seals must not share an IV")
        assertContentEquals(plaintext, cipher.open(a))
    }
}
