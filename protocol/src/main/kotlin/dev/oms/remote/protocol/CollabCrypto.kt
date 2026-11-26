package dev.oms.remote.protocol

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** RFC 4648 section 5 base64url without padding (spec 1.1). */
internal object Base64Url {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)

    /** @throws IllegalArgumentException on invalid input. */
    fun decode(text: String): ByteArray = decoder.decode(text)
}

/**
 * AES-256-GCM sealing of collab frames (spec 3.1): `IV(12) ∥ ciphertext ∥ tag(16)`, no AAD.
 * A fresh [Cipher] is initialised for every frame; IVs are never reused.
 */
internal class RoomCipher(key: ByteArray) {
    private val keySpec: SecretKeySpec

    init {
        require(key.size == KEY_BYTES) { "Room key must be $KEY_BYTES bytes" }
        keySpec = SecretKeySpec(key, "AES")
    }

    fun seal(plaintext: ByteArray, iv: ByteArray = randomIv()): ByteArray {
        require(iv.size == IV_BYTES) { "IV must be $IV_BYTES bytes" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    /** @throws java.security.GeneralSecurityException when authentication fails. */
    fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= IV_BYTES) throw java.security.GeneralSecurityException("Sealed frame too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    companion object {
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val rng = SecureRandom()

        fun randomIv(): ByteArray = ByteArray(IV_BYTES).also(rng::nextBytes)
    }
}

/** `[u32 BE peerId][sealed]` relay envelope (spec 2.2). */
internal object Envelope {
    class Unpacked(val peerId: Long, val sealed: ByteArray)

    fun pack(sealed: ByteArray, peerId: Int = 0): ByteArray =
        ByteBuffer.allocate(4 + sealed.size).putInt(peerId).put(sealed).array()

    /** Null for envelopes shorter than the 4-byte header (they are ignored). */
    fun unpack(bytes: ByteArray): Unpacked? {
        if (bytes.size < 4) return null
        val peer = ByteBuffer.wrap(bytes, 0, 4).int.toLong() and 0xFFFFFFFFL
        return Unpacked(peer, bytes.copyOfRange(4, bytes.size))
    }
}
