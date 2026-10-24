package com.acousticguard.dispatch

import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal sealed interface DispatchSendResult {
    data object Accepted : DispatchSendResult

    data class Retryable(
        val httpStatus: Int? = null,
        val ioFailure: Boolean = false,
    ) : DispatchSendResult

    data class Terminal(val reason: String) : DispatchSendResult
}

/** Built-in HTTPS sender. It uses the platform TLS stack without custom trust or hostname rules. */
internal class DispatchTransport {
    fun send(record: StoredIncident): DispatchSendResult {
        val validated = DispatchValidation.validateSettings(
            DispatchSettings(
                enabled = true,
                endpoint = record.endpoint,
                publicKeyPem = record.publicKeyPem,
                bearerToken = record.bearerToken,
            ),
        ).getOrElse { return DispatchSendResult.Terminal("Stored dispatch configuration is invalid.") }
        val publicKey = validated.publicKey
            ?: return DispatchSendResult.Terminal("Stored recipient key is invalid.")
        if (record.recipientKeyId != validated.recipientKeyId) {
            return DispatchSendResult.Terminal("Stored recipient key identity is invalid.")
        }

        val payload = JSONObject()
            .put("v", 1)
            .put("event_id", record.id)
            .put("created_at_epoch_ms", record.createdAtEpochMs)
            .put("marker", record.marker)
            .put("confidence", record.confidence.toDouble())
            .put("model_version", record.modelVersion)
            .put("test", record.test)
            .put("recipient_key_id", record.recipientKeyId)
            .toString()
            .toByteArray(Charsets.UTF_8)

        val envelope = try {
            DispatchEnvelope.encrypt(payload, publicKey, record.id).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            return DispatchSendResult.Terminal("The dispatch envelope could not be created.")
        }
        if (envelope.size > MAX_REQUEST_BYTES) {
            return DispatchSendResult.Terminal("The dispatch request is too large.")
        }

        val connection = try {
            URL(record.endpoint).openConnection() as? HttpsURLConnection
                ?: return DispatchSendResult.Terminal("The dispatch endpoint is not HTTPS.")
        } catch (_: MalformedURLException) {
            return DispatchSendResult.Terminal("The stored dispatch endpoint is invalid.")
        }

        return try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setInstanceFollowRedirects(false)
            connection.useCaches = false
            connection.doInput = true
            connection.doOutput = true
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Idempotency-Key", record.id)
            if (record.bearerToken.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer ${record.bearerToken}")
            }
            connection.setFixedLengthStreamingMode(envelope.size)
            connection.outputStream.use { output ->
                output.write(envelope)
                output.flush()
            }

            val responseCode = connection.responseCode
            // The body is deliberately not parsed or persisted. Reading only a bounded prefix lets
            // the platform reuse/close the connection without allowing an unbounded response.
            runCatching {
                boundedRead(if (responseCode >= 400) connection.errorStream else connection.inputStream)
            }
            when {
                responseCode in 200..299 -> DispatchSendResult.Accepted
                DispatchPolicy.isRetryableHttpStatus(responseCode) -> DispatchSendResult.Retryable(httpStatus = responseCode)
                else -> DispatchSendResult.Terminal(
                    if (responseCode in 300..399) {
                        "The dispatch endpoint returned a redirect."
                    } else {
                        "The dispatch endpoint rejected the event."
                    },
                )
            }
        } catch (_: IOException) {
            DispatchSendResult.Retryable(ioFailure = true)
        } catch (_: SecurityException) {
            DispatchSendResult.Terminal("The dispatch connection was rejected.")
        } finally {
            connection.disconnect()
        }
    }

    private fun boundedRead(stream: java.io.InputStream?) {
        if (stream == null) return
        stream.use { input ->
            val buffer = ByteArray(RESPONSE_BUFFER_BYTES)
            var remaining = MAX_RESPONSE_BYTES
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read <= 0) break
                remaining -= read
            }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 10_000
        const val MAX_RESPONSE_BYTES = 16 * 1_024
        const val RESPONSE_BUFFER_BYTES = 4 * 1_024
        const val MAX_REQUEST_BYTES = 64 * 1_024
    }
}
