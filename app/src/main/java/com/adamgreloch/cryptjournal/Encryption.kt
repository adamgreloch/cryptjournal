package com.adamgreloch.cryptjournal

import org.bouncycastle.util.io.Streams
import org.pgpainless.PGPainless
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

fun encryptText(plain: String, secretKey: String, publicKey: String, password: String): String {

    val passphrase = Passphrase.fromPassword(password)

    val secret = PGPainless.readKeyRing().secretKeyRing(secretKey)
    val cert = PGPainless.readKeyRing().publicKeyRing(publicKey)

    val plainStream: InputStream = plain.byteInputStream()
    val encryptedStream = ByteArrayOutputStream()

    val protector = SecretKeyRingProtector.unlockAnyKeyWith(passphrase)

    val encryptor: EncryptionStream = PGPainless.encryptAndOrSign()
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

fun decryptText(encrypted: String, secretKey: String, publicKey: String, password: String): String {

    val passphrase = Passphrase.fromPassword(password)

    val secret = PGPainless.readKeyRing().secretKeyRing(secretKey)
    val cert = PGPainless.readKeyRing().publicKeyRing(publicKey)

    val encryptedStream: InputStream = encrypted.byteInputStream()
    val decrypted = ByteArrayOutputStream()

    val protector = SecretKeyRingProtector.unlockAnyKeyWith(passphrase)

    val decryptor = PGPainless.decryptAndOrVerify()
        .onInputStream(encryptedStream)
        .withOptions(ConsumerOptions()
            .addDecryptionKey(secret, protector)
            .addVerificationCert(cert))

    Streams.pipeAll(decryptor, decrypted)
    decryptor.close()

    val metadata = decryptor.result
//    assert(metadata.isEncrypted)
//    assert(metadata.containsVerifiedSignatureFrom(cert))

    return decrypted.toString()
}
