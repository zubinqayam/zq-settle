package com.zq.commprep

import com.zq.commprep.backup.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupCryptoTest {
    private val plain = """{"format":"commprep-data-v1","history":[]}""".toByteArray()
    private val pass = "correct horse battery".toCharArray()

    @Test fun round_trip() {
        val enc = BackupCrypto.encrypt(plain, pass.copyOf(), iterations = 20_000)
        assertArrayEquals(plain, BackupCrypto.decrypt(enc, pass.copyOf()))
    }
    @Test fun ciphertext_does_not_contain_plaintext() {
        val enc = BackupCrypto.encrypt(plain, pass.copyOf(), iterations = 20_000)
        assertFalse(String(enc, Charsets.ISO_8859_1).contains("commprep-data"))
    }
    @Test fun wrong_passphrase_fails_safely() {
        val enc = BackupCrypto.encrypt(plain, pass.copyOf(), iterations = 20_000)
        assertThrows(BackupCrypto.WrongPassphraseOrCorruptException::class.java) {
            BackupCrypto.decrypt(enc, "wrong passphrase!".toCharArray())
        }
    }
    @Test fun tampered_file_fails_safely() {
        val enc = BackupCrypto.encrypt(plain, pass.copyOf(), iterations = 20_000)
        enc[enc.size - 1] = (enc[enc.size - 1].toInt() xor 1).toByte()
        assertThrows(BackupCrypto.WrongPassphraseOrCorruptException::class.java) { BackupCrypto.decrypt(enc, pass.copyOf()) }
    }
    @Test fun non_backup_file_rejected() {
        assertThrows(BackupCrypto.WrongPassphraseOrCorruptException::class.java) { BackupCrypto.decrypt(plain, pass.copyOf()) }
    }
    @Test fun short_passphrase_rejected() {
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.encrypt(plain, "short".toCharArray()) }
    }
}
