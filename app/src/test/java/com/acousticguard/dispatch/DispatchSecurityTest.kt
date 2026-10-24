package com.acousticguard.dispatch

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DispatchSecurityTest {
    @Test
    fun endpointAndRecipientValidationRejectUnsafeSettings() {
        assertTrue(
            DispatchValidation.validateSettings(
                DispatchSettings(
                    enabled = true,
                    endpoint = "https://receiver.example.test/v1/events",
                    publicKeyPem = publicKeyPem(generateRsaKeyPair().public.encoded),
                    bearerToken = "token",
                ),
            ).isSuccess,
        )
        assertTrue(DispatchValidation.validateEndpoint("https://receiver.example.test/v1/events").startsWith("https://"))
        assertTrue(runCatching { DispatchValidation.validateEndpoint("http://receiver.example.test/events") }.isFailure)
        assertTrue(runCatching { DispatchValidation.validateEndpoint("https://user:pass@receiver.example.test/events") }.isFailure)
        assertTrue(runCatching { DispatchValidation.validateEndpoint("https://receiver.example.test/events?token=secret") }.isFailure)
        assertTrue(runCatching { DispatchValidation.validateEndpoint("https://receiver.example.test/events#fragment") }.isFailure)
        assertTrue(
            DispatchValidation.validateSettings(
                DispatchSettings(
                    enabled = false,
                    bearerToken = "line\nfeed",
                ),
            ).isFailure,
        )
    }

    @Test
    fun rsaKeyMustBeX509AndAtLeast2048Bits() {
        val small = KeyPairGenerator.getInstance("RSA").apply { initialize(1_024) }.generateKeyPair()
        val large = generateRsaKeyPair()

        assertTrue(runCatching { DispatchValidation.parsePublicKey(publicKeyPem(small.public.encoded)) }.isFailure)
        assertTrue(DispatchValidation.parsePublicKey(publicKeyPem(large.public.encoded)).encoded.contentEquals(large.public.encoded))
        assertTrue(runCatching { DispatchValidation.parsePublicKey("-----BEGIN RSA PUBLIC KEY-----\nabc\n-----END RSA PUBLIC KEY-----") }.isFailure)
    }

    @Test
    fun hybridEnvelopeDecryptsAndTamperingFailsIntegrityCheck() {
        val keyPair = generateRsaKeyPair()
        val eventId = UUID.randomUUID().toString()
        val plaintext = "metadata-only payload".toByteArray()

        val envelope = DispatchEnvelope.encrypt(plaintext, keyPair.public, eventId)
        assertFalse(envelope.contains("metadata-only"))
        assertArrayEquals(plaintext, DispatchEnvelope.decrypt(envelope, keyPair.private, eventId))

        val json = JSONObject(envelope)
        val ciphertext = json.getString("ciphertext")
        val replacement = (if (ciphertext.last() == 'A') 'B' else 'A')
        json.put("ciphertext", ciphertext.dropLast(1) + replacement)
        assertTrue(runCatching { DispatchEnvelope.decrypt(json.toString(), keyPair.private, eventId) }.isFailure)
    }

    private fun generateRsaKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2_048)
    }.generateKeyPair()

    private fun publicKeyPem(encoded: ByteArray): String = buildString {
        append("-----BEGIN PUBLIC KEY-----\n")
        append(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encoded))
        append("\n-----END PUBLIC KEY-----")
    }
}
