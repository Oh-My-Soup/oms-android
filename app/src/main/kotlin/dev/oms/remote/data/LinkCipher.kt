package dev.oms.remote.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable key in AndroidKeyStore. Output is base64(`IV ‖ ciphertext+tag`).
 * The key never leaves the keystore, so a copied preferences file is useless on another device.
 */
internal class LinkCipher(private val alias: String) {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    fun encrypt(plain: String, associatedData: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(associatedData.encodeToByteArray())
        val iv = cipher.iv
        if (iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected GCM IV length ${iv.size}")
        val sealed = cipher.doFinal(plain.encodeToByteArray())
        return Base64.getEncoder().encodeToString(iv + sealed)
    }

    /** @throws GeneralSecurityException when the blob is corrupt or the key was lost. */
    fun decrypt(blob: String, associatedData: String): String {
        val bytes = try {
            Base64.getDecoder().decode(blob)
        } catch (e: IllegalArgumentException) {
            throw GeneralSecurityException("Corrupt sealed link", e)
        }
        if (bytes.size <= IV_BYTES + TAG_BITS / 8) throw GeneralSecurityException("Sealed link too short")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        cipher.updateAAD(associatedData.encodeToByteArray())
        return cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES).decodeToString()
    }

    @Synchronized
    private fun key(): SecretKey {
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
