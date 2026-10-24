package com.acousticguard.dispatch

import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

internal data class ValidatedDispatchSettings(
    val settings: DispatchSettings,
    val publicKey: PublicKey?,
    val recipientKeyId: String,
)

internal object DispatchValidation {
    private const val PUBLIC_KEY_BEGIN = "-----BEGIN PUBLIC KEY-----"
    private const val PUBLIC_KEY_END = "-----END PUBLIC KEY-----"

    fun validateSettings(settings: DispatchSettings): Result<ValidatedDispatchSettings> = runCatching {
        if (settings.endpoint.length > MAX_ENDPOINT_LENGTH) {
            invalid("The dispatch endpoint is too long.")
        }
        if (settings.publicKeyPem.length > MAX_PUBLIC_KEY_PEM_LENGTH) {
            invalid("The recipient public key is too long.")
        }
        if (settings.bearerToken.length > MAX_BEARER_TOKEN_LENGTH) {
            invalid("The bearer token is too long.")
        }
        rejectHeaderControls(settings.bearerToken, "The bearer token contains forbidden characters.")

        val endpoint = if (settings.endpoint.isEmpty()) {
            null
        } else {
            validateEndpoint(settings.endpoint)
        }
        val publicKey = if (settings.publicKeyPem.isEmpty()) {
            null
        } else {
            parsePublicKey(settings.publicKeyPem)
        }

        if (settings.enabled && (endpoint == null || publicKey == null)) {
            invalid("Enabled dispatch requires an HTTPS endpoint and an RSA public key.")
        }

        ValidatedDispatchSettings(
            settings = settings,
            publicKey = publicKey,
            recipientKeyId = publicKey?.let(::publicKeyId).orEmpty(),
        )
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { failure ->
            if (failure is DispatchValidationException) {
                Result.failure(failure)
            } else {
                Result.failure(DispatchValidationException("The dispatch settings are invalid."))
            }
        },
    )

    fun validateEnqueue(marker: String, confidence: Float, modelVersion: String): Result<Unit> = runCatching {
        if (marker.isBlank() || marker.length > MAX_MARKER_LENGTH || hasControlCharacters(marker)) {
            invalid("The incident marker is invalid.")
        }
        if (!confidence.isFinite() || confidence !in 0f..1f) {
            invalid("The incident confidence is invalid.")
        }
        if (modelVersion.isBlank() || modelVersion.length > MAX_MODEL_VERSION_LENGTH || hasControlCharacters(modelVersion)) {
            invalid("The model version is invalid.")
        }
        Unit
    }.fold(
        onSuccess = { Result.success(Unit) },
        onFailure = { failure ->
            if (failure is DispatchValidationException) {
                Result.failure(failure)
            } else {
                Result.failure(DispatchValidationException("The incident metadata is invalid."))
            }
        },
    )

    fun validateEndpoint(endpoint: String): String {
        if (endpoint.length > MAX_ENDPOINT_LENGTH || endpoint.isEmpty() || hasControlCharacters(endpoint)) {
            invalid("The dispatch endpoint is invalid.")
        }
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            invalid("The dispatch endpoint is invalid.")
        }
        if (!uri.isAbsolute || !uri.scheme.equals("https", ignoreCase = true)) {
            invalid("The dispatch endpoint must use HTTPS.")
        }
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawFragment != null || uri.rawQuery != null) {
            invalid("The dispatch endpoint must not contain user info, fragments, or query parameters.")
        }
        if (uri.port == 0 || uri.rawAuthority.isNullOrBlank()) {
            invalid("The dispatch endpoint is invalid.")
        }
        return endpoint
    }

    fun parsePublicKey(publicKeyPem: String): PublicKey {
        if (publicKeyPem.length > MAX_PUBLIC_KEY_PEM_LENGTH) {
            invalid("The recipient public key is too long.")
        }
        val normalized = publicKeyPem.trim()
        if (!normalized.startsWith(PUBLIC_KEY_BEGIN) || !normalized.endsWith(PUBLIC_KEY_END)) {
            invalid("The recipient key must be an X509 PUBLIC KEY PEM.")
        }
        val body = normalized
            .removePrefix(PUBLIC_KEY_BEGIN)
            .removeSuffix(PUBLIC_KEY_END)
            .filterNot { Character.isWhitespace(it) }
        if (body.isEmpty() || body.contains('-')) {
            invalid("The recipient key must be an X509 PUBLIC KEY PEM.")
        }
        val der = try {
            Base64.getDecoder().decode(body)
        } catch (_: IllegalArgumentException) {
            invalid("The recipient key must be an X509 PUBLIC KEY PEM.")
        }
        val key = try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        } catch (_: Exception) {
            invalid("The recipient key must be an X509 PUBLIC KEY PEM.")
        }
        val rsaKey = key as? RSAPublicKey ?: invalid("The recipient key must be RSA.")
        if (rsaKey.modulus.bitLength() < 2_048) {
            invalid("The recipient RSA key must be at least 2048 bits.")
        }
        return key
    }

    fun publicKeyId(publicKey: PublicKey): String = Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(publicKey.encoded))

    private fun rejectHeaderControls(value: String, message: String) {
        if (value.any { it == '\r' || it == '\n' || Character.isISOControl(it) }) {
            invalid(message)
        }
    }

    private fun hasControlCharacters(value: String): Boolean = value.any { Character.isISOControl(it) }

    private fun invalid(message: String): Nothing = throw DispatchValidationException(message)
}
