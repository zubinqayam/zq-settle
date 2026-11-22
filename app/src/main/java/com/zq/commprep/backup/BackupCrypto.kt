package com.zq.commprep.backup

import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Passphrase-based backup encryption, done entirely on the device (checklist 6.8).
 *
 *  key   = PBKDF2-HMAC-SHA256(passphrase, random 16-byte salt, ITERATIONS, 256 bit)
 *  data  = AES-256-GCM(key, random 12-byte IV), 128-bit tag
 *  file  = "CPB1" | iterations (int32) | salt(16) | iv(12) | ciphertext+tag
 *
 * The passphrase is only held in memory for the duration of the call; it is never stored
 * or sent. Without it the file cannot be opened. Uses only the platform JCA provider.
 */
object BackupCrypto {
    private val MAGIC = byteArrayOf('C'.code.toByte(), 'P'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
    const val ITERATIONS = 210_000
    const val MIN_PASSPHRASE_LENGTH = 8
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    class WrongPassphraseOrCorruptException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

    private val random = SecureRandom()

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(plain: ByteArray, passphrase: CharArray, iterations: Int = ITERATIONS): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters" }
        val salt = ByteArray(SALT_LEN).also(random::nextBytes)
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(MAGIC)
        val ct = cipher.doFinal(plain)
        return ByteBuffer.allocate(MAGIC.size + 4 + SALT_LEN + IV_LEN + ct.size)
            .put(MAGIC).putInt(iterations).put(salt).put(iv).put(ct).array()
    }

    /** Throws [WrongPassphraseOrCorruptException] for a wrong passphrase or a damaged / foreign file. */
    fun decrypt(file: ByteArray, passphrase: CharArray): ByteArray {
        if (file.size < MAGIC.size + 4 + SALT_LEN + IV_LEN + TAG_BITS / 8 ||
            !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
        ) throw WrongPassphraseOrCorruptException("This is not a ZQ Settle backup file.")
        val buf = ByteBuffer.wrap(file)
        buf.position(MAGIC.size)
        val iterations = buf.int
        if (iterations < 10_000 || iterations > 10_000_000) throw WrongPassphraseOrCorruptException("Backup file is damaged.")
        val salt = ByteArray(SALT_LEN).also { buf.get(it) }
        val iv = ByteArray(IV_LEN).also { buf.get(it) }
        val ct = ByteArray(buf.remaining()).also { buf.get(it) }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(MAGIC)
            cipher.doFinal(ct)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseOrCorruptException("Wrong passphrase, or the file is damaged. Nothing was changed.", e)
        } catch (e: GeneralSecurityException) {
            throw WrongPassphraseOrCorruptException("Could not open the backup. Nothing was changed.", e)
        }
    }
}
