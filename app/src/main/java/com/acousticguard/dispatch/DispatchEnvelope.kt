package com.acousticguard.dispatch

import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.RSAPrivateCrtKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.security.KeyFactory
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import org.json.JSONObject

/** Versioned hybrid envelope used for end-to-end encryption at the user-owned receiver. */
object DispatchEnvelope {
    const val VERSION = 1
    const val ALGORITHM = "A256GCM+RSA-OAEP-256"

    fun encrypt(payload: ByteArray, recipientPublicKey: PublicKey, eventId: String): String {
        validateEventId(eventId)
        if (payload.size > MAX_PAYLOAD_BYTES) {
            throw DispatchEnvelopeException("The dispatch payload is too large.")
        }
        val rsaKey = recipientPublicKey as? java.security.interfaces.RSAPublicKey
            ?: throw DispatchEnvelopeException("The recipient key must be RSA.")
        if (rsaKey.modulus.bitLength() < 2_048) {
            throw DispatchEnvelopeException("The recipient RSA key must be at least 2048 bits.")
        }

        val keyId = DispatchValidation.publicKeyId(recipientPublicKey)
        val contentKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val iv = ByteArray(GCM_IV_BYTES).also { java.security.SecureRandom().nextBytes(it) }

        val aesCipher = Cipher.getInstance(AES_TRANSFORMATION)
        aesCipher.init(Cipher.ENCRYPT_MODE, contentKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        aesCipher.updateAAD(aad(eventId, keyId))
        val ciphertext = aesCipher.doFinal(payload)

        val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
        rsaCipher.init(Cipher.ENCRYPT_MODE, recipientPublicKey, OAEP_SPEC)
        val wrappedKey = rsaCipher.doFinal(contentKey.encoded)

        return JSONObject()
            .put("v", VERSION)
            .put("alg", ALGORITHM)
            .put("event_id", eventId)
            .put("key_id", keyId)
            .put("wrapped_key", encode(wrappedKey))
            .put("iv", encode(iv))
            .put("ciphertext", encode(ciphertext))
            .toString()
    }

    fun encrypt(payload: ByteArray, recipientPublicKeyPem: String, eventId: String): String =
        encrypt(payload, DispatchValidation.parsePublicKey(recipientPublicKeyPem), eventId)

    fun decrypt(envelope: String, recipientPrivateKey: PrivateKey, expectedEventId: String? = null): ByteArray {
        val json = try {
            JSONObject(envelope)
        } catch (_: Exception) {
            throw DispatchEnvelopeException("The dispatch envelope is invalid.")
        }
        if (json.optInt("v", -1) != VERSION || json.optString("alg") != ALGORITHM) {
            throw DispatchEnvelopeException("The dispatch envelope version or algorithm is invalid.")
        }
        val eventId = json.optString("event_id")
        validateEventId(eventId)
        if (expectedEventId != null && eventId != expectedEventId) {
            throw DispatchEnvelopeException("The dispatch envelope event identity is invalid.")
        }
        val keyId = json.optString("key_id")
        if (keyId.isEmpty() || keyId.length > 128) {
            throw DispatchEnvelopeException("The dispatch envelope recipient identity is invalid.")
        }
        val derivedKeyId = derivePublicKeyId(recipientPrivateKey)
        if (derivedKeyId != null && derivedKeyId != keyId) {
            throw DispatchEnvelopeException("The dispatch envelope recipient identity is invalid.")
        }

        val wrappedKey = decode(json.optString("wrapped_key"), MAX_WRAPPED_KEY_BYTES)
        val iv = decode(json.optString("iv"), MAX_IV_BYTES)
        val ciphertext = decode(json.optString("ciphertext"), MAX_CIPHERTEXT_BYTES)
        if (iv.size != GCM_IV_BYTES || ciphertext.size < GCM_TAG_BYTES) {
            throw DispatchEnvelopeException("The dispatch envelope is invalid.")
        }

        val rsaCipher = Cipher.getInstance(RSA_TRANSFORMATION)
        rsaCipher.init(Cipher.DECRYPT_MODE, recipientPrivateKey, OAEP_SPEC)
        val contentKeyBytes = try {
            rsaCipher.doFinal(wrappedKey)
        } catch (_: Exception) {
            throw DispatchEnvelopeException("The dispatch envelope could not be decrypted.")
        }
        if (contentKeyBytes.size != AES_KEY_BYTES) {
            throw DispatchEnvelopeException("The dispatch envelope key is invalid.")
        }

        val aesCipher = Cipher.getInstance(AES_TRANSFORMATION)
        aesCipher.init(
            Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(contentKeyBytes, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, iv),
        )
        aesCipher.updateAAD(aad(eventId, keyId))
        return try {
            aesCipher.doFinal(ciphertext)
        } catch (_: Exception) {
            throw DispatchEnvelopeException("The dispatch envelope integrity check failed.")
        }
    }

    private fun derivePublicKeyId(privateKey: PrivateKey): String? {
        val rsaPrivate = privateKey as? RSAPrivateCrtKey ?: return null
        return runCatching {
            val publicKey = KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(rsaPrivate.modulus, rsaPrivate.publicExponent),
            )
            DispatchValidation.publicKeyId(publicKey)
        }.getOrNull()
    }

    private fun validateEventId(eventId: String) {
        if (eventId.length > 64 || runCatching { UUID.fromString(eventId) }.isFailure) {
            throw DispatchEnvelopeException("The dispatch event identity is invalid.")
        }
    }

    private fun aad(eventId: String, keyId: String): ByteArray =
        "com.acousticguard.dispatch.envelope.v1|$eventId|$keyId".toByteArray(Charsets.UTF_8)

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun decode(value: String, maxBytes: Int): ByteArray {
        if (value.isEmpty() || value.length > maxBytes * 2) {
            throw DispatchEnvelopeException("The dispatch envelope is invalid.")
        }
        return try {
            val decoded = Base64.getUrlDecoder().decode(value)
            if (decoded.size > maxBytes || encode(decoded) != value) {
                throw DispatchEnvelopeException("The dispatch envelope is invalid.")
            }
            decoded
        } catch (_: IllegalArgumentException) {
            throw DispatchEnvelopeException("The dispatch envelope is invalid.")
        }
    }

    private const val AES_TRANSFORMATION = "AES/GCM/NoPadding"
    private const val RSA_TRANSFORMATION = "RSA/ECB/OAEPPadding"
    private const val GCM_IV_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
    private const val AES_KEY_BYTES = 32
    private const val MAX_PAYLOAD_BYTES = 64 * 1_024
    private const val MAX_WRAPPED_KEY_BYTES = 16 * 1_024
    private const val MAX_IV_BYTES = 32
    private const val MAX_CIPHERTEXT_BYTES = MAX_PAYLOAD_BYTES + GCM_TAG_BYTES
    private val OAEP_SPEC = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT,
    )
}
