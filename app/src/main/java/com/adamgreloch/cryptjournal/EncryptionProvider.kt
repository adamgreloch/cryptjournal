package com.adamgreloch.cryptjournal

import android.content.SharedPreferences
import org.bouncycastle.util.io.Streams
import org.pgpainless.PGPainless
import org.pgpainless.PGPainless.*
import org.pgpainless.algorithm.DocumentSignatureType
import org.pgpainless.decryption_verification.ConsumerOptions
import org.pgpainless.encryption_signing.EncryptionOptions
import org.pgpainless.encryption_signing.EncryptionStream
import org.pgpainless.encryption_signing.ProducerOptions
import org.pgpainless.encryption_signing.SigningOptions
import org.pgpainless.key.protection.SecretKeyRingProtector
import org.pgpainless.util.Passphrase
import java.io.ByteArrayOutputStream
import java.io.InputStream

class EncryptionProvider(private val pref: SharedPreferences) {

    fun importKey(userId: String, asciiSecretKey: String, password: String) {
        val secretKeyRing = readKeyRing().secretKeyRing(asciiSecretKey)

        with (pref.edit()) {
            putString("user_id", userId)
            putString("secret_key", asciiSecretKey)
            putString("public_key", asciiArmor(extractCertificate(secretKeyRing)))
            putString("password", password)
            apply()
        }
    }

    private fun getAsciiSecretKey() : String {
        return pref.getString("secret_key", null) ?: ""
    }

    private fun getAsciiPublicKey() : String {
        return pref.getString("public_key", null) ?: ""
    }

    private fun getPassword() : String {
        return pref.getString("password", null) ?: ""
    }

    fun encryptText(plain: String): String {
        val passphrase = Passphrase.fromPassword(getPassword())

        val secret = readKeyRing().secretKeyRing(getAsciiSecretKey())
        val cert = readKeyRing().publicKeyRing(getAsciiPublicKey())

        val plainStream: InputStream = plain.byteInputStream()
        val encryptedStream = ByteArrayOutputStream()

        val protector = SecretKeyRingProtector.unlockAnyKeyWith(passphrase)

        val encryptor: EncryptionStream = encryptAndOrSign()
            .onOutputStream(encryptedStream).withOptions(
                ProducerOptions.signAndEncrypt(
                EncryptionOptions.encryptCommunications()
                    .addRecipient(cert),
                    SigningOptions()
                        .addInlineSignature(protector, secret, DocumentSignatureType.CANONICAL_TEXT_DOCUMENT)
                ).setAsciiArmor(true)
            )

        Streams.pipeAll(plainStream, encryptor)
        encryptor.close()

        return encryptedStream.toString()
    }

    fun decryptText(encrypted: String): String {
        val passphrase = Passphrase.fromPassword(getPassword())

        val secret = readKeyRing().secretKeyRing(getAsciiSecretKey())
        val cert = readKeyRing().publicKeyRing(getAsciiPublicKey())

        val encryptedStream: InputStream = encrypted.byteInputStream()
        val decrypted = ByteArrayOutputStream()

        val protector = SecretKeyRingProtector.unlockAnyKeyWith(passphrase)

        val decryptor = decryptAndOrVerify()
            .onInputStream(encryptedStream)
            .withOptions(ConsumerOptions()
                .addDecryptionKey(secret, protector)
                .addVerificationCert(cert))

        Streams.pipeAll(decryptor, decrypted)
        decryptor.close()

        return decrypted.toString()
    }

}
